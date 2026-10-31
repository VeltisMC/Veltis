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
 */

import java.io.File

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

val nativePlatform = hostPlatformKey()
val nativeBuildType = providers.gradleProperty("veltisNativeBuildType").orElse("Release")
val nativeGenerator = providers.gradleProperty("veltisNativeGenerator")

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
    outputs.file(cmakeBuildDir.map { it.file("CMakeCache.txt") }).withPropertyName("cmakeCache")

    doFirst {
        val cmake = requireTool(
            name = "cmake",
            propertyName = "veltisNativeCmake",
            environmentName = "CMAKE",
            installHint = "CMake 3.20 or newer"
        )
        val args = mutableListOf(
            cmake,
            "-S", projectDir.absolutePath,
            "-B", cmakeBuildDir.get().asFile.absolutePath,
            "-DCMAKE_BUILD_TYPE=${nativeBuildType.get()}",
            "-DVELTIS_NATIVE_OUTPUT_DIR=${nativeOutputDir.get().asFile.absolutePath}",
            "-DVELTIS_NATIVE_BUILD_TESTS=ON",
        )
        nativeGenerator.orNull?.takeIf { it.isNotBlank() }?.let { generator ->
            args += listOf("-G", generator)
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
            installHint = "CMake 3.20 or newer"
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
        val ctest = requireTool(
            name = "ctest",
            propertyName = "veltisNativeCtest",
            environmentName = "CTEST",
            installHint = "CMake 3.20 or newer (CTest ships with it)"
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

tasks.withType<Test>().configureEach {
    // The integration tests call Linker.nativeLinker()/SymbolLookup. On JDK 24+
    // that is a restricted operation: without this the JVM prints a warning for
    // every test JVM. The launcher already passes the same flag to the server.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty(
        "veltis.native.test.library",
        providers.systemProperty("veltis.native.test.library")
            .orElse(nativeLibraryFile.map { it.asFile.absolutePath })
            .get()
    )
    systemProperty(
        "veltis.native.test.fakeAbi",
        providers.systemProperty("veltis.native.test.fakeAbi")
            .orElse(fixtureOutputDir.map { it.file(fixtureLibrary("veltis_native_fake_abi")).asFile.absolutePath })
            .get()
    )
    systemProperty(
        "veltis.native.test.fakeVersion",
        providers.systemProperty("veltis.native.test.fakeVersion")
            .orElse(fixtureOutputDir.map { it.file(fixtureLibrary("veltis_native_fake_version")).asFile.absolutePath })
            .get()
    )
    systemProperty(
        "veltis.native.test.fakeInit",
        providers.systemProperty("veltis.native.test.fakeInit")
            .orElse(fixtureOutputDir.map { it.file(fixtureLibrary("veltis_native_fake_init")).asFile.absolutePath })
            .get()
    )
    systemProperty(
        "veltis.native.test.fakeTransform",
        providers.systemProperty("veltis.native.test.fakeTransform")
            .orElse(fixtureOutputDir.map { it.file(fixtureLibrary("veltis_native_fake_transform")).asFile.absolutePath })
            .get()
    )
    systemProperty(
        "veltis.native.test.fakeMissing",
        providers.systemProperty("veltis.native.test.fakeMissing")
            .orElse(fixtureOutputDir.map { it.file(fixtureLibrary("veltis_native_fake_missing")).asFile.absolutePath })
            .get()
    )
    systemProperty(
        "veltis.native.test.fakeMinimal",
        providers.systemProperty("veltis.native.test.fakeMinimal")
            .orElse(fixtureOutputDir.map { it.file(fixtureLibrary("veltis_native_fake_minimal")).asFile.absolutePath })
            .get()
    )

    if (providers.gradleProperty("veltisNativeTest").orNull?.toBoolean() == true) {
        dependsOn(buildNative)
    }
}

if (providers.gradleProperty("veltisNativeTest").orNull?.toBoolean() == true) {
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
}
