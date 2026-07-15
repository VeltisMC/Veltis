import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    java
    idea
}

val asmVersion = "9.10.1"

subprojects {
    configurations.all {
        resolutionStrategy {
            force(
                "org.ow2.asm:asm:$asmVersion",
                "org.ow2.asm:asm-commons:$asmVersion",
                "org.ow2.asm:asm-tree:$asmVersion",
                "org.ow2.asm:asm-analysis:$asmVersion",
                "org.ow2.asm:asm-util:$asmVersion"
            )
        }
    }
}

group = "org.veltismc.veltis"
version = "1.0.0-SNAPSHOT"

// ---------------------------------------------------------------------------
// Minecraft pipeline configuration
// ---------------------------------------------------------------------------
val minecraftVersion = providers.gradleProperty("minecraftVersion")
    .orElse("26.2").get()
val verDir = layout.projectDirectory.dir("ver/${minecraftVersion}")
val serverPatchesDir = layout.projectDirectory.dir("server/patches")
val apiPatchesDir = layout.projectDirectory.dir("patches/api")
val minecraftSourceDir = verDir.dir("minecraft-source")
val patchedSourceDir = verDir.dir("patched-source")
val minecraftClassesDir = verDir.dir("classes")
val minecraftLibDir = verDir.dir("libraries")
val pipelineClasspath by configurations.creating

fun patchedMinecraftSourceFiles(): List<File> {
    return listOf(serverPatchesDir.asFile, apiPatchesDir.asFile)
        .asSequence()
        .filter { it.isDirectory }
        .flatMap { patchDir ->
            patchDir.walkTopDown()
                .filter { it.isFile && it.extension == "patch" }
                .flatMap { patchFile ->
                    patchFile.readLines()
                        .asSequence()
                        .filter { it.startsWith("+++ b/") && it.endsWith(".java") }
                        .map { it.removePrefix("+++ b/").trim() }
                        .filter { it != "/dev/null" }
                        .map { path ->
                            patchedSourceDir.asFile.resolve(
                                path.replace('/', File.separatorChar))
                        }
                }
        }
        .distinct()
        .filter { it.isFile }
        .toList()
}

// ---------------------------------------------------------------------------
// Allprojects / Subprojects
// ---------------------------------------------------------------------------
allprojects {
    group = rootProject.group
    version = rootProject.version
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "java-library")
    apply(plugin = "idea")

    java.toolchain.languageVersion.set(JavaLanguageVersion.of(26))

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(26)
    }

    // Modules that need the Mojang-mapped server jar at compile time
    val minecraftModules = setOf("server", "runtime")
    if (name in minecraftModules) {
        tasks.named("compileJava") {
            dependsOn(":extractServerJar")
        }
    }

    idea {
        module {
            excludeDirs = excludeDirs + setOf(file(".gradle"), file("build"), file("ver"))
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    repositories {
        mavenCentral()
        maven("https://libraries.minecraft.net/")
        maven("https://maven.fabricmc.net/")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.spongepowered.org/repository/maven-public/")
        maven("https://jitpack.io")
        maven("https://repo.lucko.me/")
    }

    dependencies {
        testImplementation(platform("org.junit:junit-bom:5.12.1"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }
}

repositories {
    mavenCentral()
    maven("https://libraries.minecraft.net/")
    maven("https://maven.fabricmc.net/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    pipelineClasspath(project(":build-tools"))
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "org.veltismc.veltis.launcher.VeltisLauncher"
        )
    }
}

// ---------------------------------------------------------------------------
// Pipeline helper: register and configure a JavaExec pipeline task
// ---------------------------------------------------------------------------
fun pipelineTask(
    name: String,
    step: String,
    vararg deps: Any
): TaskProvider<JavaExec> {
    return tasks.register<JavaExec>(name) {
        description = "Minecraft build pipeline: $step"
        group = "minecraft"
        classpath = pipelineClasspath
        mainClass = "org.veltismc.veltis.buildtools.PipelineRunner"
        args(step, minecraftVersion, rootDir.absolutePath)
        dependsOn(":build-tools:classes")
        dependsOn(deps.toList())
    }
}

// ---------------------------------------------------------------------------
// Phase 1: Download & Remap
// ---------------------------------------------------------------------------
val downloadMinecraft = pipelineTask("downloadMinecraft", "downloadMinecraft")
val downloadMappings = pipelineTask("downloadMappings", "downloadMappings")

// ---------------------------------------------------------------------------
// Phase 2: Minecraft Library downloads
// ---------------------------------------------------------------------------
val downloadLibraries = pipelineTask(
    "downloadLibraries", "downloadLibraries",
    downloadMinecraft
)

// ---------------------------------------------------------------------------
// Phase 3: Decompile & Source Workspace
// ---------------------------------------------------------------------------
val decompileMinecraft = pipelineTask(
    "decompileMinecraft", "decompileMinecraft",
    downloadMinecraft, downloadLibraries
)

val generateSourceWorkspace = pipelineTask(
    "generateSourceWorkspace", "generateSourceWorkspace",
    decompileMinecraft
)

// ---------------------------------------------------------------------------
// Phase 4: Patch Engine
// ---------------------------------------------------------------------------
val applyPatches = pipelineTask(
    "applyPatches", "applyPatches",
    generateSourceWorkspace
)

val rebuildPatches = pipelineTask(
    "rebuildPatches", "rebuildPatches"
)

// ---------------------------------------------------------------------------
// Phase 5: Compilation
// ---------------------------------------------------------------------------

// Minecraft library classpath as a file tree (lazy)
val minecraftClasspath: FileCollection = files(provider {
    if (minecraftLibDir.asFile.exists()) {
        minecraftLibDir.asFile.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".jar") }
            .toList()
    } else {
        emptyList()
    }
})

val patchedMinecraftSources: FileCollection = files(provider {
    patchedMinecraftSourceFiles()
})

// Compile only patched Minecraft sources. The original server jar supplies
// unchanged vanilla classes, avoiding javac errors from uncompilable decompile
// artifacts outside the patch set.
val compileMinecraft = tasks.register<JavaCompile>("compileMinecraft") {
    description = "Compiles patched Minecraft source files"
    group = "minecraft"
    dependsOn(applyPatches, downloadLibraries, extractServerJar)

    source(patchedMinecraftSources)
    classpath = files(verDir.file("server.jar")) + minecraftClasspath
    destinationDirectory = minecraftClassesDir

    options.release.set(26)
    options.encoding = "UTF-8"
    options.isFork = true

    doFirst {
        val outputDir = minecraftClassesDir.asFile
        if (outputDir.exists()) {
            outputDir.deleteRecursively()
        }
        outputDir.mkdirs()
    }

    onlyIf {
        patchedMinecraftSourceFiles().isNotEmpty()
    }
}

// packageMinecraft disabled: server jar is built at runtime by the launcher.
// Uncomment for dev/testing only. See packageVeltisMC for the production task.

val packageVeltisMC = tasks.register<Copy>("packageVeltisMC") {
    description = "Packages the EULA-compliant launcher jar (no Minecraft code)"
    group = "minecraft"
    dependsOn(":launcher:uberJar")

    // Copy launcher jar (contains NO Minecraft code)
    from(project(":launcher").layout.buildDirectory.file("libs/veltismc-1.0.jar"))
    into(layout.buildDirectory.dir("distributions"))
    rename { "veltismc.jar" }

    doLast {
        logger.lifecycle(
            "veltismc.jar: ${layout.buildDirectory.file("distributions/veltismc.jar").get().asFile.absolutePath}")
    }
}

// ---------------------------------------------------------------------------
// Phase 5: Patch Verification
// ---------------------------------------------------------------------------
val verifyPatches = pipelineTask(
    "verifyPatches", "verifyPatches",
    downloadMinecraft, downloadMappings
)

// ---------------------------------------------------------------------------
// Phase 5b: VeltisBuilder (standalone jar build)
// ---------------------------------------------------------------------------
val buildServer = tasks.register<JavaExec>("buildServer") {
    description = "Builds veltismc-server.jar using VeltisBuilder"
    group = "minecraft"
    mainClass = "org.veltismc.veltis.builder.VeltisBuilder"
    classpath = project(":builder").sourceSets.main.get().runtimeClasspath
    args("build", "--home", rootDir.absolutePath, "--version", minecraftVersion)
    dependsOn(":builder:classes")
    dependsOn(":downloadMinecraft", ":downloadLibraries")
}

val validateBuild = tasks.register<JavaExec>("validateBuild") {
    description = "Validate the build cache"
    group = "minecraft"
    mainClass = "org.veltismc.veltis.builder.VeltisBuilder"
    classpath = project(":builder").sourceSets.main.get().runtimeClasspath
    args("validate", "--home", rootDir.absolutePath, "--version", minecraftVersion)
    dependsOn(":builder:classes")
}

val cleanBuild = tasks.register<JavaExec>("cleanBuild") {
    description = "Clean build artifacts"
    group = "minecraft"
    mainClass = "org.veltismc.veltis.builder.VeltisBuilder"
    classpath = project(":builder").sourceSets.main.get().runtimeClasspath
    args("clean", minecraftVersion, "--home", rootDir.absolutePath)
    dependsOn(":builder:classes")
}

// ---------------------------------------------------------------------------
// Phase 6: Full Pipeline
// ---------------------------------------------------------------------------
tasks.register("buildVeltisMC") {
    description = "Builds the VeltisMC launcher (EULA-compliant runtime patching)"
    group = "minecraft"
    dependsOn(
        downloadMinecraft,
        downloadLibraries,
        packageVeltisMC
    )
    doLast {
        logger.lifecycle("=== VeltisMC build pipeline complete ===")
    }
}

// ---------------------------------------------------------------------------
// Extract the Mojang-mapped server jar from the bundler format for IDE support
// ---------------------------------------------------------------------------
val extractServerJar by tasks.registering {
    description = "Extracts the Mojang-mapped server jar from the bundler format"
    group = "minecraft"
    val bundlerJar = file("runtime/minecraft/$minecraftVersion/server.jar")
    val outputJar = verDir.file("server.jar").asFile
    outputs.file(outputJar)
    // Bundler jar may not exist yet (downloaded at runtime by the launcher);
    // skip this task if absent rather than failing at configuration time.
    onlyIf { bundlerJar.exists() }
    doLast {
        outputJar.parentFile.mkdirs()
        val fs = FileSystems.newFileSystem(bundlerJar.toPath(), null as ClassLoader?)
        try {
            val innerPath = fs.getPath("/META-INF/versions/$minecraftVersion/server-$minecraftVersion.jar")
            Files.copy(innerPath, outputJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            fs.close()
        }
        logger.lifecycle("Extracted Mojang-mapped server jar to $outputJar")
    }
}

// ---------------------------------------------------------------------------
// Access Widener: widen Minecraft server jar access for Moonrise compilation
// ---------------------------------------------------------------------------
val widenServerJarAccess by tasks.registering(JavaExec::class) {
    description = "Widens class/field/method access in Mojang-mapped server jar"
    group = "minecraft"
    val inputJar = verDir.file("server.jar").asFile
    val outputJar = verDir.file("server-widened.jar").asFile
    outputs.file(outputJar)
    classpath = pipelineClasspath
    mainClass = "org.veltismc.veltis.buildtools.AccessWidener"
    args(inputJar.absolutePath, outputJar.absolutePath)
    dependsOn(":build-tools:classes")
    dependsOn(":extractServerJar")
    // Input may not exist yet; only run when extractServerJar produces it
    onlyIf { inputJar.exists() }
    doLast { logger.lifecycle("Widened access: ${outputJar.absolutePath}") }
}

// ---------------------------------------------------------------------------
// Ensure patch directories exist
// ---------------------------------------------------------------------------
tasks.register("ensurePatchDirs") {
    doLast {
        serverPatchesDir.asFile.mkdirs()
        apiPatchesDir.asFile.mkdirs()
    }
}
