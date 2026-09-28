plugins {
    id("java-library")
}

description = "VeltisMC NMS entrypoint: vanilla main + console/log setup"

val minecraftVersion = providers.gradleProperty("minecraftVersion")
    .orElse("26.3").get()

val minecraftJar = rootProject.projectDir.resolve("ver/$minecraftVersion/server-widened.jar")
val minecraftLibsDir = rootProject.projectDir.resolve("ver/$minecraftVersion/libraries")

dependencies {
    // Minecraft classes and libraries
    implementation(files(minecraftJar.canonicalPath))
    implementation(fileTree(minecraftLibsDir) { include("**/*.jar") })

    // Veltis server framework; Main reaches VeltisBootstrap reflectively so the
    // boot path stays identical to the one the patches use.
    implementation(project(":runtime"))

    // Console/logging bootstrap (VeltisConsole) shared with the launcher:
    // Log4j2 configuration plus UTF-8/ANSI console setup — no Jansi, which on
    // JDK 26 prints restricted native-access warnings that cannot be fixed
    // from application code (Jansi 2.4.1 is the latest release).
    implementation(project(":patch-engine"))

    // The Log4j2 stack whose JUL manager the console setup installs.
    implementation("org.apache.logging.log4j:log4j-jul:2.25.2")
    implementation("org.apache.logging.log4j:log4j-api:2.25.2")
    implementation("org.apache.logging.log4j:log4j-core:2.25.2")
}

tasks.named("compileJava") {
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
