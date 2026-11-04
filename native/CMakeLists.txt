cmake_minimum_required(VERSION 3.21)

# ---------------------------------------------------------------------------
# Minimum macOS version, decided before project()
#
# This has to happen first, not in the body below: the Darwin platform modules
# turn CMAKE_OSX_DEPLOYMENT_TARGET into -mmacosx-version-min while project()
# runs, so a value assigned after it reaches neither the compiler nor the linker.
# APPLE is also unusable here — it is only true from project() onwards — so the
# platform is detected from the toolchain file's CMAKE_SYSTEM_NAME (a cross
# build) or from CMAKE_HOST_APPLE (a native one).
#
# Precedence, highest first: an explicitly passed -D, a value left in the cache
# by an earlier configure, MACOSX_DEPLOYMENT_TARGET, then this default. An
# environment variable must be honoured rather than shadowed, because CMake only
# reads it when the cache variable is still undefined — which this block is about
# to define.
# ---------------------------------------------------------------------------
set(VELTIS_NATIVE_MACOS_MINIMUM_VERSION "11.0" CACHE STRING
    "Default minimum macOS version when nothing else specifies one")

if(NOT DEFINED CMAKE_OSX_DEPLOYMENT_TARGET OR CMAKE_OSX_DEPLOYMENT_TARGET STREQUAL "")
    if(CMAKE_SYSTEM_NAME STREQUAL "Darwin" OR (CMAKE_HOST_APPLE AND NOT CMAKE_TOOLCHAIN_FILE))
        if(DEFINED ENV{MACOSX_DEPLOYMENT_TARGET} AND NOT "$ENV{MACOSX_DEPLOYMENT_TARGET}" STREQUAL "")
            set(CMAKE_OSX_DEPLOYMENT_TARGET "$ENV{MACOSX_DEPLOYMENT_TARGET}" CACHE STRING
                "Minimum macOS version" FORCE)
            message(STATUS "VeltisNative: macOS deployment target taken from MACOSX_DEPLOYMENT_TARGET")
        else()
            set(CMAKE_OSX_DEPLOYMENT_TARGET "${VELTIS_NATIVE_MACOS_MINIMUM_VERSION}" CACHE STRING
                "Minimum macOS version" FORCE)
            message(STATUS "VeltisNative: macOS deployment target defaulted to "
                "${VELTIS_NATIVE_MACOS_MINIMUM_VERSION}")
        endif()
    endif()
endif()

project(VeltisNative
    VERSION 1.0.0
    DESCRIPTION "VeltisMC optional native engine"
    LANGUAGES C CXX
)

# Gradle passes the platform-qualified output directory explicitly. The fallback
# keeps a manual `cmake -S . -B build` usable without any Gradle properties.
set(VELTIS_NATIVE_OUTPUT_DIR "${CMAKE_BINARY_DIR}/out" CACHE PATH
    "Directory receiving the shared library and test fixtures")

option(VELTIS_NATIVE_BUILD_TESTS "Build the native engine test executable and fixtures" ON)

# The DLL should not need a compiler runtime beside it. A shipped artifact is
# copied next to a JAR, and vcruntime140.dll is not something that copy carries,
# so every Windows toolchain links its runtime in by default: MinGW with
# -static (which also covers winpthreads), LLVM-MinGW with
# -static-libgcc/-static-libstdc++, MSVC and clang-cl with the static CRT (/MT).
# This is safe here only because the ABI crosses no CRT state at all — no
# allocator contract, no FILE*, no exception — which veltis_native.h records as a
# rule. Linux and macOS use the system runtime, the normal convention there.
# -DVELTIS_NATIVE_STATIC_RUNTIME=OFF opts back out when the CRT is a deliberate
# choice, and the manifest records whichever way the link step went.
if(MINGW OR MSVC)
    set(VELTIS_NATIVE_STATIC_RUNTIME_DEFAULT ON)
else()
    set(VELTIS_NATIVE_STATIC_RUNTIME_DEFAULT OFF)
endif()
option(VELTIS_NATIVE_STATIC_RUNTIME
    "Link the C/C++ runtime statically where the toolchain supports it"
    ${VELTIS_NATIVE_STATIC_RUNTIME_DEFAULT})

# --- diagnostics -------------------------------------------------------------
# OFF by default, so a normal build reports warnings without refusing them. A
# release or CI lane turns it on; the reason it is not simply always on is that a
# new compiler release can make the build red for reasons nobody reviewed, which
# is a worse failure than a warning.
option(VELTIS_NATIVE_WERROR "Treat compiler warnings as errors" OFF)

# Empty means "no sanitizer", which is the release configuration. The list is
# validated against what the compiler can actually build *and link* before any
# target exists, because a requested sanitizer that silently does nothing is the
# one outcome worse than no sanitizer: someone would read the run as evidence.
set(VELTIS_NATIVE_SANITIZERS "" CACHE STRING
    "Comma-separated sanitizers: address, undefined, thread, memory")

if(VELTIS_NATIVE_WERROR)
    if(MSVC)
        add_compile_options(/WX)
    else()
        add_compile_options(-Werror)
    endif()
    message(STATUS "VeltisNative: warnings are errors (VELTIS_NATIVE_WERROR=ON)")
endif()

set(VELTIS_NATIVE_SANITIZER_NAMES address undefined thread memory)
string(REPLACE "," ";" VELTIS_NATIVE_SANITIZER_LIST "${VELTIS_NATIVE_SANITIZERS}")
list(REMOVE_ITEM VELTIS_NATIVE_SANITIZER_LIST "")
list(LENGTH VELTIS_NATIVE_SANITIZER_LIST VELTIS_NATIVE_SANITIZER_COUNT)

if(VELTIS_NATIVE_SANITIZER_COUNT GREATER 0)
    foreach(san IN LISTS VELTIS_NATIVE_SANITIZER_LIST)
        if(NOT san IN_LIST VELTIS_NATIVE_SANITIZER_NAMES)
            list(JOIN VELTIS_NATIVE_SANITIZER_NAMES ", " VELTIS_NATIVE_SANITIZER_NAMES_TEXT)
            message(FATAL_ERROR
                "VeltisNative: unknown sanitizer '${san}'. Known names: "
                "${VELTIS_NATIVE_SANITIZER_NAMES_TEXT}.")
        endif()
        if(MSVC AND NOT san STREQUAL "address")
            # MSVC's own sanitizer support is AddressSanitizer (via clang-cl
            # instrumentation); there is no /fsanitize=undefined or /fsanitize=thread.
            message(FATAL_ERROR
                "VeltisNative: MSVC provides only '/fsanitize=address'; '${san}' is not "
                "available on this compiler. docs/TESTING_STRATEGY.md records the matrix.")
        endif()
    endforeach()

    if("thread" IN_LIST VELTIS_NATIVE_SANITIZER_LIST AND
       ("address" IN_LIST VELTIS_NATIVE_SANITIZER_LIST
        OR "memory" IN_LIST VELTIS_NATIVE_SANITIZER_LIST))
        # Not a limitation of this project: the runtimes instrument memory the same
        # way and cannot share a process. Two build directories is the answer.
        message(FATAL_ERROR
            "VeltisNative: ThreadSanitizer cannot be combined with address or memory. "
            "Configure two separate build directories instead of asking for both at once.")
    endif()

    include(CheckCXXSourceCompiles)
    foreach(san IN LISTS VELTIS_NATIVE_SANITIZER_LIST)
        if(MSVC)
            set(VELTIS_NATIVE_SANITIZER_FLAG "/fsanitize=address")
        else()
            set(VELTIS_NATIVE_SANITIZER_FLAG "-fsanitize=${san}")
        endif()

        # Accepting the flag is not the same as being able to link it: a MinGW
        # toolchain built without libasan or libubsan takes -fsanitize=address and
        # then fails at ld. Probing a compile *and* link is the only way to know at
        # configure time, and a clear refusal there is worth more than a link-time
        # stack trace — or, worst of all, an uninstrumented binary someone quotes
        # as sanitizer evidence. The cache entry is removed first so a toolchain
        # switch re-probes instead of trusting the previous answer.
        string(TOUPPER "${san}" VELTIS_NATIVE_SANITIZER_UPPER)
        set(VELTIS_NATIVE_SANITIZER_PROBE "VELTIS_NATIVE_SANITIZER_${VELTIS_NATIVE_SANITIZER_UPPER}_USABLE")
        unset(${VELTIS_NATIVE_SANITIZER_PROBE} CACHE)
        set(CMAKE_REQUIRED_QUIET TRUE)
        set(CMAKE_REQUIRED_FLAGS "${VELTIS_NATIVE_SANITIZER_FLAG}")
        check_cxx_source_compiles("
#include <cstdlib>
int main() {
    int* block = static_cast<int*>(std::malloc(sizeof(int) * 4));
    if (block == nullptr) { return 1; }
    block[0] = 1;
    const int value = block[0];
    std::free(block);
    return value == 1 ? 0 : 1;
}
" ${VELTIS_NATIVE_SANITIZER_PROBE})
        unset(CMAKE_REQUIRED_FLAGS)
        unset(CMAKE_REQUIRED_QUIET)

        if(NOT ${VELTIS_NATIVE_SANITIZER_PROBE})
            message(FATAL_ERROR
                "VeltisNative: this toolchain cannot compile and link "
                "${VELTIS_NATIVE_SANITIZER_FLAG} (${CMAKE_CXX_COMPILER_ID} "
                "${CMAKE_CXX_COMPILER_VERSION} on ${CMAKE_SYSTEM_NAME}). It accepts the flag "
                "but has no sanitizer runtime. Drop '${san}' from VELTIS_NATIVE_SANITIZERS or "
                "use a toolchain that ships libasan/libubsan/libtsan; "
                "docs/TESTING_STRATEGY.md records which combinations are expected to work.")
        endif()
        message(STATUS "VeltisNative: ${VELTIS_NATIVE_SANITIZER_FLAG} verified against this toolchain")
        add_compile_options("${VELTIS_NATIVE_SANITIZER_FLAG}")
        add_link_options("${VELTIS_NATIVE_SANITIZER_FLAG}")
        if(NOT MSVC)
            add_compile_options(-fno-omit-frame-pointer -g)
            if(san STREQUAL "undefined" OR san STREQUAL "address" OR san STREQUAL "memory")
                # A recovered defect is a missed one in a run nobody is watching.
                # The spelling matters: neither clang nor GCC accepts
                # `-fsanitize-recover=off` (clang rejects `off` outright), so the
                # negative form with the sanitizer's own name is used. UBSan and
                # MSan recover by default; ASan aborts by default and this flag
                # only restates that.
                add_compile_options(-fno-sanitize-recover=${san})
            endif()
        endif()
    endforeach()

    # A sanitizer build is a developer lane, never a shipped artifact, and the
    # static-runtime options fight the dynamic sanitizer runtime on Windows.
    if(VELTIS_NATIVE_STATIC_RUNTIME AND MINGW AND
       ("address" IN_LIST VELTIS_NATIVE_SANITIZER_LIST
        OR "undefined" IN_LIST VELTIS_NATIVE_SANITIZER_LIST
        OR "thread" IN_LIST VELTIS_NATIVE_SANITIZER_LIST))
        message(STATUS
            "VeltisNative: static runtime is ignored for this sanitizer build; the "
            "sanitizer runtimes are shared libraries. A release build is unaffected "
            "(VELTIS_NATIVE_STATIC_RUNTIME stays ON there).")
        set(VELTIS_NATIVE_STATIC_RUNTIME OFF)
    endif()
endif()

# Recorded in the build metadata: a manifest that names its sanitizers makes an
# instrumented artifact impossible to mistake for a release one.
list(JOIN VELTIS_NATIVE_SANITIZER_LIST "," VELTIS_NATIVE_SANITIZERS_NORMALIZED)
if(VELTIS_NATIVE_SANITIZER_COUNT GREATER 0)
    message(STATUS "VeltisNative: sanitizer build enabled: ${VELTIS_NATIVE_SANITIZERS_NORMALIZED}")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# A single-config generator otherwise produces an unoptimised, undefined build
# type. A manual `cmake -S . -B build` should behave like the documented Release
# build; presets and Gradle still pass an explicit type.
if(NOT CMAKE_CONFIGURATION_TYPES AND NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
    message(STATUS "VeltisNative: CMAKE_BUILD_TYPE was empty; defaulting to Release")
endif()

# `math(EXPR)` is required: CMake message arguments understand only plain
# `${variable}` references, not arithmetic inside them.
math(EXPR VELTIS_NATIVE_POINTER_BITS "${CMAKE_SIZEOF_VOID_P} * 8")

message(STATUS
    "VeltisNative: system=${CMAKE_SYSTEM_NAME} processor=${CMAKE_SYSTEM_PROCESSOR} "
    "pointer=${VELTIS_NATIVE_POINTER_BITS}-bit "
    "C=${CMAKE_C_COMPILER_ID} ${CMAKE_C_COMPILER_VERSION} "
    "CXX=${CMAKE_CXX_COMPILER_ID} ${CMAKE_CXX_COMPILER_VERSION}")
if(CMAKE_SIZEOF_VOID_P LESS 8)
    message(WARNING
        "VeltisNative: 32-bit builds are not a supported release target. "
        "The C ABI remains size_t-correct, but the Java FFM integration only "
        "recognises 64-bit x86-64 and AArch64 platforms.")
endif()

if(CMAKE_SYSTEM_NAME STREQUAL "Darwin" AND NOT CMAKE_OSX_DEPLOYMENT_TARGET)
    message(WARNING
        "VeltisNative: no macOS deployment target was set, so the artifact inherits the SDK "
        "version of this runner and silently narrows compatibility to it. Reconfigure with "
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=<x.y> (or delete the CMake cache) for a redistributable build.")
endif()

add_library(veltis_native SHARED
    src/c/native_utils.c
    src/cpp/native_engine.cpp
    src/cpp/algorithms.cpp
)

target_compile_definitions(veltis_native PRIVATE
    VELTIS_NATIVE_EXPORTS
)

target_compile_features(veltis_native PRIVATE
    c_std_11
    cxx_std_20
)

target_include_directories(veltis_native
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src/c
        ${CMAKE_CURRENT_SOURCE_DIR}/src/cpp
)

set_target_properties(veltis_native PROPERTIES
    OUTPUT_NAME "veltis_native"
    C_VISIBILITY_PRESET hidden
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
    POSITION_INDEPENDENT_CODE ON
)

# Warnings are applied per target instead of globally: consumers of the public
# header must not inherit this project's warning policy, and the test/fixture
# targets should be held to the same standard as the library.
#
# The extra flags are the ones that pay for themselves on this code: a shadowed
# `count` or `bytes` is the shape of bug a size-checking function hides, a
# missing declaration means a definition no translation unit was checked against,
# and writing to a string literal is a crash waiting for a different OS.
# `-Wconversion` and `-Wcast-align=strict` are deliberately *not* here: the first
# fires on every legitimate `size_t` narrowing in this module, and a policy that
# needs suppression comments is a policy that gets switched off.
function(veltis_enable_warnings target)
    if(MSVC)
        target_compile_options(${target} PRIVATE /W4)
    else()
        target_compile_options(${target} PRIVATE
            -Wall -Wextra -Wpedantic
            -Wshadow -Wwrite-strings -Wmissing-declarations -Wredundant-decls)
    endif()
endfunction()

function(veltis_configure_runtime target)
    if(MSVC AND VELTIS_NATIVE_STATIC_RUNTIME)
        set_property(TARGET ${target} PROPERTY
            MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    endif()
    if(MINGW AND VELTIS_NATIVE_STATIC_RUNTIME)
        # Keep libgcc and libc++ inside the artifact. The UCRT API set that
        # remains is part of Windows, not of the compiler.
        if(CMAKE_C_COMPILER_ID STREQUAL "GNU")
            # MinGW-w64 GCC uses the POSIX thread model, and its static libstdc++
            # asks ld for winpthreads from inside the archive, so -static-libgcc
            # and -static-libstdc++ still leave libwinpthread-1.dll imported: the
            # static preference has to cover the whole library section, which is
            # what the driver's -static does. System libraries are single-flavour
            # import libraries, so only the compiler runtime changes.
            target_link_options(${target} PRIVATE -static)
        else()
            target_link_options(${target} PRIVATE -static-libgcc)
            # A C-only target does not link libstdc++, and clang warns that the
            # option was unused, so pass it only where it applies. Callers add the
            # C_ONLY keyword for a pure-C executable such as the export checker.
            if(NOT "C_ONLY" IN_LIST ARGN)
                target_link_options(${target} PRIVATE -static-libstdc++)
            endif()
        endif()
    endif()
endfunction()

veltis_enable_warnings(veltis_native)
veltis_configure_runtime(veltis_native)

# On ELF platforms a shared object is allowed unresolved symbols by default.
# The Java loader would then fail at dlopen time, far from the build, so make the
# link step refuse them. PE and Mach-O already behave this way.
if(NOT WIN32 AND NOT APPLE)
    target_link_options(veltis_native PRIVATE "LINKER:--no-undefined")
endif()

# Reproducible artifact bytes. PE embeds the link time, which changes between
# otherwise identical builds and makes the published checksum meaningless; ELF
# build IDs are content-derived. Mach-O was given the same treatment with
# `-no_uuid`, but Apple's current linker refuses to link ANY consumer against a
# dylib that has no LC_UUID — the CI test executables fail with
# "missing LC_UUID load command in libveltis_native.dylib" — and suppressing it
# was never executed on a macOS runner before the first CI run showed this.
# macOS therefore keeps its UUID: byte reproducibility is a Windows/ELF property
# and is deliberately not claimed for macOS builds.
if(MSVC)
    target_link_options(veltis_native PRIVATE "/Brepro")
elseif(MINGW)
    target_link_options(veltis_native PRIVATE "-Wl,--no-insert-timestamp")
endif()

# Belt and braces for the deployment target (macOS only). CMake's Darwin
# platform module normally turns CMAKE_OSX_DEPLOYMENT_TARGET into
# -mmacosx-version-min while project() runs; if it did not, the artifact would
# silently carry the SDK's own floor while the manifest claimed the configured
# one. The value is still only a build-time floor: it is not evidence that an
# older macOS executed the library, and the JVM's own requirement is usually
# higher than this.
if(APPLE AND CMAKE_OSX_DEPLOYMENT_TARGET AND NOT CMAKE_C_FLAGS MATCHES "mmacosx-version-min")
    message(STATUS
        "VeltisNative: -mmacosx-version-min was absent from the compiler flags; "
        "adding it explicitly for ${CMAKE_OSX_DEPLOYMENT_TARGET}")
    target_compile_options(veltis_native PRIVATE
        "-mmacosx-version-min=${CMAKE_OSX_DEPLOYMENT_TARGET}")
    target_link_options(veltis_native PRIVATE
        "-mmacosx-version-min=${CMAKE_OSX_DEPLOYMENT_TARGET}")
endif()

# A multi-config generator (Visual Studio, Xcode, Ninja Multi-Config) appends a
# per-configuration directory unless each one is named. Setting both the generic
# and per-configuration properties is what keeps the shared library at exactly
# build/native/<platform>/ on every generator.
function(veltis_set_output_directory target directory)
    set_target_properties(${target} PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${directory}"
        LIBRARY_OUTPUT_DIRECTORY "${directory}"
        ARCHIVE_OUTPUT_DIRECTORY "${directory}"
    )
    if(WIN32)
        # MinGW and some other generators otherwise put a "lib" prefix on a
        # Windows DLL, while MSVC does not. The Java loader and the install
        # instructions name one file, so normalize the prefix here instead of
        # teaching the loader two spellings.
        set_target_properties(${target} PROPERTIES PREFIX "")
    endif()
    foreach(config Debug Release RelWithDebInfo MinSizeRel)
        set_target_properties(${target} PROPERTIES
            RUNTIME_OUTPUT_DIRECTORY_${config} "${directory}"
            LIBRARY_OUTPUT_DIRECTORY_${config} "${directory}"
            ARCHIVE_OUTPUT_DIRECTORY_${config} "${directory}"
        )
    endforeach()
endfunction()

veltis_set_output_directory(veltis_native "${VELTIS_NATIVE_OUTPUT_DIR}")

# ---------------------------------------------------------------------------
# Build metadata
#
# The version, ABI and export list are read from the public header rather than
# duplicated here, so the manifest cannot drift from the ABI. Gradle's
# `packageNative` task turns this file into the distribution manifest; a manual
# CMake build still gets the raw metadata beside the library.
# ---------------------------------------------------------------------------
file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/include/veltis/native/veltis_native.h"
    VELTIS_NATIVE_HEADER_CONSTANTS
    REGEX "^#define VELTIS_NATIVE_(ABI_VERSION|VERSION_MAJOR|VERSION_MINOR|VERSION_PATCH|VERSION_STRING)[ \t]")
foreach(_line IN LISTS VELTIS_NATIVE_HEADER_CONSTANTS)
    if(_line MATCHES "^#define VELTIS_NATIVE_ABI_VERSION[ \t]+([0-9]+)")
        set(VELTIS_NATIVE_ABI_VERSION "${CMAKE_MATCH_1}")
    elseif(_line MATCHES "^#define VELTIS_NATIVE_VERSION_STRING[ \t]+\"([^\"]+)\"")
        set(VELTIS_NATIVE_VERSION_STRING "${CMAKE_MATCH_1}")
    endif()
endforeach()
if(NOT VELTIS_NATIVE_ABI_VERSION OR NOT VELTIS_NATIVE_VERSION_STRING)
    message(FATAL_ERROR "VeltisNative: could not read the ABI/version macros from the public header")
endif()

file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/include/veltis/native/veltis_native.h"
    VELTIS_NATIVE_API_LINES
    REGEX "^VELTIS_NATIVE_API ")
set(VELTIS_NATIVE_EXPORTS "")
foreach(_line IN LISTS VELTIS_NATIVE_API_LINES)
    # The exported identifier is the last identifier before the opening paren.
    string(REGEX REPLACE "^.*[ *]([A-Za-z_][A-Za-z0-9_]*)\\(.*$" "\\1" _symbol "${_line}")
    list(APPEND VELTIS_NATIVE_EXPORTS "${_symbol}")
endforeach()
list(LENGTH VELTIS_NATIVE_EXPORTS VELTIS_NATIVE_EXPORT_COUNT)
string(REPLACE ";" "\", \"" VELTIS_NATIVE_EXPORTS_JSON "${VELTIS_NATIVE_EXPORTS}")
set(VELTIS_NATIVE_EXPORTS_JSON "\"${VELTIS_NATIVE_EXPORTS_JSON}\"")

function(veltis_native_normalize_arch out value)
    string(TOLOWER "${value}" _value)
    if(_value MATCHES "^(x86_64|amd64|x64)$")
        set(_result "x86_64")
    elseif(_value MATCHES "^(aarch64|arm64)$")
        set(_result "aarch64")
    elseif(_value MATCHES "^(riscv64|riscv)$")
        set(_result "riscv64")
    else()
        set(_result "${_value}")
    endif()
    set(${out} "${_result}" PARENT_SCOPE)
endfunction()

if(CMAKE_SYSTEM_NAME STREQUAL "Windows")
    set(VELTIS_NATIVE_OS_NAME "windows")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    set(VELTIS_NATIVE_OS_NAME "linux")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Darwin")
    set(VELTIS_NATIVE_OS_NAME "macos")
elseif(CMAKE_SYSTEM_NAME STREQUAL "FreeBSD")
    set(VELTIS_NATIVE_OS_NAME "freebsd")
else()
    string(TOLOWER "${CMAKE_SYSTEM_NAME}" VELTIS_NATIVE_OS_NAME)
endif()

veltis_native_normalize_arch(VELTIS_NATIVE_ARCHITECTURE "${CMAKE_SYSTEM_PROCESSOR}")

# Gradle supplies the platform key it uses for output paths and the loader; a
# manual CMake build derives the same spelling from CMake's system variables.
if(NOT VELTIS_NATIVE_PLATFORM_KEY)
    set(VELTIS_NATIVE_PLATFORM_KEY "${VELTIS_NATIVE_OS_NAME}-${VELTIS_NATIVE_ARCHITECTURE}")
endif()
set(VELTIS_NATIVE_PLATFORM_KEY "${VELTIS_NATIVE_PLATFORM_KEY}" CACHE STRING
    "Loader platform key recorded in the artifact manifest")

# The triple is best-effort: it is exact for prefixed cross compilers and for
# CMake's own CMAKE_*_COMPILER_TARGET, and a documented convention otherwise.
set(VELTIS_NATIVE_TARGET_TRIPLE "")
if(CMAKE_C_COMPILER_TARGET)
    set(VELTIS_NATIVE_TARGET_TRIPLE "${CMAKE_C_COMPILER_TARGET}")
else()
    get_filename_component(_veltis_cc_name "${CMAKE_C_COMPILER}" NAME_WE)
    if(_veltis_cc_name MATCHES "^([a-z0-9_]+)-([a-z0-9_]+)-([a-z0-9_]+)-(clang|gcc|cc)$")
        set(VELTIS_NATIVE_TARGET_TRIPLE "${CMAKE_MATCH_1}-${CMAKE_MATCH_2}-${CMAKE_MATCH_3}")
    endif()
endif()
if(NOT VELTIS_NATIVE_TARGET_TRIPLE)
    if(MINGW)
        if(VELTIS_NATIVE_ARCHITECTURE STREQUAL "aarch64")
            set(VELTIS_NATIVE_TARGET_TRIPLE "aarch64-w64-mingw32")
        else()
            set(VELTIS_NATIVE_TARGET_TRIPLE "x86_64-w64-mingw32")
        endif()
    elseif(MSVC)
        set(VELTIS_NATIVE_TARGET_TRIPLE "${VELTIS_NATIVE_ARCHITECTURE}-pc-windows-msvc")
    elseif(APPLE)
        set(VELTIS_NATIVE_TARGET_TRIPLE "${VELTIS_NATIVE_ARCHITECTURE}-apple-darwin")
    elseif(VELTIS_NATIVE_OS_NAME STREQUAL "linux")
        set(VELTIS_NATIVE_TARGET_TRIPLE "${VELTIS_NATIVE_ARCHITECTURE}-unknown-linux-gnu")
    else()
        set(VELTIS_NATIVE_TARGET_TRIPLE "${VELTIS_NATIVE_ARCHITECTURE}-unknown-${VELTIS_NATIVE_OS_NAME}")
    endif()
endif()

if(VELTIS_NATIVE_STATIC_RUNTIME)
    set(VELTIS_NATIVE_STATIC_RUNTIME_JSON "true")
else()
    set(VELTIS_NATIVE_STATIC_RUNTIME_JSON "false")
endif()

if(VELTIS_NATIVE_WERROR)
    set(VELTIS_NATIVE_WERROR_JSON "true")
else()
    set(VELTIS_NATIVE_WERROR_JSON "false")
endif()

# The bare names, not the compiler flags: the manifest is read by a human and by
# the release verifier, and "address" is the thing that was asked for.
set(VELTIS_NATIVE_SANITIZERS_JSON "")
if(VELTIS_NATIVE_SANITIZER_COUNT GREATER 0)
    list(TRANSFORM VELTIS_NATIVE_SANITIZER_LIST PREPEND "\"" OUTPUT_VARIABLE
        VELTIS_NATIVE_SANITIZERS_QUOTED)
    list(TRANSFORM VELTIS_NATIVE_SANITIZERS_QUOTED APPEND "\""
        OUTPUT_VARIABLE VELTIS_NATIVE_SANITIZERS_QUOTED)
    list(JOIN VELTIS_NATIVE_SANITIZERS_QUOTED ", " VELTIS_NATIVE_SANITIZERS_JSON)
endif()

# An empty value is the honest answer where the minimum runtime is not known at
# configure time (Linux glibc, Windows). CI records the observed requirement.
set(VELTIS_NATIVE_MINIMUM_OS "${CMAKE_OSX_DEPLOYMENT_TARGET}")

# ---------------------------------------------------------------------------
# Build metadata, delivered as a build byproduct
#
# The values are configure-time facts — ABI, version and export list come out of
# the public header, the compiler identity out of the cache — so `configure_file`
# renders the JSON into the build tree, which is where a configure side effect
# belongs. What must not happen at configure time is writing into
# VELTIS_NATIVE_OUTPUT_DIR: that directory is owned by the *build*. An
# incremental build is allowed to prune anything in it, and a metadata file only
# a `cmake -S` can put back disappears while the library beside it survives.
# Packaging then either fails or ships a manifest nobody checked — the failure
# this replaced, recorded as R2 in docs/FOUNDATION_AUDIT.md.
#
# A custom target with no OUTPUT is re-run by every generator on every build, so
# refreshing the copy costs one stat. It is ordered before the library because
# the JSON describes this build configuration, not the binary's bytes.
# ---------------------------------------------------------------------------
set(VELTIS_NATIVE_BUILD_INFO_GENERATED "${CMAKE_BINARY_DIR}/veltis-native-build-info.json")
configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/cmake/veltis-native-build-info.json.in"
    "${VELTIS_NATIVE_BUILD_INFO_GENERATED}"
    @ONLY)

file(MAKE_DIRECTORY "${VELTIS_NATIVE_OUTPUT_DIR}")
add_custom_target(veltis_native_build_info
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        "${VELTIS_NATIVE_BUILD_INFO_GENERATED}"
        "${VELTIS_NATIVE_OUTPUT_DIR}/veltis-native-build-info.json"
    COMMENT "VeltisNative: refreshing veltis-native-build-info.json beside the library")
add_dependencies(veltis_native veltis_native_build_info)
message(STATUS
    "VeltisNative: platform=${VELTIS_NATIVE_PLATFORM_KEY} abi=${VELTIS_NATIVE_ABI_VERSION} "
    "version=${VELTIS_NATIVE_VERSION_STRING} exports=${VELTIS_NATIVE_EXPORT_COUNT}")

if(VELTIS_NATIVE_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()
