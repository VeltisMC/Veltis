plugins {
    id("java-library")
}

description = "VeltisMC Patch Engine - Mojang acquisition, decompile and patch application"

dependencies {
    // Vineflower: the one decompiler VeltisMC uses.
    implementation("org.vineflower:vineflower:1.12.0")
    // JSON parsing for Mojang's version manifest and version metadata.
    implementation("com.google.code.gson:gson:2.14.0")
    // Access widening. The runtime bootstrap decompiles the widened jar, so this
    // is not a build-time-only concern: `java -jar veltismc.jar` on a machine
    // with no workspace has to widen the jar it just downloaded before it can
    // read it. Keeping it in the engine is what lets the launcher and the Gradle
    // pipeline run one implementation instead of two copies that can drift.
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-commons:9.10.1")
    // The one logging system VeltisMC uses (Log4j2 — the same one Minecraft uses).
    // api() so launcher and server can call LogManager too. log4j-core
    // is api() rather than runtime-only because VeltisConsole installs VeltisMC's
    // configuration programmatically, and that needs Configurator; Minecraft ships
    // its own log4j-core, so this guarantees the API is present either way.
    api("org.apache.logging.log4j:log4j-api:2.26.0")
    api("org.apache.logging.log4j:log4j-core:2.26.0")
    testRuntimeOnly("org.apache.logging.log4j:log4j-jul:2.25.2")
}

// GradlePipelineTest reads the packaged jar rather than the packaging script,
// because the script says what was asked for and the jar says what happened —
// and those have differed before, in ways no green build noticed (a `from(task)`
// that contributed nothing; a `from(directory)` that put the patch files one
// directory above where the index said they were). The dependency makes the
// artifact exist before the assertions that read it.
tasks.test {
    dependsOn(rootProject.tasks.named("packageVeltisMC"))
}

// Repeatable patch-pipeline benchmark (discovery / parsing / application / I/O).
// Not attached to `build`. Run:
//   ./gradlew :patch-engine:benchmark                       -> all scenarios
//   ./gradlew :patch-engine:benchmark -PbenchArgs="scale"   -> one scenario
//   ./gradlew :patch-engine:benchmark -Pjfr                  -> also record a JFR profile
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Benchmarks the patch pipeline (discovery, parsing, application, I/O)."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "org.veltismc.patchengine.PatchBench"
    workingDir = rootProject.projectDir
    val extraArgs =
        if (project.hasProperty("benchArgs")) project.property("benchArgs").toString().split(" ")
        else listOf("all")
    args = listOf(rootProject.projectDir.absolutePath) + extraArgs
    if (project.hasProperty("jfr")) {
        val recording = layout.buildDirectory.file("patchbench.jfr").get().asFile
        jvmArgs(
            "-XX:StartFlightRecording=filename=$recording,settings=profile,dumponexit=true"
        )
    }
}
