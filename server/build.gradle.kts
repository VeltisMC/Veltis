import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("java-library")
}

description = "VeltisMC server: the NMS entrypoint, the Veltis runtime framework and the world engine"

// One module for everything that runs inside Minecraft's classloader, because
// that is the boundary that was real. `server`, `runtime` and `world` were
// three projects packaged into one jar by a list in `launcher/build.gradle.kts`
// that named each of them and their order; the world engine was reachable only
// through the framework, the framework only through `Main`, and none of the
// three was ever published or depended on from outside this repository. Three
// modules for one deployable unit is a boundary maintained for its own sake.
//
// The package names are unchanged, so nothing inside them had to move in a way
// that shows up as a diff: `org.veltismc.server`, `org.veltismc.runtime` and
// `org.veltismc.world` still belong to the classes they belonged to yesterday.

// The Minecraft classpath (patched classes, widened server jar, Mojang's
// libraries) is contributed by the root build so there is exactly one
// definition of it. Nothing here reaches into the workspace directly.

dependencies {
    implementation(project(":api"))
    implementation(project(":veltis-native"))
    implementation(project(":patcher"))
    // For the Veltis API
    // Optional native acceleration. The dependency is on the module's Java FFM
    // integration classes; the native shared library itself is discovered at
    // runtime and is deliberately not packaged or required.
    // Console/logging bootstrap (VeltisConsole) shared with the launcher:
    // Log4j2 configuration plus UTF-8/ANSI console setup — no Jansi, which on
    // JDK 26 prints restricted native-access warnings that cannot be fixed
    // from application code (Jansi 2.4.1 is the latest release).

    // The Log4j2 stack whose JUL manager the console setup installs, and the
    // same API Minecraft's own code uses, so Veltis messages and Minecraft
    // messages share one format.
    implementation("org.apache.logging.log4j:log4j-api:2.26.0")
    implementation("org.apache.logging.log4j:log4j-jul:2.26.0")

    // The JSON parser `MojangMetadata` resolves Mojang's version document with;
    // it has to be on the server runtime classpath the bootstrap table is built
    // from, not only on the launcher's.
    implementation("com.google.code.gson:gson:2.14.0")

    // The class-file parser the runtime delta applier needs: applying a patch
    // set splices `ClassDelta` payloads onto the verified vanilla classes, and
    // that splice parses both sides with ASM's tree API. Declared here rather
    // than inherited through `:patcher` so the start-up classpath this
    // build assembles for the server names its own requirement: these two are
    // in the bootstrap table the launcher preflights, not in veltismc.jar.
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-tree:9.10.1")

    // Tests run with a real Log4j2 context (see src/test/resources/log4j2.xml).
    testRuntimeOnly("org.apache.logging.log4j:log4j-core:2.26.0")
    testRuntimeOnly("org.apache.logging.log4j:log4j-jul:2.26.0")
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(rootProject.tasks.named("generateBuildMetadata"))
    from(rootProject.layout.buildDirectory.file("metadata/build-metadata.json")) {
        rename { "veltis-build-metadata.json" }
    }
}

// `NativeBootstrapTest` exercises the same startup path a real installation
// uses, so it reads `veltis.native.path` exactly as the server does. The
// property is forwarded only when the caller set it: a plain `:server:test`
// keeps testing the Java-only fallback and must never pick up a library from an
// unrelated build output.
tasks.withType<Test>().configureEach {
    // The startup smoke test loads the FFM library when `veltis.native.path` is
    // set. On JDK 24+ that is a restricted operation; the launcher already
    // passes this flag to the server, and the test JVM must match it.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    providers.systemProperty("veltis.native.path").orNull?.let { value ->
        // A path handed to Gradle is written relative to the repository, but the
        // test JVM resolves a relative path against ITS working directory, which
        // is this project's directory — so `native/build/.../veltis_native.dll`
        // silently names `server/native/build/...`, the loader reports
        // "configured native library not found", and the bootstrap assertion
        // fails with no hint that the file exists a directory over. Anchor the
        // path to the repository root here, the one place that knows it.
        val anchored = if (value.isBlank()) {
            value
        } else {
            val named = File(value)
            (if (named.isAbsolute) named else rootProject.projectDir.resolve(named)).path
        }
        systemProperty("veltis.native.path", anchored)
    }
}
