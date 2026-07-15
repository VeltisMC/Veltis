import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("java-library")
}

description = "VeltisMC Runtime"

val minecraftVersion = providers.gradleProperty("minecraftVersion")
    .orElse("26.2").get()
val minecraftJar = rootProject.projectDir.resolve("ver/$minecraftVersion/server-widened.jar")
val minecraftLibsDir = rootProject.projectDir.resolve("ver/$minecraftVersion/libraries")

dependencies {
    implementation(project(":server"))
    implementation(project(":build-tools"))
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-commons:9.10.1")
    implementation("org.yaml:snakeyaml:2.4")
    implementation("com.google.code.gson:gson:2.12.1")
    implementation("com.moandjiezana.toml:toml4j:0.7.2")
    implementation("org.apache.logging.log4j:log4j-jul:2.25.2")
    implementation("org.apache.logging.log4j:log4j-api:2.25.2")
    implementation("org.apache.logging.log4j:log4j-core:2.25.2")
    implementation("org.fusesource.jansi:jansi:2.4.1")
    implementation("org.apache.commons:commons-lang3:3.19.0")
    implementation("commons-codec:commons-codec:1.19.0")
    // Minecraft classes (for direct imports like VeltisBootstrap)
    implementation(files(minecraftJar.canonicalPath))
    implementation(fileTree(minecraftLibsDir) { include("**/*.jar") })
}

tasks.named("compileJava") {
    dependsOn(":extractServerJar")
    dependsOn(":widenServerJarAccess")
    dependsOn(":downloadLibraries")
}

// Attach patched Minecraft source for IDE navigation/autocomplete
val patchedSourceDir = rootProject.projectDir.resolve("ver/$minecraftVersion/patched-source")
if (patchedSourceDir.isDirectory) {
    idea {
        module {
            sourceDirs = sourceDirs.plus(patchedSourceDir)
        }
    }
}
