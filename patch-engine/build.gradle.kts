plugins {
    id("java-library")
}

description = "VeltisMC Patch Engine - Runtime patch application & jar building"

dependencies {
    // Vineflower decompiler (used by RuntimeDecompiler)
    implementation("org.vineflower:vineflower:1.12.0")
    // JSON parsing for version manifest
    implementation("com.google.code.gson:gson:2.12.1")
    // The one logging system VeltisMC uses (Log4j2 — the same one Minecraft
    // uses). api() so launcher, server and build-tools can call LogManager too.
    api("org.apache.logging.log4j:log4j-api:2.25.2")
    // Tests and the benchmark run configure Log4j2 through a plain log4j2.xml.
    testRuntimeOnly("org.apache.logging.log4j:log4j-core:2.25.2")
    testRuntimeOnly("org.apache.logging.log4j:log4j-jul:2.25.2")
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
