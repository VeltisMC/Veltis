plugins {
    id("java-library")
}

description = "VeltisMC server framework: bootstrap, containers, events, lifecycle, scheduler, config"

// Pure application code: the world simulation comes from :world and everything
// else is vanilla JDK plus config/JSON parsing. The Minecraft jar is deliberately
// NOT on this classpath — NMS access lives in server/ and world's nms subpackage.
dependencies {
    implementation(project(":world"))
    implementation("org.yaml:snakeyaml:2.4")
    implementation("com.google.code.gson:gson:2.12.1")
    // The one logging system (Log4j2) — the same API Minecraft's own code uses,
    // so Veltis runtime messages and Minecraft messages share one format.
    implementation("org.apache.logging.log4j:log4j-api:2.25.2")
    // Tests run with a real Log4j2 context (see src/test/resources/log4j2.xml).
    testRuntimeOnly("org.apache.logging.log4j:log4j-core:2.25.2")
    testRuntimeOnly("org.apache.logging.log4j:log4j-jul:2.25.2")
}
