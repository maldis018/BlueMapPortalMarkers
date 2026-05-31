plugins {
    java
}

group = "dev.aldis"
version = "0.2.0-SNAPSHOT"

// Compile to Java 25 bytecode: paper-api 26.1.x publishes Gradle metadata
// requiring a JVM runtime of 25+, so the plugin must target 25 as well.
// We do NOT use a toolchain (the host only has JDK 26); `--release 25` lets
// the JDK 26 compiler emit Java 25-compatible classes.
tasks.withType<JavaCompile> {
    options.release.set(25)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.bluecolored.de/releases")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.66-stable")
    compileOnly("de.bluecolored:bluemap-api:2.7.8")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
