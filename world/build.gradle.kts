plugins {
    id("java-library")
}

description = "VeltisMC world simulation core plus its version-pinned NMS boundary"

val minecraftVersion = providers.gradleProperty("minecraftVersion")
    .orElse("26.3").get()
val minecraftJar = rootProject.projectDir.resolve("ver/$minecraftVersion/server-widened.jar")
val minecraftLibsDir = rootProject.projectDir.resolve("ver/$minecraftVersion/libraries")

// The engine core (`org.veltismc.world.*`, everything outside the `nms`
// subpackage) is pure JDK and must never import net.minecraft. The widened
// server jar below exists solely so `org.veltismc.world.nms` can bind the
// engine to a live ServerLevel.
dependencies {
    implementation(files(minecraftJar.canonicalPath))
    implementation(fileTree(minecraftLibsDir) { include("**/*.jar") })
}

tasks.named("compileJava") {
    dependsOn(":widenServerJarAccess")
    dependsOn(":downloadLibraries")
}

val patchedSourceDir = rootProject.projectDir.resolve("ver/$minecraftVersion/patched-source")
if (patchedSourceDir.isDirectory) {
    idea {
        module {
            sourceDirs = sourceDirs.plus(patchedSourceDir)
        }
    }
}
