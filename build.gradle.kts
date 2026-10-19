plugins {
    java
}

group = "dev.cody"
version = "0.1.0"
description = "Verified resource-pack download failover for Paper servers"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

val paperApiVersion = "26.2.build.134-stable"
val packetEventsVersion = "2.14.0"
val junitVersion = "6.1.3"

dependencies {
    // Paper 26.2 API (Java 25 bytecode). Provided by the server at runtime.
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    // PacketEvents 2.x Spigot API. The PacketEvents plugin is installed on the server.
    compileOnly("com.github.retrooper:packetevents-spigot:$packetEventsVersion")

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveBaseName = "PackFailover"
    manifest {
        attributes(
            "Implementation-Title" to "PackFailover",
            "Implementation-Version" to project.version,
            "Built-With-JDK" to "25",
            "Target-Paper" to "26.2"
        )
    }
}
