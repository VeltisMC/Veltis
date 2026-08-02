plugins {
    id("java-library")
}

description = "VeltisMC NMS Entrypoint"

val minecraftVersion = providers.gradleProperty("minecraftVersion")
    .orElse("26.2").get()

val minecraftJar = rootProject.projectDir.resolve("ver/$minecraftVersion/server-widened.jar")
val minecraftOriginalJar = rootProject.projectDir.resolve("ver/$minecraftVersion/server.jar")
val minecraftLibsDir = rootProject.projectDir.resolve("ver/$minecraftVersion/libraries")

logger.lifecycle("server.jar exists: ${minecraftJar.exists()} at ${minecraftJar.canonicalPath}")

val annotationsVersion = "26.0.2"

dependencies {
    // Minecraft classes and libraries
    implementation(files(minecraftJar.canonicalPath))
    implementation(fileTree(minecraftLibsDir) { include("**/*.jar") })

    // Jansi for native Windows Unicode/ANSI console support
    implementation("org.fusesource.jansi:jansi:2.4.1")

    compileOnly("org.jetbrains:annotations:$annotationsVersion")

    // Test dependencies
    testImplementation(project(":runtime"))
}

tasks.named("compileJava") {
    dependsOn(":widenServerJarAccess")
    dependsOn(":downloadLibraries")
}

// Debug: print compile classpath
tasks.register("printClasspath") {
    dependsOn(":extractServerJar")
    doLast {
        println("=== compileClasspath files ===")
        configurations["compileClasspath"].forEach { file ->
            println("  ${file.absolutePath}")
        }
        println("=== end ===")
    }
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
