plugins {
    kotlin("jvm")
    // @Serializable DTOs for Home Assistant JSON.
    kotlin("plugin.serialization")
    application
    // Self-contained fat JAR -> runnable with plain `java -jar` (handy for VPS deploy).
    id("com.gradleup.shadow")
}

// Versions aligned with what MCP Kotlin SDK 0.13.0 itself ships (Ktor 3.4.3,
// serialization/coroutines 1.11.0) so there is no transitive version conflict.
dependencies {
    // Official MCP Kotlin SDK (server + types + stdio/SSE transports).
    implementation("io.modelcontextprotocol:kotlin-sdk:0.13.0")
    // runBlocking + Job in main.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // asSource()/asSink() bridge System.in/out into the stdio transport.
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.0")
    // JsonObject building for tool schemas + HA payloads.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // .env loader for local secrets (real process env still takes precedence).
    implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")

    // Ktor HTTP client -> Home Assistant REST API.
    implementation("io.ktor:ktor-client-core:3.4.3")
    implementation("io.ktor:ktor-client-cio:3.4.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.4.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.3")

    // Ktor server engine for the optional HTTP/SSE transport (VPS deploy).
    implementation("io.ktor:ktor-server-cio:3.4.3")

    // SLF4J -> stderr, so stdout stays clean for the MCP protocol over stdio.
    implementation("org.slf4j:slf4j-simple:2.0.18")

    // Unit tests: kotlin-test (JUnit5 platform), coroutine test scope, Ktor MockEngine.
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.ktor:ktor-client-mock:3.4.3")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("MainKt")
}

// Keep stdout reserved for protocol output; force UTF-8 so non-ASCII isn't
// mangled by the platform default charset (CP866 on a Russian Windows console).
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
