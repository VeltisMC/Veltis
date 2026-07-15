plugins {
    id("application")
    id("java-library")
}

description = "VeltisMC Builder - Standalone build tool for producing veltismc-server.jar"

application {
    mainClass = "org.veltismc.veltis.builder.VeltisBuilder"
}

dependencies {
    implementation(project(":patch-engine"))
    implementation(project(":build-tools"))
    implementation("com.google.code.gson:gson:2.12.1")
    implementation("info.picocli:picocli:4.7.6")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")
}

tasks.withType<Jar> {
    manifest {
        attributes(
            "Main-Class" to "org.veltismc.veltis.builder.VeltisBuilder",
            "Multi-Release" to "true"
        )
    }
}

val uberJar = tasks.register("uberJar", Jar::class) {
    dependsOn(":patch-engine:jar", ":build-tools:jar")
    archiveBaseName.set("veltis-builder")
    archiveClassifier.set("")
    archiveVersion.set("1.0")
    manifest {
        attributes(
            "Main-Class" to "org.veltismc.veltis.builder.VeltisBuilder",
            "Multi-Release" to "true"
        )
    }
    val deps = configurations.runtimeClasspath.get()
        .filter { it.name.endsWith(".jar") }
        .map { zipTree(it) }
    from(deps)
    from(sourceSets.main.get().output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
