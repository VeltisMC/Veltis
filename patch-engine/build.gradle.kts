plugins {
    id("java-library")
}

description = "VeltisMC Patch Engine - Runtime patch application & jar building"

repositories {
    maven("https://maven.fabricmc.net/")
}

dependencies {
    // Vineflower decompiler
    implementation("org.vineflower:vineflower:1.12.0")
    // Tiny Remapper for jar manipulation
    implementation("net.fabricmc:tiny-remapper:0.10.3")
    // JSON parsing for version manifest
    implementation("com.google.code.gson:gson:2.12.1")
}
