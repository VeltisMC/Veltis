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
 * Use `-PveltisNativeTest=true` to make the Java test task build the native
 * library first and to run CTest as part of `check`. Without that flag the
 * real-integration test is skipped when no library has been built, which is the
 * only way an ordinary Java-only build can avoid requiring CMake.
 *
 * Cross-compilation is selected with:
 *
 *   -PveltisNativePlatform=windows-aarch64    output/installation key
 *   -PveltisNativeToolchainFile=cmake/toolchains/windows-arm64-mingw.cmake
 *   -PveltisNativeOsxArchitectures=arm64      macOS multi-arch selection
 *   -PveltisNativeStaticRuntime=true|false    override the per-toolchain default
 *
 * A cross build compiles and produces the library but never runs CTest, the Java
 * FFM integration tests or the benchmark: those require a host that can execute
 * the target binary.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest
import java.time.Instant

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
val nativeGenerator = providers.gradleProperty("veltisNativeGenerator")
// Kept as a String provider so an unset property is the empty string rather than
// a null File: Provider<File> has no no-value representation here, and the
// configure task needs a stable input value regardless of the host.
val nativeToolchainFile = providers.gradleProperty("veltisNativeToolchainFile")
    .orElse("")
    .map { it.trim() }
val nativeToolchainPath = nativeToolchainFile.map { if (it.isEmpty()) "" else file(it).absolutePath }
val nativeOsxArchitectures = providers.gradleProperty("veltisNativeOsxArchitectures").orElse("")
val nativeStaticRuntime = providers.gradleProperty("veltisNativeStaticRuntime").orElse("")
val nativeHostPlatform = hostPlatformKey()

/**
 * A build is cross-compiled when it targets a platform key other than the one
 * this JVM runs on, or when an explicit CMake toolchain file is supplied. Cross
 * artifacts are compiled and inspected, never executed: CTest and the Java
 * integration tests are host-only, so running them against a foreign binary
 * would report a false failure on a perfectly good build.
 */
val nativeCrossCompiling = nativePlatform != nativeHostPlatform || nativeToolchainFile.orNull?.isNotEmpty() == true

val nativeRoot = layout.buildDirectory.dir("native")
val nativeOutputDir = nativeRoot.map { it.dir(nativePlatform) }
val cmakeBuildDir = nativeRoot.map { it.dir("cmake/$nativePlatform") }
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

val nativeSourceInputs = fileTree(projectDir) {
    include("CMakeLists.txt", "CMakePresets.json")
    include("cmake/**")
    include("include/**")
    include("src/**")
    include("tests/**")
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
    inputs.property("generator", nativeGenerator.orElse(""))
    inputs.property("crossCompiling", nativeCrossCompiling)
    inputs.property("toolchainFile", nativeToolchainPath)
    inputs.property("osxArchitectures", nativeOsxArchitectures)
    inputs.property("staticRuntime", nativeStaticRuntime)
    outputs.file(cmakeBuildDir.map { it.file("CMakeCache.txt") }).withPropertyName("cmakeCache")

    doFirst {
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
        nativeGenerator.orNull?.takeIf { it.isNotBlank() }?.let { generator ->
            args += listOf("-G", generator)
        }
        nativeToolchainPath.orNull?.takeIf { it.isNotEmpty() }?.let { toolchain ->
            args += "-DCMAKE_TOOLCHAIN_FILE=$toolchain"
        }
        nativeOsxArchitectures.orNull?.takeIf { it.isNotBlank() }?.let { architectures ->
            args += "-DCMAKE_OSX_ARCHITECTURES=$architectures"
        }
        nativeStaticRuntime.orNull?.takeIf { it.isNotBlank() }?.let { value ->
            args += "-DVELTIS_NATIVE_STATIC_RUNTIME=${if (value.toBoolean()) "ON" else "OFF"}"
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

val cleanNative by tasks.registering(Delete::class) {
    group = "native"
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
// The paths are passed unconditionally. A test uses Assumptions when the file is
// absent, so `:veltis-native:test` works on a machine with no native toolchain
// and starts verifying the real library as soon as `buildNative` has produced
// one. `-PveltisNativeTest=true` makes that ordering explicit instead of relying
// on the operator having run buildNative first.
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

fun configureFixturePath(target: Test, property: String, defaultFile: File) {    val explicit = providers.systemProperty(property)
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
    configureFixturePath(this, "veltis.native.test.library", nativeLibraryFile.get().asFile)
    configureFixturePath(
        this, "veltis.native.test.fakeAbi",
        fixtureOutputDir.get().file(fixtureLibrary("veltis_native_fake_abi")).asFile
    )
    configureFixturePath(
        this, "veltis.native.test.fakeVersion",
        fixtureOutputDir.get().file(fixtureLibrary("veltis_native_fake_version")).asFile
    )
    configureFixturePath(
        this, "veltis.native.test.fakeInit",
        fixtureOutputDir.get().file(fixtureLibrary("veltis_native_fake_init")).asFile
    )
    configureFixturePath(
        this, "veltis.native.test.fakeTransform",
        fixtureOutputDir.get().file(fixtureLibrary("veltis_native_fake_transform")).asFile
    )
    configureFixturePath(
        this, "veltis.native.test.fakeMissing",
        fixtureOutputDir.get().file(fixtureLibrary("veltis_native_fake_missing")).asFile
    )
    configureFixturePath(
        this, "veltis.native.test.fakeMinimal",
        fixtureOutputDir.get().file(fixtureLibrary("veltis_native_fake_minimal")).asFile
    )

    // The fixture paths reach the test JVM as system-property *strings*:
    // Gradle tracks the string, not the bytes it points at. Declaring the files
    // as inputs is what makes a rebuilt DLL invalidate the Java suite instead
    // of leaving it UP-TO-DATE against a stale library. Files are resolved at
    // execution time so a checkout with no library yet is still valid input.
    if (!javaOnlyRequested) {
        val fixtureFiles = provider {
            buildList {
                add(nativeLibraryFile.get().asFile)
                fixtureOutputDir.get().asFile.listFiles()?.forEach { add(it) }
            }
        }
        inputs.files(fixtureFiles).withPropertyName("nativeTestFixtures")
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

/**
 * The module-scoped half of the release checklist. It is intentionally not
 * attached to `build`: it needs a compiler and it is run deliberately before a
 * release. The full procedure (clean checkout, Java-only fallback, server
 * bootstrap) is documented in README.md and mirrored by CI.
 */
val verifyNativeRelease by tasks.registering {
    group = "verification"
    description = "Builds, tests, packages and records native release evidence for $nativePlatform"
    dependsOn(buildNative, testNative, packageNativeArchive, tasks.named("test"))
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
                "package",
                "checksum",
            ),
        )
        val target = File(nativeDistRoot.get().asFile, "verification-report-$nativePlatform.json")
        target.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(report)) + "\n")
        logger.lifecycle("Native release verification written to ${target.absolutePath}")
    }
}
