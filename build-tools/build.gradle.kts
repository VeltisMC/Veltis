plugins {
    id("java-library")
}

description = "VeltisMC Build Tools - pipeline steps, access widening and the veltis-builder CLI"

dependencies {
    implementation(project(":patch-engine"))
    implementation("org.vineflower:vineflower:1.12.0")
    implementation("com.google.code.gson:gson:2.12.1")
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-commons:9.10.1")
    implementation("info.picocli:picocli:4.7.6")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")
    // The one logging system (Log4j2); api comes via patch-engine, core is
    // needed because each pipeline JavaExec runs in its own JVM and finds its
    // console-only log4j2.xml through normal classpath discovery.
    implementation("org.apache.logging.log4j:log4j-core:2.25.2")
    implementation("org.apache.logging.log4j:log4j-jul:2.25.2")
}
