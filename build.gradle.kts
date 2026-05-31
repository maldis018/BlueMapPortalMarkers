plugins {
    java
    // Shadow (GradleUp fork) to bundle + relocate bStats into the plugin jar.
    // 9.3.1 is the first line compatible with the pinned Gradle 9.4 wrapper.
    id("com.gradleup.shadow") version "9.3.1"
}

group = "dev.aldis"
version = "0.3.0"

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

    // bStats: the ONLY bundled dependency. Shaded + relocated by shadowJar below;
    // the server does not provide it, so it must travel inside the plugin jar.
    implementation("org.bstats:bstats-bukkit:3.2.1")

    // gson is provided by the server at runtime (compileOnly for main, via paper-api),
    // but tests of PortalStore load/save/migration need it on the test classpath.
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// Single-source the plugin version: plugin.yml reads `version: '${version}'`,
// filled from the Gradle `version` above so the two can't drift.
tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

// The plain jar lacks bStats and is not the distributable. Disable it so the
// shaded jar is the single, canonically named output in build/libs (and the CI
// release glob can't accidentally pick up an unshaded jar). shadowJar builds
// from the compiled classes, not from the plain jar, so this is safe.
tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Bundle ONLY bStats — everything else (paper-api, bluemap-api, gson) is
    // provided by the server at runtime and must NOT be shaded in.
    dependencies {
        include(dependency("org.bstats:.*:.*"))
    }
    // Relocate so we don't clash with other plugins shading their own bStats.
    relocate("org.bstats", "dev.aldis.bluemapportalmarkers.bstats")
}

// `build` should produce the shaded, distributable jar.
tasks.build {
    dependsOn(tasks.shadowJar)
}
