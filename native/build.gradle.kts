/*
 * VeltisMC native engine.
 *
 * The Java side of this module is ordinary code: it is compiled by every
 * `build`, it is a dependency of :server, and it contains no mandatory native
 * reference. The C/C++ side is deliberately *not* part of the ordinary build
 * graph. CMake is invoked only by the `native` tasks below, so a checkout with
 * no compiler still builds and tests every Java module exactly as before.
 *
 * The three task groups:
 *
 *   configureNative  runs CMake's configure step into build/native/cmake/<platform>
 *   buildNative      compiles and links the shared library into build/native/<platform>
 *   testNative       runs the C/C++ test executable through CTest
 *   cleanNative      deletes this module's native build tree
 *
 * The release-side tasks do not change what is built; they check what was built:
 *
 *   packageNative/packageNativeArchive  manifest, SHA-256s, reproducible zip
 *   verifyNativeArtifact                every manifest field vs the actual binary
 *   packageNativeSourceArchive          a zip of tracked files only
 *   verifyNativeSourceArchive           extracts it and builds that copy from scratch
 *   verifyNativeRelease                 all of the above, plus CTest and the Java suite
 *
 * Use `-PveltisNativeTest=true` to make the Java test task build the native
 * library first and to run CTest as part of `check`. Without that flag the
 * real-integration test is skipped when no library has been built, which is the
 * only way an ordinary Java-only build can avoid requiring CMake.
 *
 * Cross-compilation is selected with:
 *
 *   -PveltisNativePlatform=windows-aarch64    output/installation key
 *   -PveltisNativeToolchainFile=cmake/toolchains/windows-arm64-mingw.cmake
 *   -PveltisNativeGenerator=Ninja             build tool (Windows defaults to Ninja
 *                                             when it is on PATH; see effectiveNativeGenerator)
 *   -PveltisNativeGeneratorPlatform=ARM64      CMake generator target platform
 *   -PveltisNativeOsxArchitectures=arm64      macOS multi-arch selection
 *   -PveltisNativeOsxDeploymentTarget=11.0    macOS minimum version (default: 11.0)
 *   -PveltisNativeStaticRuntime=true|false    override the per-toolchain default
 *
 * A cross build compiles and produces the library but never runs CTest, the Java
 * FFM integration tests or the benchmark: those require a host that can execute
 * the target binary.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipFile

plugins {
    id("java-library")
}

description = "VeltisMC native engine: C/C++ implementation and Java FFM integration"

// ---------------------------------------------------------------------------
// Host platform naming
//
// The same spelling is used by the build output directory, the Java loader and
// the installation instructions, so there is one string an operator can match
// rather than three that have to be kept in sync.
// ---------------------------------------------------------------------------
fun hostPlatformKey(): String {
        val osName = System.getProperty("os.name", "unknown").lowercase()
    val archName = System.getProperty("os.arch", "unknown").lowercase()
    val os = when {
        osName.contains("win") -> "windows"
        osName.contains("mac") || osName.contains("darwin") -> "macos"
        osName.contains("linux") -> "linux"
        else -> osName.replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "unknown" }
    }
    val arch = when (archName) {
        "x86_64", "amd64", "x64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> archName.replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "unknown" }
    }
    return "$os-$arch"
}

val nativePlatform = providers.gradleProperty("veltisNativePlatform").orElse(hostPlatformKey()).get()
val nativeBuildType = providers.gradleProperty("veltisNativeBuildType").orElse("Release")
// Kept as a String provider so an unset property is the empty string rather than
// a null File: Provider<File> has no no-value representation here, and the
// configure task needs a stable input value regardless of the host.
val nativeToolchainFile = providers.gradleProperty("veltisNativeToolchainFile")
    .orElse("")
    .map { it.trim() }
val nativeToolchainPath = nativeToolchainFile.map { if (it.isEmpty()) "" else file(it).absolutePath }
val nativeOsxArchitectures = providers.gradleProperty("veltisNativeOsxArchitectures").orElse("")
// Empty means "let CMake decide", which is the documented 11.0 floor; naming it
// here is how CI pins a target deliberately instead of inheriting a runner's SDK.
val nativeOsxDeploymentTarget = providers.gradleProperty("veltisNativeOsxDeploymentTarget").orElse("")
/**
 * The value handed to `VELTIS_NATIVE_STATIC_RUNTIME`, normalised to ON or OFF.
 *
 * Empty means "use the per-toolchain default". The other spellings are CMake's
 * own, because that is the parser this value ultimately meets: Kotlin's
 * `String.toBoolean()` read `ON` as false, which would have configured a dynamic
 * link on a lane that asked for a static one and then reported the artifact it
 * actually built. An unrecognised value fails rather than guessing.
 */
val nativeStaticRuntime = providers.gradleProperty("veltisNativeStaticRuntime").orElse("").map { raw ->
    when (val value = raw.trim().lowercase()) {
        "" -> ""
        "on", "true", "1", "y", "yes" -> "ON"
        "off", "false", "0", "n", "no", "ignore", "notfound" -> "OFF"
        else -> throw GradleException(
            "-PveltisNativeStaticRuntime='$raw' is not a boolean. Use true/false (or any spelling " +
                "CMake accepts) — a wrong value here silently changes which runtime the artifact links."
        )
    }
}
val nativeHostPlatform = hostPlatformKey()

/**
 * The `-fsanitize=` list for a developer lane, normalised to CMake's spelling.
 *
 * Empty means "no sanitizer", which is every release and CI build. CMake is the
 * authority on whether a name is valid *and* linkable; a request it cannot
 * satisfy fails at configure time with the toolchain named, because a build that
 * quietly ignored the flag would later be quoted as sanitizer evidence.
 */
val nativeSanitizers = providers.gradleProperty("veltisNativeSanitizers").orElse("").map { raw ->
    raw.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
}

/**
 * `-DVELTIS_NATIVE_WERROR`, normalised the same way as the runtime switch.
 *
 * Unlike the runtime switch this one always has a value, because CMake keeps an
 * option in its cache: a lane that asked for warnings-as-errors and a later lane
 * that did not would otherwise share a directory and the second would silently
 * inherit the first. Passing `OFF` explicitly is what makes the property describe
 * the build it configures.
 */
val nativeWerror = providers.gradleProperty("veltisNativeWerror").orElse("false").map { raw ->
    when (val value = raw.trim().lowercase()) {
        "on", "true", "1", "y", "yes" -> "ON"
        "off", "false", "0", "n", "no", "ignore", "notfound" -> "OFF"
        else -> throw GradleException(
            "-PveltisNativeWerror='$raw' is not a boolean. Use true or false — a value this " +
                "build could not read would decide by accident whether a warning fails the lane."
        )
    }
}

/**
 * A sanitizer build is kept in its own tree.
 *
 * An instrumented library has to be impossible to mistake for the artifact a
 * release check reads: sharing `build/native/<platform>` would let an ASan build
 * overwrite the DLL that the runtime-policy and checksum verification steps
 * inspect, and the CMake cache would keep fighting over flags between lanes. The
 * suffix is part of the directory name, so both trees and both caches exist at
 * once and no lane can be silently re-used by another.
 */
val nativeLaneSuffix = nativeSanitizers.map { if (it.isEmpty()) "" else "-san-" + it.replace(',', '-') }

/**
 * A build is cross-compiled when it targets a platform key other than the one
 * this JVM runs on, or when an explicit CMake toolchain file is supplied. Cross
 * artifacts are compiled and inspected, never executed: CTest and the Java
 * integration tests are host-only, so running them against a foreign binary
 * would report a false failure on a perfectly good build.
 */
val nativeCrossCompiling = nativePlatform != nativeHostPlatform || nativeToolchainFile.orNull?.isNotEmpty() == true

val nativeRoot = layout.buildDirectory.dir("native")
val nativeOutputDir = nativeRoot.zip(nativeLaneSuffix) { root, lane -> root.dir(nativePlatform + lane) }
val cmakeBuildDir = nativeRoot.zip(nativeLaneSuffix) { root, lane -> root.dir("cmake/${nativePlatform + lane}") }
val fixtureOutputDir = nativeOutputDir.map { it.dir("test-fixtures") }

fun libraryName(base: String, platform: String): String = when {
    platform.startsWith("windows") -> "$base.dll"
    platform.startsWith("macos") -> "lib$base.dylib"
    else -> "lib$base.so"
}

val nativeLibraryName = libraryName("veltis_native", nativePlatform)
val nativeLibraryFile = nativeOutputDir.map { it.file(nativeLibraryName) }

/**
 * Finds an executable on PATH, trying the platform's usual suffixes.
 *
 * Gradle deliberately does not use any internal operating-system API here: this
 * module has to configure on every host, including one where the tool is absent.
 */
fun findOnPath(name: String): String? {
    val windows = System.getProperty("os.name", "").lowercase().contains("win")
    val suffixes = if (windows) listOf(".exe", ".cmd", ".bat", "") else listOf("")
    val path = System.getenv("PATH").orEmpty()
    for (directory in path.split(File.pathSeparatorChar)) {
        if (directory.isBlank()) continue
        for (suffix in suffixes) {
            val candidate = File(directory, name + suffix)
            if (candidate.isFile) return candidate.absolutePath
        }
    }
    return null
}

/**
 * The generator this module actually asks CMake for; empty means "CMake decides".
 *
 * An explicit `-PveltisNativeGenerator` always wins. Without one, a Windows lane
 * that can see Ninja asks for Ninja, because CMake's own Windows default is
 * NMake (or Visual Studio) and a MinGW or LLVM-MinGW lane has neither. The
 * failure that produced this rule is inside `project()`:
 *
 *     Building for: NMake Makefiles
 *     'nmake' '-?' failed with: no such file or directory
 *
 * which names no compiler and reads like a toolchain bug while the real cause is
 * the build tool CMake chose for itself. Every CI MinGW and cross lane already
 * names Ninja, so this changes only what a manual local run does — from failing
 * confusingly to building. Where Ninja is absent the decision is left to CMake,
 * so an MSVC host keeps its Visual Studio generator.
 */
fun effectiveNativeGenerator(): String {
    val requested = providers.gradleProperty("veltisNativeGenerator").orNull?.trim().orEmpty()
    if (requested.isNotEmpty()) return requested
    return if (nativePlatform.startsWith("windows") && findOnPath("ninja") != null) "Ninja" else ""
}

val nativeGenerator = providers.provider { effectiveNativeGenerator() }
val nativeGeneratorPlatform = providers.gradleProperty("veltisNativeGeneratorPlatform").orElse("")

/**
 * Resolves a native tool at execution time, never at configuration time.
 *
 * Resolution order is an explicit Gradle property, an environment variable, and
 * PATH. The error is actionable because a missing tool is the one failure a
 * native build cannot work around.
 */
fun requireTool(
    name: String,
    propertyName: String,
    environmentName: String,
    installHint: String,
): String {
    providers.gradleProperty(propertyName).orNull?.takeIf { it.isNotBlank() }?.let { requested ->
        val file = File(requested)
        if (file.isFile) return file.absolutePath
        findOnPath(requested)?.let { return it }
        throw GradleException(
            "$propertyName points at '$requested', but it is not an executable file"
        )
    }
    System.getenv(environmentName)?.takeIf { it.isNotBlank() }?.let { requested ->
        val file = File(requested)
        if (file.isFile) return file.absolutePath
        findOnPath(requested)?.let { return it }
        throw GradleException(
            "$environmentName points at '$requested', but it is not an executable file"
        )
    }
    return findOnPath(name) ?: throw GradleException(
        "$name was not found on PATH\n" +
            "  Needed by: :veltis-native native tasks\n" +
            "  Fix: install $installHint, or pass -P$propertyName=/path/to/$name\n" +
            "  Note: ordinary Java builds and :server do not need this tool."
    )
}

/**
 * The three names for one compiler choice: the CMake cache variable it ends up
 * in, the environment variable CMake reads, and the Gradle property that stands
 * in for it when a caller would rather not mutate their shell.
 */
val nativeCompilerKnobs = listOf(
    Triple("CMAKE_C_COMPILER", "CC", "veltisNativeCc"),
    Triple("CMAKE_CXX_COMPILER", "CXX", "veltisNativeCxx"),
)

/**
 * The compilers the caller asked for, keyed by CMake cache variable.
 *
 * A toolchain file is excluded: it sets `CMAKE_C_COMPILER` itself, on every
 * configure, so `CC` in that lane names nothing the build will use, and a shell
 * default would fail the preserved cross-compile lanes for the wrong reason.
 *
 * Read through a function rather than a `val` because the environment is the
 * caller's, and it is consulted when the task's inputs are fingerprinted.
 */
fun nativeCompilerSelection(): Map<String, String> =
    if (nativeToolchainFile.get().isNotBlank()) {
        emptyMap()
    } else {
        buildMap {
            nativeCompilerKnobs.forEach { (cacheVariable, environmentName, propertyName) ->
                val requested = providers.gradleProperty(propertyName).orNull?.takeIf { it.isNotBlank() }
                    ?: System.getenv(environmentName)?.takeIf { it.isNotBlank() }
                if (requested != null) put(cacheVariable, requested.trim())
            }
        }.toSortedMap()
    }

/** The compiler entries of a CMake cache, as the paths CMake recorded. */
fun cachedCompilers(cache: File): Map<String, String> {
    val variables = nativeCompilerKnobs.map { it.first }.toSet()
    // A cache a killed CMake left half-written is not evidence about a compiler,
    // and this build has no business failing over it: CMake re-reads its own cache
    // and reports that problem in its own words.
    val lines = runCatching { cache.readLines() }.getOrNull() ?: return emptyMap()
    return lines.mapNotNull { line ->
        val variable = line.substringBefore(':', "").trim()
        val type = line.substringAfter(':', "").substringBefore('=', "").trim()
        if (variable in variables && type == "FILEPATH") variable to line.substringAfter('=').trim()
        else null
    }.toMap()
}

/**
 * Whether a requested compiler and the one CMake recorded name the same tool.
 *
 * A bare name like `gcc` means "whichever the resolver finds first", which is the
 * same question CMake was asked, so matching the file stem is the honest answer;
 * requiring the absolute path would fail `gcc` against `/usr/bin/gcc` on every
 * Linux lane. A request that names a path is a claim about a specific tool, so
 * the paths have to agree as well — two `gcc.exe` in different directories are
 * not interchangeable. `.exe` is dropped and case ignored, because a bare `CC`
 * value has no suffix while the cache always records the resolved one.
 */
fun sameCompiler(requested: String, recorded: String): Boolean {
    fun normalizedPath(path: String) = path.replace('\\', '/')

    fun stem(path: String) =
        normalizedPath(path).substringAfterLast('/').removeSuffix(".exe").lowercase()

    if (stem(requested) != stem(recorded)) return false
    if (!normalizedPath(requested).contains('/')) return true

    val windows = System.getProperty("os.name", "").lowercase().contains("win")
    val resolvesTo = runCatching {
        File(requested).canonicalPath.equals(File(recorded).canonicalPath, ignoreCase = windows)
    }
    return resolvesTo.getOrDefault(true)
}

/**
 * Fails when the cache holds a different compiler than the one just requested.
 *
 * CMake writes the first compiler it finds into `CMakeCache.txt` and afterwards
 * ignores `CC`, `CXX` and a changed PATH: the reconfigure succeeds and rebuilds
 * every object with the tool the caller did not ask for, and the artifact check
 * then reports that tool as if it had been chosen. An edit to a cache is not
 * something Gradle can see as an input either, so the only honest option is to
 * refuse and say how to clear it.
 */
fun requireCompilerMatchesCache(cache: File, requested: Map<String, String>) {
    if (requested.isEmpty() || !cache.isFile) return
    val recorded = cachedCompilers(cache)
    val conflicts = requested.filter { (variable, value) ->
        val existing = recorded[variable]
        existing != null && existing.isNotBlank() && !sameCompiler(value, existing)
    }
    if (conflicts.isEmpty()) return
    throw GradleException(
        "The CMake cache for $nativePlatform was configured with a different compiler.\n" +
            conflicts.entries.joinToString(separator = "") { (variable, value) ->
                "\n  $variable: cache holds '${recorded[variable]}', this build asks for '$value'"
            } +
            "\n  CMake reuses the cached compiler and ignores the new one, so this build would" +
            " compile with a tool you did not select." +
            "\n  Fix: ./gradlew :veltis-native:cleanNative, then repeat the build." +
            "\n  Cache: ${cache.absolutePath}"
    )
}

val nativeSourceInputs = fileTree(projectDir) {
    include("CMakeLists.txt", "CMakePresets.json")
    include("cmake/**")
    include("include/**")
    include("src/**")
    include("tests/**")
}

/**
 * The subset of the build inputs that a C/C++ rebuild actually consumes.
 *
 * The src glob above also holds this module's Java sources, and an edit there
 * says nothing about whether the library on disk matches the native code.
 * Filtering them out keeps the freshness check below about the artifact it is
 * meant to describe.
 */
val nativeCodeInputs = fileTree(projectDir) {
    include("CMakeLists.txt", "CMakePresets.json")
    include("cmake/**")
    include("include/**")
    include("src/**")
    include("tests/**")
    exclude("src/main/java/**", "src/test/java/**")
}

// ---------------------------------------------------------------------------
// Native tasks
// ---------------------------------------------------------------------------

val configureNative by tasks.registering(Exec::class) {
    group = "native"
    description = "Configures the CMake build for $nativePlatform"
    inputs.files(nativeSourceInputs).withPropertyName("nativeSources")
    inputs.property("platform", nativePlatform)
    inputs.property("buildType", nativeBuildType)
    inputs.property("generator", nativeGenerator)
    inputs.property("generatorPlatform", nativeGeneratorPlatform)
    inputs.property("crossCompiling", nativeCrossCompiling)
    inputs.property("toolchainFile", nativeToolchainPath)
    inputs.property("osxArchitectures", nativeOsxArchitectures)
    inputs.property("osxDeploymentTarget", nativeOsxDeploymentTarget)
    inputs.property("staticRuntime", nativeStaticRuntime)
    inputs.property("sanitizers", nativeSanitizers)
    inputs.property("werror", nativeWerror)
    // The compiler is an input because it is a *choice*, and a choice this build
    // cannot see is a choice it will silently ignore: CMake records the compiler
    // in the cache and a later `CC=` re-run keeps the cached one. Reading it when
    // the inputs are fingerprinted, not in a `val`, is what lets Gradle see the
    // change; `requireCompilerMatchesCache` below turns it into guidance instead
    // of a build compiled with a tool nobody selected.
    inputs.property("compilerSelection", providers.provider { nativeCompilerSelection() })
    // No `outputs` declaration on purpose. Declaring `cmakeBuildDir` hands that
    // tree to Gradle's stale-output cleanup: copying a cache from another
    // platform's tree into it made Gradle delete the file before the task ran,
    // because the same content was already registered as a Gradle output
    // elsewhere. CMake owns this directory. Re-configuring is cheap, CMake no-ops
    // when nothing changed, and the compiler check below stays unconditional.

    doFirst {
        requireCompilerMatchesCache(
            cmakeBuildDir.get().asFile.resolve("CMakeCache.txt"), nativeCompilerSelection()
        )
        val cmake = requireTool(
            name = "cmake",
            propertyName = "veltisNativeCmake",
            environmentName = "CMAKE",
            installHint = "CMake 3.21 or newer"
        )
        val args = mutableListOf(
            cmake,
            "-S", projectDir.absolutePath,
            "-B", cmakeBuildDir.get().asFile.absolutePath,
            "-DCMAKE_BUILD_TYPE=${nativeBuildType.get()}",
            "-DVELTIS_NATIVE_OUTPUT_DIR=${nativeOutputDir.get().asFile.absolutePath}",
            "-DVELTIS_NATIVE_PLATFORM_KEY=${nativePlatform}",
            "-DVELTIS_NATIVE_BUILD_TESTS=${if (nativeCrossCompiling) "OFF" else "ON"}",
        )
        nativeGenerator.get().takeIf { it.isNotBlank() }?.let { generator ->
            args += listOf("-G", generator)
        }
        nativeGeneratorPlatform.orNull?.takeIf { it.isNotBlank() }?.let { platform ->
            args += listOf("-A", platform)
        }
        nativeToolchainPath.orNull?.takeIf { it.isNotEmpty() }?.let { toolchain ->
            args += "-DCMAKE_TOOLCHAIN_FILE=$toolchain"
        }
        nativeOsxArchitectures.orNull?.takeIf { it.isNotBlank() }?.let { architectures ->
            args += "-DCMAKE_OSX_ARCHITECTURES=$architectures"
        }
        nativeOsxDeploymentTarget.orNull?.takeIf { it.isNotBlank() }?.let { target ->
            args += "-DCMAKE_OSX_DEPLOYMENT_TARGET=$target"
        }
        nativeStaticRuntime.orNull?.takeIf { it.isNotEmpty() }?.let { value ->
            args += "-DVELTIS_NATIVE_STATIC_RUNTIME=$value"
        }
        nativeSanitizers.orNull?.takeIf { it.isNotEmpty() }?.let { list ->
            args += "-DVELTIS_NATIVE_SANITIZERS=$list"
        }
        args += "-DVELTIS_NATIVE_WERROR=${nativeWerror.get()}"
        // The Gradle properties reach CMake through the channel CMake already
        // reads. A request that came from the environment is rewritten with its
        // own value, so the only new behaviour is `-PveltisNativeCc=…`.
        nativeCompilerKnobs.forEach { (_, environmentName, propertyName) ->
            providers.gradleProperty(propertyName).orNull?.takeIf { it.isNotBlank() }
                ?.let { environment(environmentName, it.trim()) }
        }
        commandLine(args)
    }
}

val buildNative by tasks.registering(Exec::class) {
    group = "native"
    description = "Compiles and links the C/C++ shared library for $nativePlatform"
    dependsOn(configureNative)
    inputs.files(nativeSourceInputs).withPropertyName("nativeSources")
    inputs.property("platform", nativePlatform)
    inputs.property("buildType", nativeBuildType)
    // The cache is the fingerprint of the compile and link configuration CMake
    // actually generated. Gradle cannot see a compiler swap, a toolchain file or
    // `-PveltisNativeStaticRuntime=false` through the properties declared above,
    // so without this input it reports the previous library UP-TO-DATE: the link
    // step never re-runs and every later check measures bytes built from a
    // different configuration than the one being reported.
    inputs.file(cmakeBuildDir.map { it.file("CMakeCache.txt") }).withPropertyName("cmakeCache")
    outputs.dir(nativeOutputDir).withPropertyName("nativeOutput")

    doFirst {
        val cmake = requireTool(
            name = "cmake",
            propertyName = "veltisNativeCmake",
            environmentName = "CMAKE",
            installHint = "CMake 3.21 or newer"
        )
        commandLine(
            cmake,
            "--build", cmakeBuildDir.get().asFile.absolutePath,
            "--config", nativeBuildType.get(),
            "--parallel",
        )
    }
}

val testNative by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the C/C++ native engine tests with CTest"
    dependsOn(buildNative)
    outputs.upToDateWhen { false }

    doFirst {
        if (nativeCrossCompiling) {
            throw GradleException(
                "testNative cannot run a cross-compiled build: " + nativePlatform +
                    " artifacts were built on " + nativeHostPlatform + "\n" +
                    "  Use `buildNative` to compile and inspect them, and run the tests on a native " +
                    nativePlatform + " runner (see .github/workflows/gradle.yml)."
            )
        }
        val ctest = requireTool(
            name = "ctest",
            propertyName = "veltisNativeCtest",
            environmentName = "CTEST",
            installHint = "CMake 3.21 or newer (CTest ships with it)"
        )
        commandLine(
            ctest,
            "--test-dir", cmakeBuildDir.get().asFile.absolutePath,
            "-C", nativeBuildType.get(),
            "--output-on-failure",
        )
    }
}

/**
 * Cppcheck over the C/C++ sources — the static-analysis lane that needs no
 * compile database and therefore runs on a tree that has never been configured.
 *
 * It is a separate task rather than part of `build` because it is a *review*
 * tool: its findings are judgement calls about code a compiler accepts, and a
 * third-party checker's opinion must not decide whether a release artifact
 * exists. Invoking it explicitly is a request for an answer, so a missing
 * cppcheck fails instead of skipping — a skipped check reported as a clean one
 * is the failure mode this module already refuses for sanitizers.
 */
val analyzeNativeStatic by tasks.registering {
    group = "verification"
    description = "Runs Cppcheck and the GCC/Clang analyzer over the native sources"
    outputs.upToDateWhen { false }

    doLast {
        val cppcheck = findOnPath("cppcheck")
        if (cppcheck == null) {
            throw GradleException(
                "analyzeNativeStatic needs `cppcheck` on PATH (or -PveltisNativeCppcheck=<path>). " +
                    "A static-analysis lane that silently does not run is worse than one that is " +
                    "absent, because its absence would be read as a clean result."
            )
        }
        val sources = listOf(
            file("src").absolutePath,
            file("tests").absolutePath,
        )
        // --error-exitcode makes a finding a build failure; the severity set is
        // the one that has produced a real defect here (a null dereference on a
        // path the compiler was free to assume impossible). Style and unusedFunction
        // are excluded: the first is taste, the second misreports a function reached
        // only through a translation unit Cppcheck parses separately.
        val args = listOf(
            cppcheck,
            "--error-exitcode=2",
            "--enable=warning,performance,portability",
            "--suppress=unusedFunction",
            "--suppress=missingIncludeSystem",
            "--inline-suppr",
            "--language=c++",
            "--std=c++20",
            "--quiet",
        ) + sources
        // `providers.exec` cannot be used here: it captures output into a buffer
        // nobody prints and reports only a bare exit code, so a cppcheck finding
        // would arrive as "exit 2" with no file and no line. The child is run
        // directly so stdout and stderr are merged, printed and inspectable —
        // the same reason verifyNativeSourceArchive does it this way.
        fun runChecker(command: List<String>): Pair<Int, String> {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            return process.waitFor() to output
        }

        val (cppcheckExit, cppcheckOutput) = runChecker(args)
        cppcheckOutput.lineSequence().filter { it.isNotBlank() }.forEach { logger.lifecycle(it) }
        if (cppcheckExit != 0) {
            throw GradleException(
                "Cppcheck reported findings (exit $cppcheckExit). Its output is above; " +
                    "each one is a decision to fix or to suppress inline with a reason."
            )
        }
        logger.lifecycle("Cppcheck: no findings in ${sources.joinToString(", ")}")

        // -fanalyzer is GCC's own deep analyzer and understands this module's C
        // translation unit (the thread-local error slot). It is skipped, not
        // failed, when the compiler is not GCC, because Clang has its own path
        // documented in docs/TESTING_STRATEGY.md and a second compiler's flag is
        // not this tool's business.
        val gcc = findOnPath("gcc")
        if (gcc != null) {
            val (analysisExit, analysisOutput) = runChecker(
                listOf(
                    gcc, "-fanalyzer", "-c",
                    file("src/c/native_utils.c").absolutePath,
                    "-I", file("include").absolutePath,
                    "-o", File(layout.buildDirectory.asFile.get(), "native-utils-analyzer.o").absolutePath,
                )
            )
            analysisOutput.lineSequence().filter { it.isNotBlank() }.forEach { logger.lifecycle(it) }
            if (analysisExit != 0) {
                throw GradleException("gcc -fanalyzer failed on src/c/native_utils.c (exit $analysisExit)")
            }
            logger.lifecycle("gcc -fanalyzer: src/c/native_utils.c is clean")
        } else {
            logger.lifecycle("gcc not on PATH; the -fanalyzer half of this check did not run")
        }
    }
}

val cleanNative by tasks.registering(Delete::class) {    group = "native"
    description = "Deletes this module's CMake build tree and native artifacts"
    delete(nativeRoot)
}

// `clean` already removes build/, but an explicit native clean is useful while
// a compiler invocation is being debugged and is what the README documents.
tasks.named("clean") {
    dependsOn(cleanNative)
}

// ---------------------------------------------------------------------------
// Java integration tests
//
// The paths are passed whenever the library on disk is current. A test uses
// Assumptions when the file is absent, so `:veltis-native:test` works on a
// machine with no native toolchain and starts verifying the real library as soon
// as `buildNative` has produced one. `-PveltisNativeTest=true` makes that
// ordering explicit instead of relying on the operator having run buildNative
// first; `nativeFixtureIsCurrent` below is what stops a library left over from
// an older source tree from being verified green.
// ---------------------------------------------------------------------------
fun fixtureLibrary(base: String): String = libraryName(base, nativePlatform)

/**
 * Points a test JVM at a fixture only when the fixture is a host binary.
 *
 * <p>An explicit {@code -D} always wins — including an empty value, which is how
 * CI asks for the Java-only suite by disabling every fixture. Otherwise a cross
 * build deliberately leaves every property unset: the tests use {@code
 * Assumptions} to skip, so the source tree's Java suite still runs on the build
 * machine.
 */
val javaOnlyRequested =
    providers.systemProperty("veltis.native.test.expectJavaOnly").orNull?.toBoolean() == true

/**
 * Newest modification among the native sources.
 *
 * A function, not a cached value: the only caller is the freshness verdict below,
 * and that verdict has to be read when the task's inputs are fingerprinted, after
 * any native build in the same invocation has had a chance to relink.
 */
fun newestNativeSourceTime(): Long =
    nativeCodeInputs.files.maxOfOrNull { it.lastModified() } ?: 0L

fun fixtureFile(base: String): File =
    fixtureOutputDir.get().file(fixtureLibrary(base)).asFile

/**
 * Every native fixture the Java suite can be pointed at, as property and file.
 *
 * One list rather than seven hand-written calls: the pairing is what keeps the
 * property names, the fixture names and the input declaration below from
 * drifting apart.
 */
val nativeTestFixtures: List<Pair<String, File>> = listOf(
    "veltis.native.test.library" to nativeLibraryFile.get().asFile,
    "veltis.native.test.fakeAbi" to fixtureFile("veltis_native_fake_abi"),
    "veltis.native.test.fakeVersion" to fixtureFile("veltis_native_fake_version"),
    "veltis.native.test.fakeInit" to fixtureFile("veltis_native_fake_init"),
    "veltis.native.test.fakeTransform" to fixtureFile("veltis_native_fake_transform"),
    "veltis.native.test.fakeMissing" to fixtureFile("veltis_native_fake_missing"),
    "veltis.native.test.fakeMinimal" to fixtureFile("veltis_native_fake_minimal")
)

/**
 * Whether the library on disk was linked from the native sources in this tree.
 *
 * This exists for the case a file input cannot express: `:veltis-native:test` is
 * *meant* to run with no toolchain and no native build at all, and it happily
 * wires itself to any library it finds. If that library predates an edit to
 * `algorithms.cpp`, Gradle sees a perfectly unchanged input and reports green —
 * about a binary this source tree did not produce. Earlier sessions on this host
 * left exactly such libraries behind (R5 in `docs/FOUNDATION_AUDIT.md`), and the
 * stale bytes were then quoted as evidence about the current ones.
 *
 * The comparison is last-modified, not content, on purpose. It errs in the only
 * direction that matters: a difference this build cannot explain means no
 * fixture is offered, so the suite runs Java-only and the native cases skip with
 * a warning naming the library. Failing the build would be wrong twice over —
 * the same tree must stay testable without native tooling, and a `git checkout`
 * that only refreshed mtimes would otherwise break `build`.
 *
 * Only the real library is judged. The fakes belong to the same CMake tree but
 * their own sources decide when CMake relinks them, so a fake's timestamp says
 * nothing about this tree; once the library under test is stale the native
 * premise is gone either way, which is why every fixture is withheld.
 */
fun nativeLibraryIsStale(library: File, newestSourceTime: Long): Boolean =
    library.isFile && library.lastModified() < newestSourceTime

fun configureFixturePath(target: Test, property: String, defaultFile: File) {
    val explicit = providers.systemProperty(property)
    when {
        explicit.isPresent -> {
            val value = explicit.get()
            if (value.isNotBlank()) {
                target.systemProperty(property, value)
            }
        }
        // The Java-only mode is a claim about the absence of a fixture, so it
        // wins over "a library happens to exist in this working tree".
        javaOnlyRequested -> Unit
        !nativeCrossCompiling -> target.systemProperty(property, defaultFile.absolutePath)
    }
}

tasks.withType<Test>().configureEach {
    // The integration tests call Linker.nativeLinker()/SymbolLookup. On JDK 24+
    // that is a restricted operation: without this the JVM prints a warning for
    // every test JVM. The launcher already passes the same flag to the server.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Evidence that a Java-only run really had no fixture: the test fails when a
    // fixture property is set while this flag asks for the fallback-only suite.
    providers.systemProperty("veltis.native.test.expectJavaOnly").orNull?.let { value ->
        systemProperty("veltis.native.test.expectJavaOnly", value)
    }
    // A sanitizer lane loads instrumented code into a JVM that is not
    // instrumented. LeakSanitizer would then report the JVM's own allocations and
    // every run would be red for a reason this module cannot fix, so leak
    // detection is turned off here and stays owned by the CTest lane, where the
    // process under test is only the library. `halt_on_error` is the point of the
    // exercise: a sanitizer that carries on produces a log nobody reads.
    if (nativeSanitizers.orNull?.isNotEmpty() == true) {
        val defaults = mapOf(
            "ASAN_OPTIONS" to "detect_leaks=0:halt_on_error=1:abort_on_error=1",
            "UBSAN_OPTIONS" to "print_stacktrace=1:halt_on_error=1",
            "TSAN_OPTIONS" to "halt_on_error=1:second_deadlock_stack=1",
        )
        defaults.forEach { (name, value) ->
            if (environment[name] == null) environment(name, value)
        }
    }
    nativeTestFixtures.forEach { (property, file) ->
        configureFixturePath(this, property, file)
    }

    // The freshness decision is taken when the task's inputs are fingerprinted,
    // which is after any native build in the same invocation has relinked, and
    // not while the tasks are configured. The ordering is the reason:
    // `-PveltisNativeTest=true` makes this task depend on `buildNative`, so a
    // configuration-time check saw the *previous* library, withheld every
    // fixture from a suite that was about to verify a freshly linked one, and
    // `verifyNativeRelease` then reported native cases that had no chance to run.
    //
    // The verdict is an input as well as an action. This build is reproducible,
    // so a relink after a no-op edit can leave the library byte-identical and the
    // file hashes unchanged; without the property this task would stay UP-TO-DATE
    // holding a result recorded under a different verdict.
    val suite = this
    val fixtureProperties = nativeTestFixtures.map { it.first }
    val libraryUnderTest = nativeLibraryFile.get().asFile
    val nativeFixturesAreFresh = providers.provider {
        !nativeLibraryIsStale(libraryUnderTest, newestNativeSourceTime())
    }

    // Blank is what the tests read as "not configured", and the warning is task
    // output, so a suite that skipped its native cases says so where it is read —
    // unlike a configuration-phase log on this host, which never reaches the
    // console at all.
    doFirst {
        if (!javaOnlyRequested && !nativeFixturesAreFresh.get()) {
            fixtureProperties.forEach { property -> suite.systemProperty(property, "") }
            logger.warn(
                "veltis-native: ${libraryUnderTest.absolutePath} predates the native sources in " +
                    "this tree, so no native fixture is offered to this suite and its native cases " +
                    "skip. This run says nothing about the current C/C++ code. Rebuild with " +
                    "./gradlew :veltis-native:test -PveltisNativeTest=true."
            )
        }
    }

    // The fixture paths reach the test JVM as system-property *strings*:
    // Gradle tracks the string, not the bytes it points at. Declaring the files
    // as inputs is what makes a rebuilt DLL invalidate the Java suite instead
    // of leaving it UP-TO-DATE against a stale library. Files are resolved at
    // execution time so a checkout with no library yet is still valid input.
    //
    // The native sources are inputs too. Without them an edit to a .cpp leaves
    // this task UP-TO-DATE and the suite never runs, so the previous result —
    // measured against a library that no longer matches this tree — keeps being
    // reported. With them the task re-runs, the guard above withholds the
    // fixtures and the skip is stated out loud.
    if (!javaOnlyRequested) {
        val fixtureFiles = provider {
            buildList {
                add(nativeLibraryFile.get().asFile)
                fixtureOutputDir.get().asFile.listFiles()?.forEach { add(it) }
            }
        }
        inputs.files(fixtureFiles, nativeCodeInputs).withPropertyName("nativeTestFixtures")
        inputs.property("nativeFixturesAreFresh", nativeFixturesAreFresh)
    }

    if (providers.gradleProperty("veltisNativeTest").orNull?.toBoolean() == true) {
        dependsOn(buildNative)
    }
}

if (providers.gradleProperty("veltisNativeTest").orNull?.toBoolean() == true && !nativeCrossCompiling) {
    tasks.named("check") {
        dependsOn(testNative)
    }
}

// ---------------------------------------------------------------------------
// Benchmark
//
// Not attached to `build`: it takes seconds and its numbers belong to the host
// it ran on. It intentionally builds the native library first, because measuring
// a native benchmark without the native library is just a Java microbenchmark.
// ---------------------------------------------------------------------------
tasks.register<JavaExec>("benchmarkNative") {
    group = "verification"
    description = "Benchmarks the Java fallback, an allocation-free Java baseline and the native engine."
    dependsOn(buildNative, tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "org.veltismc.nativeengine.NativeBenchmark"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    args(nativeLibraryFile.get().asFile.absolutePath)
    doFirst {
        if (nativeCrossCompiling) {
            throw GradleException(
                "benchmarkNative needs a library this JVM can load, but " + nativePlatform +
                    " was cross-compiled on " + nativeHostPlatform
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Artifact packaging and release verification
//
// A packaged artifact is self-describing: the library, a manifest that records
// the ABI/version/compiler/runtime, and a SHA-256 checksum. Nothing from the
// toolchain or the operating system is bundled. The archive is reproducible:
// entries are sorted and timestamps are fixed, so the same inputs produce the
// same bytes.
// ---------------------------------------------------------------------------
val nativeBuildInfoFile = nativeOutputDir.map { it.file("veltis-native-build-info.json") }
val nativeDistRoot = layout.buildDirectory.dir("native/dist")
val nativeDistDir = nativeDistRoot.map { it.dir(nativePlatform) }

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun gitCommit(): String = try {
    providers.exec { commandLine("git", "rev-parse", "HEAD") }
        .standardOutput.asText.get().trim()
} catch (_: Exception) {
    "unknown"
}

/** A release must come from a clean checkout; record the truth, never assume. */
fun gitTreeState(): String = try {
    val out = providers.exec { commandLine("git", "status", "--porcelain") }
        .standardOutput.asText.get()
    if (out.isBlank()) "clean" else "dirty"
} catch (_: Exception) {
    "unknown"
}

val packageNative by tasks.registering {
    group = "native"
    description = "Assembles the library, manifest and SHA-256 checksum for $nativePlatform"
    dependsOn(buildNative)
    inputs.dir(nativeOutputDir)
    inputs.property("platform", nativePlatform)
    inputs.property("buildType", nativeBuildType.get())
    outputs.dir(nativeDistDir)

    doLast {
        val library = nativeLibraryFile.get().asFile
        if (!library.isFile) {
            throw GradleException("native library was not produced: ${library.absolutePath}")
        }
        val infoFile = nativeBuildInfoFile.get().asFile
        if (!infoFile.isFile) {
            throw GradleException(
                "native build metadata was not produced: ${infoFile.absolutePath}\n" +
                    "  Re-run :veltis-native:configureNative (or delete the CMake cache)."
            )
        }

        val destination = nativeDistDir.get().asFile
        if (destination.exists()) destination.deleteRecursively()
        destination.mkdirs()

        val checksum = sha256(library)
        @Suppress("UNCHECKED_CAST")
        val buildInfo = JsonSlurper().parse(infoFile) as Map<String, Any?>

        val manifest = LinkedHashMap<String, Any?>()
        manifest["schemaVersion"] = 1
        manifest["library"] = library.name
        manifest["sha256"] = checksum
        manifest["sizeBytes"] = library.length()
        manifest.putAll(buildInfo)
        // These describe this invocation, never a stale configure step.
        manifest["platform"] = nativePlatform
        manifest["buildType"] = nativeBuildType.get()
        // CMake cannot know whether the checkout it was handed was pristine, so
        // the provenance is recorded here, at packaging time, from git itself.
        manifest["sourceCommit"] = gitCommit()
        manifest["sourceTreeState"] = gitTreeState()

        library.copyTo(File(destination, library.name), overwrite = true)
        val manifestFile = File(destination, "veltis-native-manifest.json")
        manifestFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n")
        // The manifest carries the library's checksum; covering the manifest too
        // means an edited manifest is detectable before it is trusted.
        File(destination, "SHA256SUMS").writeText(
            "$checksum  ${library.name}\n" +
                "${sha256(manifestFile)}  veltis-native-manifest.json\n"
        )

        logger.lifecycle(
            "Packaged ${library.name} (${library.length()} bytes, sha256 $checksum) into " +
                destination.absolutePath
        )
    }
}

val packageNativeArchive by tasks.registering(Zip::class) {
    group = "native"
    description = "Creates a reproducible native distribution archive for $nativePlatform"
    dependsOn(packageNative)
    archiveFileName.set("veltis-native-${nativePlatform}-${nativeBuildType.get().lowercase()}.zip")
    destinationDirectory.set(nativeDistRoot)
    from(nativeDistDir)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    isZip64 = false
    outputs.upToDateWhen { false }

    doLast {
        val archive = archiveFile.get().asFile
        val checksum = sha256(archive)
        File(archive.parentFile, "${archive.name}.sha256")
            .writeText("$checksum  ${archive.name}\n")
        logger.lifecycle("Native archive: ${archive.absolutePath} (sha256 $checksum)")
    }
}

// ---------------------------------------------------------------------------
// Source archive
//
// A release has to be reproducible from source alone, so the archive is built
// from `git ls-files` rather than from a hand-written list of patterns: the
// index is exactly what another person receives on a clean checkout, which
// makes CMake caches, object files, locally built libraries and IDE state
// absent by construction instead of by a rule that can drift. Extraction is
// nested under `native/` so the documented `cmake -S native` still applies.
//
// The file list is resolved when the task runs, not when the project is
// configured: a configuration-cache entry may be replayed on a different
// machine, and `git` belongs to the former.
// ---------------------------------------------------------------------------

/**
 * Build inputs that exist on disk but are not in the index.
 *
 * The archive is deliberately built from `git ls-files`, because the index is
 * what another person receives on a clean checkout. The cost of that choice is
 * that a new source file the CMake graph needs is silently absent from the
 * archive, and the release then fails on someone else's machine — which is
 * exactly what happened when an internal header was added without being staged.
 *
 * So the mismatch is reported here instead: any file git considers untracked and
 * not ignored, with a build-input extension, makes the archive incomplete. Git's
 * own ignore rules decide what counts, so build output, IDE state and local
 * libraries stay out of the comparison.
 */
/** Extensions whose untracked presence makes the source archive incomplete. */
val nativeBuildInputExtensions = setOf("c", "h", "hpp", "hh", "cc", "cpp", "cxx", "in", "cmake", "kt", "kts", "java")

/**
 * Whether an untracked path makes the source archive incomplete.
 *
 * Build inputs qualify by extension. The documentation set qualifies by
 * location: a markdown file under `docs` is a delivered artefact of this
 * module, so an untracked audit or contract would vanish from the archive
 * while the build stayed green. A stray markdown note elsewhere is not a
 * release input and does not block anything.
 *
 * (The glob is deliberately written without a slash-then-star sequence:
 * Kotlin block comments nest, so `docs/` followed by `*` inside this comment
 * would open a nested comment that never closes — and the compiler would
 * silently drop every task registered below this point.)
 */
fun isNativeReleaseInput(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in nativeBuildInputExtensions ||
        path.endsWith("CMakeLists.txt") ||
        path == "CMakePresets.json" ||
        (path.startsWith("docs/") && path.endsWith(".md"))

fun untrackedNativeBuildInputs(): List<String> {
    val git = requireTool(name = "git", propertyName = "veltisNativeGit",
        environmentName = "GIT", installHint = "Git 2.20 or newer")
    val listing = providers.exec {
        commandLine(git, "-C", projectDir.absolutePath,
            "ls-files", "--others", "--exclude-standard", "-z")
    }.standardOutput.asText.get()
    return listing.split('\u0000')
        .map { it.trim().replace('\\', '/') }
        .filter { it.isNotEmpty() }
        .filter {
            // The extension, not a substring: `Regex.matches` anchors the whole
            // input, and an earlier version of this filter used it on a
            // `\.(cpp|…)$` pattern, which only ever matched a file literally
            // named `.cpp` — a guard that could not fire.
            isNativeReleaseInput(it)
        }
        .sorted()
}

/** Tracked files inside this module, as paths relative to the module directory. */
fun trackedNativeSources(): List<String> {
    val git = requireTool(name = "git", propertyName = "veltisNativeGit",
        environmentName = "GIT", installHint = "Git 2.20 or newer")
    val listing = providers.exec {
        commandLine(git, "-C", projectDir.absolutePath, "ls-files", "-z")
    }.standardOutput.asText.get()
    val tracked = listing.split('\u0000').map { it.trim() }.filter { it.isNotEmpty() }
    if (tracked.isEmpty()) {
        throw GradleException(
            "`git ls-files` found no tracked files under ${projectDir.absolutePath}\n" +
                "  The source archive is built from the index; nothing is packaged without it."
        )
    }
    val missing = tracked.filter { !File(projectDir, it).isFile }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "These paths are in the git index but are not files on disk, so the working " +
                "tree is mid-deletion and cannot be packaged:\n  " + missing.joinToString("\n  ")
        )
    }
    return tracked.sorted()
}

val nativeSourcePaths = provider { trackedNativeSources() }

val nativeSourceArchive = nativeDistRoot.map { it.file("veltis-native-source.zip") }

val packageNativeSourceArchive by tasks.registering(Zip::class) {
    group = "native"
    description = "Creates a reproducible source-only archive from the git index"
    destinationDirectory.set(nativeDistRoot)
    archiveFileName.set("veltis-native-source.zip")
    into("native")
    from(nativeSourcePaths.map { paths ->
        project.fileTree(projectDir).matching { include(paths) }
    })
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    isZip64 = false
    outputs.upToDateWhen { false }

    doFirst {
        val untracked = untrackedNativeBuildInputs()
        if (untracked.isNotEmpty()) {
            throw GradleException(
                "The source archive would be incomplete: these build inputs exist on disk but " +
                    "are not in the git index, so `git ls-files` cannot package them. Add them " +
                    "(or remove them) before producing a release archive:\n  " +
                untracked.joinToString("\n  ")
            )
        }
    }

    doLast {
        val archive = archiveFile.get().asFile
        val checksum = sha256(archive)
        File(archive.parentFile, "${archive.name}.sha256")
            .writeText("$checksum  ${archive.name}\n")
        logger.lifecycle(
            "Native source archive: ${archive.absolutePath} " +
                "(${nativeSourcePaths.get().size} tracked files, sha256 $checksum)"
        )
    }
}

/**
 * Extracts the source archive into a directory that has no working tree behind
 * it and configures + builds that copy.
 *
 * This is the check that a locally successful build was not carried by an
 * untracked file. It needs a compiler and therefore is deliberately not part of
 * `build`; it fails rather than quietly skipping when CMake is absent, because a
 * skipped release check is worse than an absent one.
 */
val verifyNativeSourceArchive by tasks.registering {
    group = "verification"
    description = "Extracts the source archive and builds it from scratch, outside the working tree"
    dependsOn(packageNativeSourceArchive)
    outputs.upToDateWhen { false }

    val workspace = layout.buildDirectory.dir("native/source-check")

    doLast {
        val root = workspace.get().asFile
        if (root.exists() && !root.deleteRecursively()) {
            throw GradleException("could not clear ${root.absolutePath} before extracting")
        }
        val extracted = File(root, "native")
        extracted.parentFile.mkdirs()

        ZipFile(nativeSourceArchive.get().asFile).use { archive ->
            val destination = extracted.canonicalFile.parentFile
            archive.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val target = File(destination, entry.name)
                if (!target.canonicalFile.toPath().startsWith(destination.toPath())) {
                    throw GradleException("source archive entry escapes its directory: ${entry.name}")
                }
                target.parentFile.mkdirs()
                archive.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }

        // Content checks: the archive is a buildable source tree and nothing
        // else. A leak here means an ignored path is missing from .gitignore.
        // Separator-insensitive because the checks below are path-prefix tests.
        val files = extracted.walk().filter { it.isFile }
            .map { it.relativeTo(extracted).path.replace('\\', '/') }
            .toSet()
        val forbidden = listOf(
            "CMakeCache.txt", "CMakeFiles", "CTestTestfile.cmake", "build.ninja", "rules.ninja",
            ".ninja_deps", ".ninja_log", ".git", "compile_commands.json", "CMakeDetermineCompilerABI",
        )
        val leaked = files.filter { path ->
            forbidden.any { marker -> path == marker || path.contains("/$marker") || path.startsWith(marker) }
                    || path.endsWith(".o") || path.endsWith(".obj") || path.endsWith(".pdb")
                    || path.endsWith(".dll") || path.endsWith(".so") || path.endsWith(".dylib")
                    || path.endsWith(".exe")
        }
        if (leaked.isNotEmpty()) {
            throw GradleException(
                "The source archive carries generated or local state:\n  " + leaked.joinToString("\n  ")
            )
        }
        val required = listOf(
            "CMakeLists.txt",
            "CMakePresets.json",
            "include/veltis/native/veltis_native.h",
            "src/c/native_utils.c",
            "src/cpp/native_engine.cpp",
            "src/cpp/native_buffer_checks.hpp",
            "tests/CMakeLists.txt",
            "tests/export_check.c",
            "tests/abi_contract_checks.h",
            "tests/abi_contract_c.c",
            "tests/abi_contract_cxx.cpp",
            "cmake/veltis-native-build-info.json.in",
        )
        val absent = required.filter { !File(extracted, it).isFile }
        if (absent.isNotEmpty()) {
            throw GradleException(
                "The source archive is missing build inputs:\n  " + absent.joinToString("\n  ")
            )
        }
        if (files.none { it.startsWith("cmake/toolchains/") }) {
            throw GradleException("The source archive carries no CMake toolchain file.")
        }

        val cmake = requireTool(
            name = "cmake",
            propertyName = "veltisNativeCmake",
            environmentName = "CMAKE",
            installHint = "CMake 3.21 or newer"
        )
        val buildDir = File(root, "cmake-build")
        val archiveConfigure = mutableListOf(
            cmake, "-S", extracted.absolutePath, "-B", buildDir.absolutePath,
            "-DCMAKE_BUILD_TYPE=Release",
            // The extracted tree is inspected for self-containment, not for
            // coverage: tests need this host to execute the target binary.
            "-DVELTIS_NATIVE_BUILD_TESTS=OFF",
        )
        // The extracted tree is built with the same generator rule as the lane
        // above, so an archive is verified with the build tool this host actually
        // has rather than a CMake default that may name a program that is absent.
        nativeGenerator.get().takeIf { it.isNotBlank() }?.let { generator ->
            archiveConfigure += listOf("-G", generator)
        }
        // A CMake failure has to say why. `providers.exec` captures the child's
        // output into a buffer nobody prints and cannot be redirected when the
        // result is read through its Provider, and it reports only "non-zero exit
        // value 1" — which turns a missing header or a generator mismatch into a
        // debugging session instead of a message. The child is run directly here
        // so stdout and stderr are merged, captured, and printed on failure.
        fun runArchiveStep(label: String, command: List<String>) {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            if (exit != 0) {
                throw GradleException(
                    "Source archive $label failed with exit value $exit.\n" +
                        "Command: ${command.joinToString(" ")}\n" +
                        "Output:\n${output.trimEnd()}"
                )
            }
        }

        runArchiveStep("configure", archiveConfigure)
        runArchiveStep(
            "build",
            listOf(cmake, "--build", buildDir.absolutePath, "--config", "Release", "--parallel")
        )
        val built = buildDir.walk().firstOrNull { it.name == nativeLibraryName }
            ?: throw GradleException(
                "The extracted archive configured and built, but produced no $nativeLibraryName"
            )
        logger.lifecycle(
            "Source archive verified: ${files.size} files extracted to ${extracted.absolutePath}, " +
                "built from scratch to ${built.absolutePath}"
        )
    }
}

// ---------------------------------------------------------------------------
// Manifest correctness
//
// A manifest is a list of claims about a binary, so every claim is checked
// against the artifact that is actually on disk: the digest is recomputed, the
// container header is read through NativeLoader — the same code the server runs
// at startup — and the ABI fields are compared with the public header, which is
// their only authority. Fields CMake could not observe must be absent rather
// than guessed, because an unverified value in a manifest is a support claim.
// ---------------------------------------------------------------------------

/** The ABI facts as the public header states them. */
class HeaderAbi(val abiVersion: String, val version: String, val exports: List<String>)

fun readHeaderAbi(): HeaderAbi {
    val header = File(projectDir, "include/veltis/native/veltis_native.h")
    if (!header.isFile) {
        throw GradleException("the public ABI header is missing: ${header.absolutePath}")
    }
    val lines = header.readLines()
    fun constant(name: String, pattern: Regex): String =
        lines.firstNotNullOfOrNull { pattern.find(it)?.groupValues?.get(1) }
            ?: throw GradleException("$name is not defined in ${header.name}")
    val abi = constant("VELTIS_NATIVE_ABI_VERSION", Regex("""^#define\s+VELTIS_NATIVE_ABI_VERSION\s+(\d+)"""))
    val version = constant("VELTIS_NATIVE_VERSION_STRING", Regex("""^#define\s+VELTIS_NATIVE_VERSION_STRING\s+"([^"]+)""""))
    val apiLine = Regex("""^VELTIS_NATIVE_API\s+.*[ *]([A-Za-z_][A-Za-z0-9_]*)\(""")
    val exports = lines.mapNotNull { apiLine.find(it)?.groupValues?.get(1) }
    if (exports.isEmpty()) {
        throw GradleException("no VELTIS_NATIVE_API declarations found in ${header.name}")
    }
    return HeaderAbi(abi, version, exports)
}

/**
 * Runs the shipped binary through the production header parser.
 *
 * The module's own classes are loaded reflectively because a build script cannot
 * depend on code it is also compiling; probeBinary is package-private, exactly
 * as the tests in this module call it.
 */
fun probeWithNativeLoader(library: File): Pair<String, Set<String>> {
    val urls = sourceSets["main"].output.classesDirs.files
        .map { it.toURI().toURL() }
        .toTypedArray()
    if (urls.isEmpty()) {
        throw GradleException("this module's classes were not compiled; cannot inspect the artifact")
    }
    val loader = URLClassLoader(urls, null)
    try {
        val nativeLoader = loader.loadClass("org.veltismc.nativeengine.NativeLoader")
        val probe = nativeLoader.getDeclaredMethod("probeBinary", Path::class.java)
        probe.isAccessible = true
        val info = probe.invoke(null, library.toPath())
        fun component(name: String): Any {
            val accessor = info.javaClass.getDeclaredMethod(name)
            accessor.isAccessible = true
            return accessor.invoke(info)
        }
        @Suppress("UNCHECKED_CAST")
        return component("format") as String to component("architectures") as Set<String>
    } finally {
        loader.close()
    }
}

val verifyNativeArtifact by tasks.registering {
    group = "verification"
    description = "Checks every field of the packaged manifest against the artifact it describes"
    dependsOn(packageNativeArchive, tasks.named("classes"))
    outputs.upToDateWhen { false }

    doLast {
        val destination = nativeDistDir.get().asFile
        val manifestFile = File(destination, "veltis-native-manifest.json")
        val sumsFile = File(destination, "SHA256SUMS")
        val missing = listOf(manifestFile, sumsFile).filter { !it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "packageNative did not produce:\n  " +
                    missing.joinToString("\n  ") { it.absolutePath }
            )
        }

        @Suppress("UNCHECKED_CAST")
        val manifest = JsonSlurper().parse(manifestFile) as Map<String, Any?>
        val libraryName = manifest["library"] as? String
            ?: throw GradleException("the manifest records no library file name")
        val library = File(destination, libraryName)
        val problems = mutableListOf<String>()
        if (!library.isFile) {
            throw GradleException(
                "the manifest names $libraryName, which is not in ${destination.absolutePath}"
            )
        }

        // --- digests: recomputed here, never copied from the manifest ---------
        val digest = sha256(library)
        if (manifest["sha256"] != digest) {
            problems += "manifest sha256 ${manifest["sha256"]} does not match the library's $digest"
        }
        val size = (manifest["sizeBytes"] as? Number)?.toLong()
        if (size != library.length()) {
            problems += "manifest sizeBytes $size does not match the library's ${library.length()}"
        }
        val signed = sumsFile.readLines().filter { it.isNotBlank() }.associate { line ->
            val fields = line.trim().split(Regex("\\s+"), limit = 2)
            if (fields.size != 2) throw GradleException("malformed SHA256SUMS line: '$line'")
            fields[1] to fields[0]
        }
        for ((name, claimed) in signed) {
            val signedFile = File(destination, name)
            if (!signedFile.isFile) {
                problems += "SHA256SUMS lists $name, which is not in the distribution"
            } else if (sha256(signedFile) != claimed) {
                problems += "SHA256SUMS does not match $name"
            }
        }
        val unsigned = destination.listFiles()
            ?.map { it.name }
            ?.filter { it != "SHA256SUMS" && it !in signed.keys }
            .orEmpty()
        if (unsigned.isNotEmpty()) {
            problems += "files in the distribution that nothing signs: ${unsigned.joinToString(", ")}"
        }

        // --- ABI: the header is the authority, not the manifest ---------------
        val abi = readHeaderAbi()
        if (manifest["abiVersion"]?.toString() != abi.abiVersion) {
            problems += "manifest abiVersion ${manifest["abiVersion"]} != the header's ${abi.abiVersion}"
        }
        if (manifest["version"] != abi.version) {
            problems += "manifest version ${manifest["version"]} != the header's ${abi.version}"
        }
        @Suppress("UNCHECKED_CAST")
        val declared = (manifest["exports"] as? List<Any?>).orEmpty().map { it.toString() }
        if (declared != abi.exports) {
            problems += "manifest exports ${declared.joinToString(",")} are not the header's " +
                abi.exports.joinToString(",")
        }
        val exportCount = (manifest["exportCount"] as? Number)?.toInt()
        if (exportCount != abi.exports.size) {
            problems += "manifest exportCount $exportCount != ${abi.exports.size} header declarations"
        }
        val loaderAbi = Regex("""EXPECTED_ABI_VERSION\s*=\s*(\d+)""").find(
            File(projectDir, "src/main/java/org/veltismc/nativeengine/NativeLoader.java").readText()
        )?.groupValues?.get(1)
        if (loaderAbi != abi.abiVersion) {
            problems += "NativeLoader accepts ABI $loaderAbi but the header declares ${abi.abiVersion}"
        }

        // --- what the container actually is ----------------------------------
        val probed = probeWithNativeLoader(library)
        val os = manifest["os"] as? String
        val architecture = manifest["architecture"] as? String
        val expectedFormat = when {
            nativePlatform.startsWith("windows") -> "PE"
            nativePlatform.startsWith("macos") -> "Mach-O"
            nativePlatform.startsWith("linux") -> "ELF"
            else -> null
        }
        if (expectedFormat != null && probed.first != expectedFormat) {
            problems += "a $nativePlatform artifact is a ${probed.first} file, not $expectedFormat"
        }
        if (probed.second != setOf(architecture)) {
            problems += "the container reports ${probed.second.joinToString(",")} but the " +
                "manifest claims architecture '$architecture'"
        }
        if ("$os-$architecture" != nativePlatform) {
            problems += "manifest os/architecture '$os-$architecture' does not spell the platform " +
                "key '$nativePlatform' the library was built for"
        }
        // The FFM descriptor maps size_t to JAVA_LONG, so a 32-bit artifact would
        // load and then corrupt lengths instead of failing cleanly.
        val pointerBits = (manifest["pointerBits"] as? Number)?.toInt()
        if (pointerBits != 64) {
            problems += "pointerBits is $pointerBits; this integration supports 64-bit targets only"
        }
        if (architecture !in setOf("x86_64", "aarch64", "riscv64")) {
            problems += "architecture '$architecture' is not a supported 64-bit target"
        }

        // --- build, compiler and runtime claims -------------------------------
        if (manifest["buildType"] != nativeBuildType.get()) {
            problems += "manifest buildType ${manifest["buildType"]} != the requested " +
                nativeBuildType.get()
        }
        @Suppress("UNCHECKED_CAST")
        val compiler = manifest["compiler"] as? Map<String, Any?>
            ?: throw GradleException("the manifest records no compiler block")
        for (language in listOf("c", "cxx")) {
            @Suppress("UNCHECKED_CAST")
            val entry = compiler[language] as? Map<String, Any?>
                ?: throw GradleException("the manifest records no $language compiler")
            if (blank(entry["id"])) problems += "compiler.$language.id is empty"
            if (blank(entry["version"])) problems += "compiler.$language.version is empty"
        }
        if (blank(compiler["targetTriple"])) {
            problems += "compiler.targetTriple is empty; record 'unknown' instead of silence " +
                "when no triple can be derived"
        }
        @Suppress("UNCHECKED_CAST")
        val runtime = manifest["runtime"] as? Map<String, Any?>
            ?: throw GradleException("the manifest records no runtime block")
        if (runtime["staticRuntime"] !is Boolean) {
            problems += "runtime.staticRuntime must be true or false, not ${runtime["staticRuntime"]}"
        }
        val minimumOs = runtime["minimumOsVersion"] as? String
        if (os == "macos") {
            if (minimumOs.isNullOrBlank() || !minimumOs.matches(Regex("""\d+(\.\d+)*"""))) {
                problems += "runtime.minimumOsVersion '$minimumOs' is not a macOS version; the " +
                    "deployment target has to reach both the compiler and the linker"
            }
        } else if (!minimumOs.isNullOrBlank()) {
            // A glibc or MSVC floor is not observable at build time, so a value
            // here would be a runtime-compatibility claim nobody verified.
            problems += "runtime.minimumOsVersion must stay empty for $os; it was never verified"
        }
        val commit = manifest["sourceCommit"] as? String
        if (commit == null || !Regex("""[0-9a-f]{40}""").matches(commit)) {
            problems += "sourceCommit '$commit' is not a commit id; a release needs provenance"
        }
        if (manifest["sourceTreeState"] !in setOf("clean", "dirty")) {
            problems += "sourceTreeState ${manifest["sourceTreeState"]} must be clean or dirty"
        }

        // --- the distribution archive: same bytes, nothing extra --------------
        val archive = packageNativeArchive.get().archiveFile.get().asFile
        val sidecar = File(archive.parentFile, "${archive.name}.sha256")
        val archiveDigest = if (archive.isFile) sha256(archive) else "absent"
        if (!archive.isFile) {
            problems += "no distribution archive at ${archive.absolutePath}"
        } else {
            if (!sidecar.isFile) {
                problems += "no ${archive.name}.sha256 beside the distribution archive"
            } else {
                val claimed = sidecar.readLines().firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
                if (claimed != archiveDigest) {
                    problems += "${sidecar.name} says '$claimed' but the archive hashes to $archiveDigest"
                }
            }
            ZipFile(archive).use { zip ->
                val packed = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .map { it.name }
                    .toSet()
                val expected = setOf(libraryName, "veltis-native-manifest.json", "SHA256SUMS")
                if (packed != expected) {
                    problems += "the archive packs ${packed.joinToString(",")}; expected ${expected.joinToString(",")}"
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "The artifact manifest does not describe the artifact:\n  " +
                    problems.joinToString("\n  ")
            )
        }

        logger.lifecycle(
            "Manifest verified for $nativePlatform: ${probed.first}/${probed.second.joinToString(",")} " +
                "$libraryName, ${library.length()} bytes, abi ${abi.abiVersion}, " +
                "${abi.exports.size} exports, build ${nativeBuildType.get()}, " +
                "commit ${commit!!.substring(0, 7)} tree ${manifest["sourceTreeState"]}, " +
                "compiler ${compilerText(compiler, "c")}/${compilerText(compiler, "cxx")}, " +
                "triple ${compiler["targetTriple"]}, archive ${archive.name} ($archiveDigest)"
        )
    }
}

/** A manifest field that carries no information. */
fun blank(value: Any?): Boolean = value == null || value.toString().isBlank()

/**
 * Skips that `verifyNativeRelease` accepts, because they assert something about a
 * configuration this run is *not* in. Anything else that skips means a native
 * precondition went missing and the release evidence would be thinner than the
 * report claims.
 */
val NATIVE_RELEASE_TOLERATED_SKIPS = setOf("javaOnlySuiteHasNoNativeFixtureConfigured")

/** `tests, skipped, failures, errors` from one JUnit XML suite. */
fun junitCounts(xml: String): IntArray? {
    val header = Regex("""<testsuite\b[^>]*""").find(xml)?.value ?: return null
    fun attribute(name: String): Int =
        Regex("""\s$name="(\d+)"""").find(header)?.groupValues?.get(1)?.toInt() ?: -1
    return intArrayOf(attribute("tests"), attribute("skipped"), attribute("failures"), attribute("errors"))
}

/** The same four totals summed over every suite file. */
fun junitTotals(suites: List<File>): IntArray {
    val totals = IntArray(4)
    for (suite in suites) {
        val counts = junitCounts(suite.readText()) ?: continue
        for (index in totals.indices) totals[index] += counts[index]
    }
    return totals
}

/** The `id version` string CMake recorded for one language. */
fun compilerText(compiler: Map<String, Any?>, language: String): String {
    @Suppress("UNCHECKED_CAST")
    val entry = compiler[language] as? Map<String, Any?> ?: return "unknown"
    return "${entry["id"]} ${entry["version"]}"
}

/**
 * The PE machine field that `$nativePlatform` claims, or null when the key names
 * no architecture this check can verify. `hostPlatformKey` normalises `arm64` to
 * `aarch64`, but an explicit `-PveltisNativePlatform` can carry either spelling,
 * so both are accepted.
 */
val peMachineForPlatform: String? = when (nativePlatform.split("-").getOrNull(1)) {
    "x86_64", "amd64", "x64" -> "x86_64"
    "aarch64", "arm64" -> "aarch64"
    "x86" -> "x86"
    "arm64ec" -> "arm64ec"
    else -> null
}

/**
 * The shell able to run `tools/check-pe-imports.ps1`, or null when there is none.
 *
 * `pwsh` (PowerShell 7) first, then `powershell` (Windows PowerShell 5.1, shipped
 * with every supported Windows). A host with neither is a failure at the call
 * site, not a skip: this check is the gate that keeps the winpthread regression
 * from returning, and a gate that passes quietly because its inspector is
 * missing is not a gate.
 */
fun windowsCheckShell(): String? =
    listOf("pwsh", "powershell").asSequence().mapNotNull(::findOnPath).firstOrNull()

val verifyNativeRuntimePolicy by tasks.registering {
    group = "verification"
    description = "Verifies that the built library imports only the runtime it is allowed to"
    dependsOn(buildNative)
    outputs.upToDateWhen { false }

    doLast {
        val library = nativeLibraryFile.get().asFile
        if (!library.isFile) {
            throw GradleException("native library was not produced: ${library.absolutePath}")
        }

        if (!nativePlatform.startsWith("windows")) {
            // ELF and Mach-O use the system runtime by design, so there is no
            // static-link policy to break. CI inspects their dependencies with
            // ldd/otool instead; see .github/workflows/gradle.yml.
            logger.lifecycle(
                "Runtime-policy check is Windows-only: $nativePlatform uses the system runtime " +
                    "and its dependencies are recorded by the CI dependency step."
            )
            return@doLast
        }

        // The cache, not the Gradle property: this check describes the artifact
        // that was linked, and a property value only says what was requested.
        val cache = File(cmakeBuildDir.get().asFile, "CMakeCache.txt")
        if (!cache.isFile) {
            throw GradleException(
                "no CMake cache at ${cache.absolutePath}: run :veltis-native:configureNative first"
            )
        }
        val staticRuntime = Regex(
            """^VELTIS_NATIVE_STATIC_RUNTIME:BOOL=(\S+)""", RegexOption.MULTILINE
        ).find(cache.readText())?.groupValues?.get(1)?.let {
            it.equals("ON", true) || it.equals("TRUE", true) || it == "1"
        } ?: throw GradleException(
            "${cache.absolutePath} records no VELTIS_NATIVE_STATIC_RUNTIME, so the runtime " +
                "policy of this artifact cannot be determined"
        )
        val machine = peMachineForPlatform ?: throw GradleException(
            "the platform key '$nativePlatform' names no PE architecture, so nothing can be " +
                "verified about it. Add the architecture to peMachineForPlatform and to " +
                "tools/check-pe-imports.ps1 together."
        )
        val script = File(projectDir, "tools/check-pe-imports.ps1")
        if (!script.isFile) {
            throw GradleException("the PE import checker is missing: ${script.absolutePath}")
        }
        val shell = windowsCheckShell() ?: throw GradleException(
            "Neither pwsh nor powershell is on PATH, so ${script.name} cannot inspect " +
                "${library.name} and the Windows runtime policy would go unverified rather than pass. " +
                "Run this task on a Windows host, where PowerShell 5.1 is always present."
        )
        val command = mutableListOf(
            shell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath,
            "-Path", library.absolutePath, "-ExpectMachine", machine,
        )
        // Dynamic MSVC CRT is a supported configuration — the option defaults to OFF
        // for MSVC and the manifest records whichever way it went. MinGW and LLVM
        // runtimes are rejected by the script either way: those files belong to the
        // toolchain, not to Windows, and a copied DLL cannot rely on them.
        if (!staticRuntime) command += "-AllowMsvcruntime"

        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        if (exit != 0) {
            val guidance = if (staticRuntime) {
                "The static link is enabled for this artifact, so the toolchain's link rules in " +
                    "CMakeLists.txt no longer cover this dependency."
            } else {
                "The release policy is the static link: drop -PveltisNativeStaticRuntime=false " +
                    "or record the exception deliberately."
            }
            throw GradleException(
                "Runtime policy violated by the $nativePlatform artifact ${library.name} " +
                    "(staticRuntime=$staticRuntime, inspected with ${script.name}):\n" +
                    output.trimEnd() + "\n  " + guidance
            )
        }

        logger.lifecycle(
            "Runtime policy verified for $nativePlatform (staticRuntime=$staticRuntime, " +
                "inspected with ${script.name}):\n" + output.trimEnd()
        )
    }
}

/**
 * The module-scoped half of the release checklist. It is intentionally not
 * attached to `build`: it needs a compiler and it is run deliberately before a
 * release. The full procedure (clean checkout, Java-only fallback, server
 * bootstrap) is documented in README.md and mirrored by CI.
 */
val verifyNativeRelease by tasks.registering {
    group = "verification"
    description = "Builds, tests, packages and records native release evidence for $nativePlatform"
    dependsOn(buildNative, testNative, packageNativeArchive, verifyNativeArtifact,
        verifyNativeRuntimePolicy, verifyNativeSourceArchive, tasks.named("test"))
    outputs.upToDateWhen { false }

    doFirst {
        if (nativeCrossCompiling) {
            throw GradleException(
                "verifyNativeRelease needs a library this JVM can execute, but $nativePlatform " +
                    "was cross-compiled on $nativeHostPlatform"
            )
        }
        if (providers.gradleProperty("veltisNativeTest").orNull?.toBoolean() != true) {
            throw GradleException(
                "verifyNativeRelease must run the Java integration suite: " +
                    "re-run with -PveltisNativeTest=true"
            )
        }
        // A green native job whose integration tests quietly skipped would be the
        // most expensive kind of green. JUnit reports an unmet `Assumptions`
        // precondition as a skip, and Gradle reports a skipped test as a success,
        // so the release check has to read the results itself.
        val results = File(project.layout.buildDirectory.get().asFile, "test-results/test")
        val suites = results.listFiles { file ->
            file.name.startsWith("TEST-") && file.name.endsWith(".xml")
        }?.toList().orEmpty()
        if (suites.isEmpty()) {
            throw GradleException(
                "no JUnit XML in ${results.absolutePath}: the Java integration suite did not run"
            )
        }
        val skippedNames = suites
            .flatMap { Regex("""<testcase name="([^"]+)"[^>]*>\s*<skipped""").find(it.readText())?.groupValues
                ?.drop(1).orEmpty() }
            .map { it.removeSuffix("()") }
            .toSet()
        val unexplained = skippedNames - NATIVE_RELEASE_TOLERATED_SKIPS
        if (unexplained.isNotEmpty()) {
            throw GradleException(
                "native integration tests were skipped: ${unexplained.sorted().joinToString(", ")}"
            )
        }
        val endToEnd = suites.singleOrNull { it.name.contains("NativeEndToEndTest") }
            ?: throw GradleException("NativeEndToEndTest did not run; nothing proved the real library loads")
        val counts = junitCounts(endToEnd.readText())
            ?: throw GradleException("cannot read the JUnit results of ${endToEnd.name}")
        if (counts[0] == 0 || counts[1] != 0 || counts[2] != 0 || counts[3] != 0) {
            throw GradleException(
                "NativeEndToEndTest ran ${counts[0]} tests with ${counts[1]} skipped, " +
                    "${counts[2]} failures and ${counts[3]} errors"
            )
        }
        logger.lifecycle(
            "Native integration suite: ${junitTotals(suites).joinToString()} tests across " +
                "${suites.size} classes, ${skippedNames.size} tolerated skip(s)"
        )
    }

    doLast {
        val library = nativeLibraryFile.get().asFile
        val archive = packageNativeArchive.get().archiveFile.get().asFile
        val report = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "commit" to gitCommit(),
            "treeState" to gitTreeState(),
            "platform" to nativePlatform,
            "buildType" to nativeBuildType.get(),
            "library" to library.name,
            "librarySha256" to sha256(library),
            "archive" to archive.name,
            "archiveSha256" to sha256(archive),
            "javaVersion" to System.getProperty("java.version"),
            "osName" to System.getProperty("os.name"),
            "osArch" to System.getProperty("os.arch"),
            "verifiedAt" to Instant.now().toString(),
            "checks" to listOf(
                "configure",
                "build",
                "ctest",
                "export-check",
                "java-integration",
                "java-integration-not-skipped",
                "package",
                "checksum",
                "manifest-vs-artifact",
                "runtime-policy",
                "source-archive-from-scratch",
            ),
        )
        val target = File(nativeDistRoot.get().asFile, "verification-report-$nativePlatform.json")
        target.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(report)) + "\n")
        logger.lifecycle("Native release verification written to ${target.absolutePath}")
    }
}
