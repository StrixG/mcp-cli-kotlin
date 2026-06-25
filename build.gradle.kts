plugins {
    kotlin("jvm") version "2.4.0"
    application
    // Builds a self-contained fat JAR (all deps bundled) -> runnable with plain `java -jar`,
    // no Gradle needed at runtime.
    id("com.gradleup.shadow") version "9.4.2"
}

repositories {
    mavenCentral()
}

dependencies {
    // Official MCP Kotlin SDK (client + types + stdio transport)
    implementation("io.modelcontextprotocol:kotlin-sdk:0.13.0")
    // Coroutines: runBlocking in main
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // asSource()/asSink() extensions used to bridge the subprocess streams
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.0")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("MainKt")
}

// Pass program args straight through; keep stdout clean for protocol output.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    // Force UTF-8 I/O so non-ASCII output isn't garbled by the platform default
    // charset (CP866 on Russian Windows consoles).
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
