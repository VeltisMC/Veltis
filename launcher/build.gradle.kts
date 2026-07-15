plugins {
    id("application")
}

description = "VeltisMC Launcher"

application {
    mainClass = "org.veltismc.veltis.launcher.VeltisLauncher"
}

dependencies {
    implementation(project(":patch-engine"))
    // Gson for JSON parsing (version manifest, library metadata)
    implementation("com.google.code.gson:gson:2.12.1")
    // Jansi for native Windows Unicode/ANSI console support
    implementation("org.fusesource.jansi:jansi:2.4.1")
}

tasks.register("uberJar", Jar::class) {
    dependsOn(":patch-engine:jar", ":server:jar", ":runtime:jar")
    archiveBaseName.set("veltismc")
    archiveClassifier.set("")
    archiveVersion.set("1.0")

    manifest {
        attributes(
            "Main-Class" to "org.veltismc.veltis.launcher.VeltisLauncher",
            "Multi-Release" to "true"
        )
    }

    // 1. Patch-engine and launcher own dependencies
    val deps = configurations.runtimeClasspath.get()
        .filter { it.name.endsWith(".jar") }
        .map { zipTree(it) }
    from(deps)
    from(sourceSets.main.get().output)

    // 1b. Bundle patch files for runtime application
    from(project(":server").projectDir.resolve("patches")) {
        into("patches")
        include("**/*.patch")
    }

    // 2. Server module classes (Main.class, API, Moonrise)
    from(zipTree(project(":server").tasks.named("jar").map { (it as Jar).archiveFile.get().asFile }))
    // 3. Runtime module classes (VeltisBootstrap, plugin system)
    from(zipTree(project(":runtime").tasks.named("jar").map { (it as Jar).archiveFile.get().asFile }))

    // 4. Server module runtime dependencies (excluding Minecraft server jar and its libraries)
    from(project(":server").configurations.runtimeClasspath.map { config ->
        config.files
            .filter { f -> !f.absolutePath.contains(File.separator + "ver" + File.separator) }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/SIG-*")
    }

    // 5. Runtime module runtime dependencies (log4j-jul, toml4j, etc. — same filter)
    from(project(":runtime").configurations.runtimeClasspath.map { config ->
        config.files
            .filter { f -> !f.absolutePath.contains(File.separator + "ver" + File.separator) }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/SIG-*")
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.LIST")
}
