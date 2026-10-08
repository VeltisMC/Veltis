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
    implementation(project(":veltis-api"))
    api("io.github.classgraph:classgraph:4.8.179")

    // Console/logging bootstrap (VeltisConsole) shared with the launcher:
    // Log4j2 configuration plus UTF-8/ANSI console setup — no Jansi, which on
    // JDK 26 prints restricted native-access warnings that cannot be fixed
    // from application code (Jansi 2.4.1 is the latest release).
    implementation(project(":patch-engine"))

    // The Log4j2 stack whose JUL manager the console setup installs, and the
    // same API Minecraft's own code uses, so Veltis messages and Minecraft
    // messages share one format.
    implementation("org.apache.logging.log4j:log4j-api:2.26.0")
    implementation("org.apache.logging.log4j:log4j-jul:2.26.0")

    // The world engine's configuration and the framework's own parsing.
    implementation("org.yaml:snakeyaml:2.4")
    implementation("com.google.code.gson:gson:2.14.0")

    // The class-file parser the runtime delta applier needs: applying a patch
    // set splices `ClassDelta` payloads onto the verified vanilla classes, and
    // that splice parses both sides with ASM's tree API. Declared here rather
    // than inherited through `:patch-engine` so the start-up classpath this
    // build assembles for the server names its own requirement: these two are
    // in the bootstrap table the launcher preflights, not in veltismc.jar.
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-tree:9.10.1")

    // Tests run with a real Log4j2 context (see src/test/resources/log4j2.xml).
    testRuntimeOnly("org.apache.logging.log4j:log4j-core:2.26.0")
    testRuntimeOnly("org.apache.logging.log4j:log4j-jul:2.26.0")
}
