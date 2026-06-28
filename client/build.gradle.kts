plugins {
    kotlin("jvm")
    // @Serializable DTOs for the DeepSeek API.
    kotlin("plugin.serialization")
    application
    // Self-contained fat JAR -> runnable with plain `java -jar`.
    id("com.gradleup.shadow")
}

dependencies {
    // Official MCP Kotlin SDK (client + types + stdio transport).
    implementation("io.modelcontextprotocol:kotlin-sdk:0.13.0")
    // runBlocking in main.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // asSource()/asSink() bridge the subprocess streams into the stdio transport.
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.0")
    // JSON DTOs + tool schema building for the DeepSeek agent.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Ktor HTTP client -> DeepSeek chat/completions.
    implementation("io.ktor:ktor-client-core:3.4.3")
    implementation("io.ktor:ktor-client-cio:3.4.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.4.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.3")

    // .env loader for the DeepSeek API key (real process env still takes precedence).
    implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")

    // SLF4J no-op backend: silences the MCP SDK's transitive kotlin-logging so the
    // interactive REPL terminal stays clean (no NOP-provider warning, no log noise).
    implementation("org.slf4j:slf4j-nop:2.0.18")

    // Terminal markdown rendering: Markdown widget -> ANSI, auto-downgrades to
    // plain text when stdout isn't a TTY. Pulls Mordant core transitively.
    implementation("com.github.ajalt.mordant:mordant-markdown:3.0.2")

    // Unit tests: kotlin-test on the JUnit5 platform, coroutine test scope.
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // MockEngine to stub the authorization-server HTTP for OAuth discovery/grant tests.
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

// Forward stdin for the agent REPL; force UTF-8 so non-ASCII isn't mangled by the
// platform default charset (CP866 on a Russian Windows console).
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
