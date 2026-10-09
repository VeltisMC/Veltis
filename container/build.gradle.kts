plugins {
    id("java-library")
}

description = "VeltisMC container: container management for players and entities"

dependencies {
    implementation(project(":model"))
    
    implementation("org.apache.logging.log4j:log4j-api:2.26.0")
}
