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

val adventureVersion = "4.26.1"
val bungeeCordChatVersion = "1.21-R0.2-deprecated+build.21"
val annotationsVersion = "26.0.2"

dependencies {
    // Minecraft classes and libraries
    implementation(files(minecraftJar.canonicalPath))
    implementation(fileTree(minecraftLibsDir) { include("**/*.jar") })

    // Bukkit/Paper API dependencies (previously from :api module, using api() for transitive exposure)
    api("com.google.guava:guava:33.5.0-jre")
    api("com.google.code.gson:gson:2.13.2")
    api("org.yaml:snakeyaml:2.2")
    api("org.joml:joml:1.10.8") { isTransitive = false }
    api("it.unimi.dsi:fastutil:8.5.18")
    api("org.apache.logging.log4j:log4j-api:2.25.2")
    api("org.slf4j:slf4j-api:2.0.17")
    api("com.mojang:brigadier:1.3.10")
    api("net.md-5:bungeecord-chat:$bungeeCordChatVersion") {
        exclude("com.google.guava", "guava")
    }
    api(platform("net.kyori:adventure-bom:$adventureVersion"))
    api("net.kyori:adventure-api")
    api("net.kyori:adventure-text-minimessage")
    api("net.kyori:adventure-text-serializer-gson")
    api("net.kyori:adventure-text-serializer-legacy")
    api("net.kyori:adventure-text-serializer-plain")
    api("net.kyori:adventure-text-logger-slf4j")
    api("org.apache.maven:maven-resolver-provider:3.9.6")
    api("org.apache.maven.resolver:maven-resolver-connector-basic:1.9.18")
    api("org.apache.maven.resolver:maven-resolver-transport-http:1.9.18")
    compileOnly("org.jetbrains:annotations:$annotationsVersion")
    api("org.jspecify:jspecify:1.0.0")
    api("org.checkerframework:checker-qual:3.49.2")

    // Jansi for native Windows Unicode/ANSI console support
    implementation("org.fusesource.jansi:jansi:2.4.1")

    // Spark profiler dependencies (bundled, Paper-style)
    implementation("me.lucko:spark-paper:1.10.152")
    implementation("me.lucko:spark-api:0.1-20240720.200737-2")

    // Test dependencies
    testImplementation(project(":runtime"))

    // Moonrise dependencies (internal, not exposed transitively)
    implementation("ca.spottedleaf:concurrentutil:0.0.10")
    implementation("ca.spottedleaf:yamlconfig:1.2.0")
    implementation("net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7")
    implementation("io.github.llamalad7:mixinextras-fabric:0.4.1")
}

// Exclude client-side files that reference net.minecraft.client.* (not in server jar)
// and clothconfig2-dependent GUI files
sourceSets {
    main {
        java {
            exclude("ca/spottedleaf/moonrise/common/config/ui/ConfigWalker.java")
            exclude("ca/spottedleaf/moonrise/common/config/MoonriseConfigScreen.java")
            exclude("ca/spottedleaf/moonrise/patches/render/VisibilityGraph.java")
            exclude("ca/spottedleaf/moonrise/patches/command/MoonriseCommand.java")
            exclude("ca/spottedleaf/moonrise/mixin/starlight/multiplayer/ClientPacketListenerMixin.java")
            exclude("ca/spottedleaf/moonrise/mixin/serverlist/ServerSelectionListMixin.java")
            exclude("ca/spottedleaf/moonrise/mixin/serverlist/ServerAddressResolverMixin.java")
            exclude("ca/spottedleaf/moonrise/mixin/serverlist/ClientConnectionMixin.java")
            exclude("ca/spottedleaf/moonrise/mixin/render/SectionRenderDispatcherMixin.java")
            exclude("ca/spottedleaf/moonrise/mixin/profiler/MinecraftMixin.java")
            exclude("ca/spottedleaf/moonrise/mixin/config/MinecraftMixin.java")
            exclude("ca/spottedleaf/moonrise/mixin/collisions/FluidRendererMixin.java")
            exclude("ca/spottedleaf/moonrise/mixin/collisions/ParticleMixin.java")
            exclude("ca/spottedleaf/moonrise/mixin/chunk_system/OptionsMixin.java")
            exclude("ca/spottedleaf/moonrise/mixin/chunk_system/ClientLevelMixin.java")
            exclude("ca/spottedleaf/moonrise/mixin/command/CommandsMixin.java")
        }
    }
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
