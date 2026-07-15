plugins {
    id("java-library")
}

description = "VeltisMC Build Tools"

repositories {
    maven("https://maven.fabricmc.net/")
}

dependencies {
    implementation("net.fabricmc:tiny-remapper:0.10.3")
    implementation("org.vineflower:vineflower:1.12.0")
    implementation("com.google.code.gson:gson:2.12.1")
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-commons:9.10.1")
}
