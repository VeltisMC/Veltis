import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile

plugins {
    id("application")
}

description = "VeltisMC Launcher"

application {
    mainClass = "org.veltismc.launcher.VeltisLauncher"
}

dependencies {
    // VeltisConsole: Log4j2 configuration plus the UTF-8 console setup, shared
    // with :server and the build pipeline. Nothing else from the patch engine is
    // reachable from a launcher jar — it does not download, decompile or patch.
    implementation(project(":patch-engine"))
    // The Log4j2 core whose configuration VeltisConsole installs, plus the JUL
    // bridge the server installs its manager from. Declared here so a standalone
    // `gradlew :launcher:run` logs exactly like the packaged uber jar.
    //
    // Both at Mojang's own versions, not whatever is newest: the packaged jar
    // puts these on the system classpath from `libraries/` while Minecraft's
    // launcher-provided copies sit on the server's classpath behind it, and two
    // versions of log4j-core on one hierarchy is a warning at best. The build
    // reads those versions from metadata/libraries.txt, so a Minecraft upgrade
    // that moves them is a build failure here rather than a skew discovered at
    // runtime.
    implementation("org.apache.logging.log4j:log4j-core:2.26.0")
    implementation("org.apache.logging.log4j:log4j-jul:2.26.0")
}

// ---------------------------------------------------------------------------
// The jar's library table
//
// veltismc.jar contains no third-party code. What it does contain is a table
// naming the few libraries it cannot start without, with a URL and a SHA-1 for
// each, and a manifest Class-Path pointing at the same files. Stage 0 of the
// launcher reads that table before any Log4j class is named, fetches what is
// missing beside the jar, verifies what is there, and only then lets the rest
// of the start-up run against libraries it has proved are intact.
//
// One file decides both halves — the manifest entry the JVM follows and the
// row the preflight fetches — because two lists that have to agree are two
// lists that will one day not.
// ---------------------------------------------------------------------------

/**
 * The library repositories the table points at.
 *
 * These are named, not discovered, exactly as `MojangMetadata.VERSION_MANIFEST`
 * names the version manifest: the endpoint is a property of where a dependency
 * comes from, while the version, path and checksum of a particular jar are
 * properties of the build and are read rather than assumed.
 */
val mojangLibraryBase = "https://libraries.minecraft.net/"
val centralLibraryBase = "https://repo1.maven.org/maven2/"

/**
 * Everything `veltismc.jar` puts on the JVM's system classpath, and why.
 *
 * Seven entries, and the count is the point. Each one is reachable from the
 * server's own start-up path — Log4j because `VeltisConsole` configures it
 * before anything logs, Gson because `MojangMetadata` parses Mojang's version
 * document with it, the JUL bridge because the server installs Log4j's
 * `LogManager` as `java.util.logging.manager`, SnakeYAML because `VeltisConfig`
 * reads `veltis.yml`, and ASM because applying the bytecode patch set splices
 * class deltas onto the verified vanilla classes with `ClassDelta`, which parses
 * both sides to do it. Nothing else in the build's runtime classpath is used by
 * the running server, so nothing else is listed: Vineflower decompiles at build
 * time and `javax.tools` compiles at build time, and neither is present in the
 * jar at all.
 *
 * Mojang membership is not asserted here — it is read from
 * `metadata/libraries.txt`, the coordinate list the build derived from Mojang's
 * own version document. A library Mojang ships is fetched from Mojang and
 * cross-checked against the copy `:downloadLibraries` already verified; one it
 * does not is fetched from Maven Central and pinned to its checksum by this
 * build.
 */
val bootstrapCoordinates = listOf(
    // The logging facade every VeltisMC class calls into.
    "org.apache.logging.log4j:log4j-api",
    // The implementation that gets configured, before any of that logs.
    "org.apache.logging.log4j:log4j-core",
    // Mojang parses its version document with this; so does the launcher.
    "com.google.code.gson:gson",
    // `java.util.logging.manager`, which the server installs into a JVM that
    // would otherwise fall back to the JDK's SimpleLog formatter.
    "org.apache.logging.log4j:log4j-jul",
    // `veltis.yml`. Mojang does not publish this one.
    "org.yaml:snakeyaml",
    // The class-file reader and writer the delta applier parses both sides of
    // a splice with: vanilla class in, merged class out.
    "org.ow2.asm:asm",
    // The tree API that reader feeds; `ClassDelta` works on ClassNode/MethodNode.
    "org.ow2.asm:asm-tree",
)

fun sha1Of(file: java.io.File): String {
    val digest = MessageDigest.getInstance("SHA-1")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

/**
 * Where the table lands — the root build's generated-resources, because that is
 * the tree `uberJar` already packs `META-INF/veltis/` from, and the packaged
 * patch set and the recorded version live beside it there. One directory for
 * everything the jar carries under that prefix means nothing can be added to the
 * jar without someone deciding where it comes from.
 */
val bootstrapTableFile = rootProject.layout.buildDirectory
    .file("generated-resources/META-INF/veltis/bootstrap-libraries.txt")

val writeBootstrapLibraries by tasks.registering {
    group = "build"
    description = "Writes veltismc.jar's library table: path, URL and SHA-1 for every bootstrap library"

    val target = bootstrapTableFile
    val serverRuntime = project(":server").configurations.named("runtimeClasspath")
    val minecraftVersion = providers.gradleProperty("minecraftVersion").orElse("26.3").get()
    val mojangCoordinates = rootProject.layout.projectDirectory
        .file("minecraft/workspace/$minecraftVersion/metadata/libraries.txt")
    val mojangLibraries = rootProject.layout.projectDirectory
        .dir("minecraft/workspace/$minecraftVersion/libraries")

    inputs.files(serverRuntime).withPropertyName("resolvedBootstrapLibraries")
    inputs.property("bootstrapCoordinates", bootstrapCoordinates)
    inputs.file(mojangCoordinates).withPropertyName("mojangLibraryCoordinates")
    outputs.file(target).withPropertyName("bootstrapTable")

    // Ordering, not just up-to-date-ness: this reads metadata/libraries.txt and
    // Mojang's own copies of the jars, and :prepareVeltisRuntime declares the
    // whole workspace as its output — including the downloads, which it does for
    // itself rather than through the per-stage tasks. Depending on the producer
    // is what tells Gradle which of them to run first; depending on a per-stage
    // task would leave this reading a workspace nothing had committed to making.
    dependsOn(":prepareVeltisRuntime")

    doLast {
        val mojangDeclared = mojangCoordinates.asFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate {
                val parts = it.split(":")
                parts[0] + ":" + parts[1] to parts[2]
            }

        val resolved = HashMap<String, Pair<String, java.io.File>>()
        for (artifact in serverRuntime.get().incoming.artifacts.artifacts) {
            val id = artifact.id.componentIdentifier
            if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                resolved["${id.group}:${id.module}"] = id.version to artifact.file
            }
        }

        val rows = bootstrapCoordinates.map { coordinate ->
            val (version, file) = resolved[coordinate]
                ?: throw GradleException(
                    "The server start-up needs $coordinate but the build does"
                        + " not resolve it"
                        + "\n  Reason: veltismc.jar names it in its Class-Path and stages it"
                        + " beside itself, so it has to come from the build"
                        + "\n  Fix: declare it in server/build.gradle.kts"
                )
            val path = coordinate.substringBefore(':').replace('.', '/') + "/" +
                coordinate.substringAfter(':') + "/" + version + "/" +
                coordinate.substringAfter(':') + "-" + version + ".jar"
            val built = sha1Of(file)
            val declaredByMojang = mojangDeclared[coordinate]

            val url = if (declaredByMojang == null) {
                centralLibraryBase + path
            } else {
                val copy = File(mojangLibraries.asFile, path)
                if (!copy.isFile) {
                    throw GradleException(
                        "$coordinate does not match the version Mojang ships"
                            + "\n  Build resolves:  $coordinate:$version"
                            + "\n  Mojang publishes: $coordinate:$declaredByMojang"
                            + "\n  Reason: both end up on one classpath — the build's copy"
                            + " from libraries/ via the manifest, Mojang's from the server's"
                            + " classpath — and two versions of the same library are a"
                            + " failure the JVM reports as a class being wrong rather than"
                            + " as a mismatch"
                            + "\n  Fix: set it to $coordinate:$declaredByMojang in"
                            + " server/build.gradle.kts and patch-engine/build.gradle.kts"
                            + "\n  Looked for: " + copy.absolutePath
                    )
                }
                val fetched = sha1Of(copy)
                if (fetched != built) {
                    throw GradleException(
                        "The build's $coordinate is not the jar Mojang publishes"
                            + "\n  Path:     $path"
                            + "\n  Mojang:   $fetched"
                            + "\n  Build:    $built"
                            + "\n  Reason: the table pins one checksum for a file the"
                            + " launcher will verify on every start, so two candidates"
                            + " means one of them is wrong and neither can be trusted"
                            + "\n  Fix: clear the Gradle cache entry for $coordinate and"
                            + " rebuild, or check that the mirror at"
                            + " $mojangLibraryBase is serving the original"
                    )
                }
                mojangLibraryBase + path
            }
            Triple(path, url, built)
        }

        target.get().asFile.let { file ->
            file.parentFile.mkdirs()
            file.writeText(buildString {
                appendLine("# Generated by :writeBootstrapLibraries. One library per line:")
                appendLine("# path<TAB>url<TAB>sha1.")
                appendLine("#")
                appendLine("# This is the whole of veltismc.jar's Class-Path. Stage 0 of the")
                appendLine("# launcher reads it before it loads anything else, puts every file")
                appendLine("# listed here beside the jar under libraries/, and refuses to start")
                appendLine("# unless each one hashes to the value recorded on its row.")
                appendLine("#")
                appendLine("# The jar itself contains no third-party code, so these five files")
                appendLine("# are the only place a Log4j, Gson or SnakeYAML class comes from.")
                rows.forEach { (path, url, sha1) ->
                    append(path).append('\t').append(url).append('\t').append(sha1)
                    appendLine()
                }
            })
        }

        logger.lifecycle(
            "Wrote {} bootstrap libraries to {}",
            rows.size, target.get().asFile
        )
    }
}

/**
 * What `veltismc.jar` may contain, as a single question about one entry.
 *
 * An allowlist rather than a denylist, because a denylist can only ever refuse
 * the things it has been told about: a dependency that grows a new top-level
 * package, a nested jar, a `module-info.class` or an annotation processor
 * service file would pass unnoticed and ship. Refusing everything that is not
 * ours makes the answer to "is anything third-party in here" structural rather
 * than a claim about a list that has to be kept up to date.
 *
 * `org/veltismc/` is the whole of VeltisMC's own code across all three modules;
 * `META-INF/veltis/` is the packaged patch set, the recorded version and this
 * jar's library table; the two XML files are the Log4j configurations, one for
 * the pipeline and one for the server. Everything else — including
 * `META-INF/MANIFEST.MF` from an input jar, `META-INF/maven/`,
 * `META-INF/services/`, `META-INF/native-image/` and a root
 * `module-info.class` — belongs to whoever produced the jar it came from, and
 * they still have it.
 */
fun isOurs(name: String): Boolean =
    name.startsWith("org/veltismc/")
        || name.startsWith("META-INF/veltis/")
        || name == "log4j2.xml"
        || name == "veltis-log4j2.xml"

/**
 * The package roots `isOurs` protects, without the trailing slash.
 *
 * Needed because a directory element never matches `isOurs` on its own:
 * `org/veltismc/launcher` does not start with `org/veltismc/`. Without this,
 * excluding the directory prunes its whole subtree from the copy, which is how
 * the distributable once shipped with every one of its own classes missing.
 */
val oursRoots = listOf("org/veltismc", "META-INF/veltis")

/**
 * Our own classes that are build-time tools rather than runtime code.
 *
 * The distributable's job is to fetch Mojang's artifacts, apply the packaged
 * bytecode patch set, verify the result and start the server. The decompiler,
 * `javac`, the source patch engine, the access widener and the pipeline's own
 * main do that work inside the Gradle build — and `VeltisLauncher` says so out
 * loud when a `server/Shulker/` directory turns up beside the jar: there is no
 * pipeline here to run it with, so it refuses instead of starting a server that
 * quietly ignores the patches. Shipping the classes anyway would contradict
 * that claim in the one direction a reader cannot check by running anything:
 * the jar would contain a build.
 *
 * Matched on the simple class name, so an inner class (`X$Y`) goes with `X`.
 * `PatchCategory` is deliberately not here: the launcher executes it when it
 * looks for a `server/Shulker/` directory, before it can decide to refuse one.
 */
val developmentClasses = setOf(
    "AccessRequirements",     // the access widener's model of what to widen
    "AccessWidener",          // ASM-based widening; ASM is not in the jar
    "BytecodePatchGenerator", // cuts the bytecode patch set, build time
    "ClassDeltaGenerator",    // compares, builds and verifies class deltas; build time
    "DiffGenerator",          // source diffs for rebuildPatches
    "MinecraftDecompiler",    // Vineflower integration; Vineflower is not in the jar
    "ParsedPatch",            // source patch parsing
    "PatchDiscovery",         // Shulker/ scanning; its directory constant is inlined
    "PatchedSourceCompiler",  // javac integration
    "PatchFailure",           // source patch failure reporting
    "PatchRebuilder",         // the rebuildPatches half of the source workflow
    "PatchSet",               // source patch chain model
    "PatchStats",             // source patch statistics
    "PipelineRunner",         // the Gradle steps' main class
    "UnifiedDiffPatcher",     // applies text diffs
    "VeltisPatch",            // source patch model
    "VeltisPatcher",          // orchestrates the source patch chain
)

fun isDevelopmentClass(entry: String): Boolean {
    val simple = entry.substringAfterLast('/')
    return developmentClasses.any { simple == "$it.class" || simple.startsWith("$it\$") }
}

/**
 * Whether one entry may be copied into the distributable.
 *
 * Files answer strictly through `isOurs`, minus the build-time tools above.
 * Directories answer yes only when they are, or lead to, something `isOurs`
 * protects — a directory like `org/apache` must still be excluded (it would
 * otherwise land in the jar as an empty husk beside `org/veltismc`), while `org`
 * itself must be kept or nothing under it is ever visited.
 */
fun mayShip(entry: String, isDirectory: Boolean): Boolean {
    if (entry.isEmpty()) return true
    if (isOurs(entry)) return isDirectory || !isDevelopmentClass(entry)
    if (!isDirectory) return false
    val directory = entry.trimEnd('/')
    return oursRoots.any { it == directory || it.startsWith("$directory/") }
}

tasks.register("uberJar", Jar::class) {
    dependsOn(
        ":patch-engine:jar",
        ":server:jar",
        // The launcher cannot bootstrap itself without a patch set and the
        // version it was built for, so these are inputs to the jar, not to the
        // runtime that the jar later builds.
        ":publishBytecodePatch",
        ":writePackagedVersion",
        writeBootstrapLibraries
    )
    archiveBaseName.set("veltismc")
    archiveClassifier.set("")
    archiveVersion.set("1.0")

    manifest {
        attributes(
            "Main-Class" to "org.veltismc.launcher.VeltisLauncher",
            "Multi-Release" to "true",
            // Read at runtime as the Veltis version recorded in every packaged
            // veltis-server.jar, so the build number lives in one place instead
            // of a constant beside the thing it identifies.
            "Implementation-Version" to project.version.toString()
        )
    }

    // The patch set travels inside the jar, as class deltas with three hashes
    // on every index line. This is what removes the build step from the
    // operator's path: `java -jar server.jar` needs nothing on disk but the
    // jar, and the difference between it and vanilla Minecraft is in it.
    //
    // The development source patches under `server/Shulker/` deliberately are
    // not. They are how a contributor writes a change; a running server has no
    // decompiler to apply them with and no compiler to run the result through,
    // so packaging them would ship a second patch representation nobody can
    // use. One format ships, and it is the bytecode one.
    from(rootProject.layout.buildDirectory.dir("generated-resources")) {
        include("META-INF/veltis/**")
    }

    // 1. Patch-engine and launcher own dependencies
    val deps = configurations.runtimeClasspath.get()
        .filter { it.name.endsWith(".jar") }
        .map { zipTree(it) }
    from(deps)
    from(sourceSets.main.get().output)

    // 2. Everything that runs inside Minecraft's classloader: the NMS entrypoint,
    //    the Veltis framework and the world engine. One module, so one jar, so
    //    nothing here has to name three projects and the order between them.
    from(zipTree(project(":server").tasks.named("jar").map { (it as Jar).archiveFile.get().asFile }))

    // Minecraft's code and Mojang's libraries are produced by the pipeline and
    // reach the server at *runtime*, into the workspace, verified against
    // Mojang's hashes. :server carries them on its compile classpath, so those
    // entries have to be filtered out here or the "code-free" distribution jar
    // would ship Minecraft. The entry allowlist below would catch them anyway;
    // filtering the jar out first means not opening a 60 MB archive to find out.
    val minecraftWorkspace = rootProject.layout.projectDirectory
        .dir("minecraft").asFile.absolutePath + File.separator
    fun notMinecraftArtifact(file: File) = !file.absolutePath.startsWith(minecraftWorkspace)

    // The server module's own runtime dependencies. They are folded in as
    // classes rather than referenced as jars — see `isOurs` for what of them
    // survives that, and the dependency audit in CONTRIBUTING.md for why the
    // ones that do are the ones that do.
    from(project(":server").configurations.runtimeClasspath.map { config ->
        config.files.filter(::notMinecraftArtifact).map { zipTree(it) }
    }) {
        exclude("META-INF/MANIFEST.MF")
    }

    // Belt and braces on the same rule as `isOurs`, checked against the path as
    // well as the entry name: Minecraft's code and Mojang's libraries are
    // produced by the pipeline and reach the server at *runtime*, into the
    // workspace, verified against Mojang's hashes. :server carries them on its
    // compile classpath, so a dependency resolved from somewhere else (a Gradle
    // cache entry whose name happens to mention Minecraft, a vendor repo copy)
    // would slip past a path filter and ship Mojang's bytes in a jar VeltisMC
    // distributes — the one packaging error the user cannot recover from.
    val minecraftOwnedEntry = listOf(
        "net/minecraft/", "com/mojang/", "org/bukkit/", "org/spigotmc/",
        "META-INF/versions/", "assets/minecraft/",
    )
    exclude { element ->
        // The path, not the name: `element.name` is only the last segment
        // (`VeltisLauncher.class`), which can never satisfy `isOurs`' package
        // prefixes — filtering on it removed every class the jar was for.
        val entry = element.path.replace('\\', '/').trimEnd('/')
        val path = element.file.path.replace('\\', '/')
        val foreign = minecraftOwnedEntry.any { entry.contains(it) || path.contains(it) }
        foreign || !mayShip(entry, element.isDirectory)
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // The manifest's Class-Path is a property of the table above, so it is read
    // here rather than computed: one file decides both what the JVM is pointed
    // at and what stage 0 fetches, and two lists that have to agree are two
    // lists that will one day not. It cannot go in the manifest block because
    // the table does not exist until this task's dependencies have run.
    doFirst {
        val table = bootstrapTableFile.get().asFile
        if (!table.isFile) {
            throw GradleException(
                "The bootstrap library table is missing"
                    + "\n  Expected: " + table.absolutePath
                    + "\n  Reason: the manifest Class-Path is read from it, and a jar"
                    + " that points at libraries it never named cannot start"
                    + "\n  Fix: run :writeBootstrapLibraries"
            )
        }
        val entries = table.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString(" ") { row ->
                val path = row.split("\t").first()
                if (path.isBlank()) {
                    throw GradleException(
                        "The bootstrap library table has a row with no path"
                            + "\n  Row: " + row
                            + "\n  Fix: run :writeBootstrapLibraries again"
                    )
                }
                "libraries/" + path
            }
        logger.lifecycle("[VeltisDebug] uberJar doFirst: {} rows, Class-Path = {}",
            table.readLines().count { it.isNotBlank() && !it.trim().startsWith("#") }, entries)
        manifest.attributes(mapOf("Class-Path" to entries))
    }
}

/**
 * The content report for the distributable, and the gate that keeps it honest.
 *
 * Six numbers, four of them required to be zero. Classes and resources are
 * facts worth seeing — a count that jumps is a conversation. Nested jars,
 * development classes, test classes and bundled third-party libraries are
 * violations, because each is a way the "one jar, no build inside it" promise
 * fails silently: a nested jar nothing resolves, a decompiler class whose
 * library was left behind, a test fixture run in production, a bundled library
 * shadowing the one `libraries/` verifies by checksum.
 *
 * The gate is an allowlist question asked of every entry — the same question
 * `mayShip` answers while the jar is being written, asked again here of the
 * bytes that actually landed — so a package nobody has heard of fails under
 * "bundled third-party" without having been added to a list first. The two
 * answers disagreeing would itself be a failure, which is the point of asking
 * twice: once when writing, once about what was written.
 */
val verifyDistributableContent by tasks.registering {
    group = "verification"
    description = "Reports what veltismc.jar contains and fails on anything that must not ship"
    dependsOn(tasks.named("uberJar"))
    val distributable = tasks.named("uberJar", Jar::class).flatMap { it.archiveFile }
    inputs.file(distributable).withPropertyName("distributable")

    doLast {
        var classes = 0
        var resources = 0
        var nestedJars = 0
        var development = 0
        var tests = 0
        var thirdParty = 0
        val violations = mutableListOf<String>()

        ZipFile(distributable.get().asFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name.replace('\\', '/')
                if (name.endsWith("/")) continue
                val simple = name.substringAfterLast('/')
                if (name.endsWith(".jar")) {
                    nestedJars++
                    violations += "nested jar: $name"
                    continue
                }
                if (name.endsWith(".class")) {
                    classes++
                    when {
                        isDevelopmentClass(name) -> {
                            development++
                            violations += "development class: $name"
                        }
                        !isOurs(name) -> {
                            thirdParty++
                            violations += "bundled third-party class: $name"
                        }
                        simple.endsWith("Test.class") || simple.endsWith("Tests.class")
                            || simple.endsWith("Bench.class")
                            || simple.endsWith("Benchmark.class") -> {
                            tests++
                            violations += "test class: $name"
                        }
                    }
                    continue
                }
                resources++
                // The one entry isOurs does not claim by rule: the jar's own
                // manifest, written by the Jar task rather than copied from an
                // input. Every other resource has to be ours or be refused.
                if (!isOurs(name) && name != "META-INF/MANIFEST.MF") {
                    thirdParty++
                    violations += "bundled third-party resource: $name"
                }
            }
        }

        val file = distributable.get().asFile
        logger.lifecycle("Distributable content report: {}", file)
        logger.lifecycle("  Classes:                  {}", classes)
        logger.lifecycle("  Resources:                {}", resources)
        logger.lifecycle("  Nested JARs:              {}", nestedJars)
        logger.lifecycle("  Development classes:      {}", development)
        logger.lifecycle("  Test classes:             {}", tests)
        logger.lifecycle("  Bundled third-party:      {}", thirdParty)

        if (violations.isNotEmpty()) {
            throw GradleException(
                "The distributable must not ship build-only content"
                    + "\n  Jar: " + file
                    + violations.joinToString("") { "\n  - $it" }
                    + "\n  Reason: the jar is what an operator runs; every entry above"
                    + " is either dead weight, a test artifact, or code whose own"
                    + " libraries were deliberately left out of the classpath"
                    + "\n  Fix: keep development code out of the jar's inputs"
                    + " (mayShip in launcher/build.gradle.kts) or stop packaging it"
            )
        }
    }
}
