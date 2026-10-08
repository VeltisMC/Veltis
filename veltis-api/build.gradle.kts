plugins {
    id("java-library")
}

repositories {
    mavenCentral()
}

dependencies {
    api("io.github.classgraph:classgraph:4.8.179")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25)) 
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}