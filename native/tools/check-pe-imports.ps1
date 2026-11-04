<#
.SYNOPSIS
    Inspects a PE image's imports without requiring dumpbin or LLVM.

.DESCRIPTION
    A PE-parsing dependency check for Windows native artifacts. It exists so the
    CI host matrix can prove that a distributed DLL imports only Windows system
    libraries, on every entry, including the LLVM-MinGW ones where no Visual
    Studio toolchain is present. Bundling a compiler runtime (libc++.dll,
    libgcc_s_seh-1.dll, libwinpthread-1.dll, ...) would make a copied DLL fail
    on an operator's machine, and that must be a build failure, not a surprise.

.PARAMETER Path
    The PE file to inspect.

.PARAMETER ExpectMachine
    Optional. x86_64 or aarch64. When given, the COFF machine field must match.

.PARAMETER AllowMsvcruntime
    Accept a dynamic MSVC CRT. Passed when the build records
    VELTIS_NATIVE_STATIC_RUNTIME=OFF; MinGW/LLVM runtimes stay rejected.

.NOTES
    Exit code 0 on success, 1 on any unexpected import or machine mismatch.
    This is the one authority for the Windows runtime policy: the CI dependency
    step and ':veltis-native:verifyNativeRuntimePolicy' both call it, so a
    release run and a CI run cannot disagree about what the artifact imports.
    Imports that are neither a compiler runtime nor a system library are not
    rejected here - the list is printed for review, and widening these patterns
    is a decision a reviewer makes consciously.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $Path,
    [ValidateSet('x86_64', 'aarch64', 'x86', 'arm64ec')][string] $ExpectMachine,
    # Dynamic MSVC CRT (vcruntime140.dll, msvcp140.dll, ...) is a legitimate
    # configuration: VELTIS_NATIVE_STATIC_RUNTIME defaults to OFF for MSVC, and
    # the manifest records that choice. MinGW/LLVM runtimes are never legitimate
    # for a redistributed DLL, so they are always rejected.
    [switch] $AllowMsvcruntime
)

$ErrorActionPreference = 'Stop'

# Compiler runtimes that must never be an import of a redistributed artifact.
# libatomic is in the list because a cross build for aarch64 can pull it in for
# 128-bit atomics; every entry here is a file the toolchain installs beside its
# own binaries and a server operator does not have.
$MinGwRuntimePattern = '(?i)^(libc\+\+|libunwind|libgcc|libstdc\+\+|libwinpthread|libssp|libatomic)[^/\\]*\.dll$'
$MsvcruntimePattern = '(?i)^(vcruntime|msvcp|msvcr|concrt)[0-9_]*\.dll$'

$MachineNames = @{
    0x014C = 'x86'
    0x8664 = 'x86_64'
    0xAA64 = 'aarch64'
    0xA641 = 'arm64ec'
}

function Read-At {
    param([byte[]] $Bytes, [long] $Offset, [int] $Count)
    if ($Offset -lt 0 -or $Offset + $Count -gt $Bytes.Length) {
        throw "PE structure points outside the file (offset $Offset, length $Count, file $($Bytes.Length))"
    }
    return $Bytes[$Offset..($Offset + $Count - 1)]
}

function Read-U16 { param([byte[]] $Bytes, [long] $Offset) [BitConverter]::ToUInt16($Bytes, [int] $Offset) }
function Read-U32 { param([byte[]] $Bytes, [long] $Offset) [BitConverter]::ToUInt32($Bytes, [int] $Offset) }

function Convert-RvaToOffset {
    param([byte[]] $Bytes, [long] $SectionTable, [int] $SectionCount, [long] $Rva)
    for ($i = 0; $i -lt $SectionCount; $i++) {
        $header = $SectionTable + ($i * 40)
        $virtualSize = Read-U32 $Bytes ($header + 8)
        $virtualAddress = Read-U32 $Bytes ($header + 12)
        $rawSize = Read-U32 $Bytes ($header + 16)
        $rawPointer = Read-U32 $Bytes ($header + 20)
        $span = [Math]::Max($virtualSize, $rawSize)
        if ($Rva -ge $virtualAddress -and $Rva -lt ($virtualAddress + $span)) {
            return $rawPointer + ($Rva - $virtualAddress)
        }
    }
    throw ("RVA 0x{0:X} is not inside any section" -f $Rva)
}

function Read-CString {
    param([byte[]] $Bytes, [long] $Offset)
    $end = $Offset
    while ($end -lt $Bytes.Length -and $Bytes[$end] -ne 0) { $end++ }
    if ($end -ge $Bytes.Length) { throw "unterminated import name at offset $Offset" }
    return [Text.Encoding]::ASCII.GetString($Bytes, [int] $Offset, [int] ($end - $Offset))
}

if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    throw "no such file: $Path"
}

$bytes = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path).Path)
if ($bytes.Length -lt 0x40 -or $bytes[0] -ne 0x4D -or $bytes[1] -ne 0x5A) {
    throw "not a PE image (missing MZ header): $Path"
}

$peOffset = Read-U32 $bytes 0x3C
if ($peOffset + 24 -gt $bytes.Length) { throw "e_lfanew points outside the file" }
if ($bytes[$peOffset] -ne 0x50 -or $bytes[$peOffset + 1] -ne 0x45) {
    throw "not a PE image (missing PE signature): $Path"
}

$machine = Read-U16 $bytes ($peOffset + 4)
$sectionCount = Read-U16 $bytes ($peOffset + 6)
$optionalSize = Read-U16 $bytes ($peOffset + 20)
$optional = $peOffset + 24
$magic = Read-U16 $bytes $optional
$pe32Plus = $magic -eq 0x20B
if (-not $pe32Plus -and $magic -ne 0x10B) { throw ("unknown optional header magic 0x{0:X}" -f $magic) }

$directoryOffset = if ($pe32Plus) { $optional + 112 } else { $optional + 96 }
# Data directory 0 is the export table; the import table is entry 1.
$importRva = Read-U32 $bytes ($directoryOffset + 8)
$importSize = Read-U32 $bytes ($directoryOffset + 12)

$machineName = if ($MachineNames.ContainsKey([int] $machine)) { $MachineNames[[int] $machine] } else { 'unknown' }
Write-Output "file:      $((Resolve-Path -LiteralPath $Path).Path)"
Write-Output ("machine:   {0} (0x{1:X4})" -f $machineName, $machine)
Write-Output ("sections:  {0}" -f $sectionCount)

if ($PSBoundParameters.ContainsKey('ExpectMachine')) {
    $expected = switch ($ExpectMachine) { 'arm64ec' { 'arm64ec' } default { $ExpectMachine } }
    if ($machineName -ne $expected) {
        Write-Error "expected a $expected image, found $machineName"
        exit 1
    }
}

if ($importRva -eq 0 -or $importSize -eq 0) {
    Write-Output 'imports:   (none)'
    exit 0
}

$sectionTable = $optional + $optionalSize
$importOffset = Convert-RvaToOffset $bytes $sectionTable $sectionCount $importRva
$imports = [Collections.Generic.List[string]]::new()

for ($descriptor = $importOffset; ; $descriptor += 20) {
    $nameRva = Read-U32 $bytes ($descriptor + 12)
    $firstThunk = Read-U32 $bytes ($descriptor + 16)
    if ($nameRva -eq 0 -and $firstThunk -eq 0) { break }
    $nameOffset = Convert-RvaToOffset $bytes $sectionTable $sectionCount $nameRva
    $imports.Add((Read-CString $bytes $nameOffset))
    if ($imports.Count -gt 4096) { throw "import table is implausibly large" }
}

Write-Output 'imports:'
foreach ($name in ($imports | Sort-Object -Unique)) { Write-Output "  $name" }

$unique = @($imports | Sort-Object -Unique)

$mingwRuntime = @($unique | Where-Object { $_ -match $MinGwRuntimePattern })
if ($mingwRuntime.Count -gt 0) {
    Write-Error ("unexpected MinGW/LLVM compiler runtime dependency: " + ($mingwRuntime -join ', '))
    exit 1
}

$msvcRuntime = @($unique | Where-Object { $_ -match $MsvcruntimePattern })
if ($msvcRuntime.Count -gt 0) {
    if ($AllowMsvcruntime) {
        Write-Output ('dynamic MSVC runtime (allowed by configuration): ' + ($msvcRuntime -join ', '))
    } else {
        Write-Error ("MSVC runtime import but the artifact claims a static runtime: " + ($msvcRuntime -join ', '))
        exit 1
    }
} else {
    Write-Output 'compiler runtime: none imported'
}

# A DLL that imports nothing from the Windows API is almost certainly wrong.
if (-not ($imports | Where-Object { $_ -match '(?i)^(kernel32|ntdll|advapi32|ucrtbase|api-ms-win-)' })) {
    Write-Error 'no Windows system library import found; the artifact looks malformed'
    exit 1
}

Write-Output 'dependency check: passed (no compiler runtime DLLs)'
