// :cli — the standalone command-line front-end over :core (successor to flowable_atlas.py).
// Produces a self-contained fat-jar via the Shadow plugin; the repo-root `atlas` script runs it with
// `java -jar`.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.gradleup.shadow")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    // The CLI is a plain JVM app (no platform to provide the stdlib), so it needs a real runtime
    // stdlib — see the note in core/build.gradle.kts.
    implementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
}

// No `application` plugin: the `atlas` launcher runs the fat-jar with `java -jar`, and the
// application+shadow integration (startShadowScripts) is incompatible with Gradle 9's removal of
// `mainClassName`. Set the entry point on the fat-jar's manifest directly instead.
tasks.shadowJar {
    archiveClassifier.set("all")
    manifest { attributes["Main-Class"] = "com.flowable.atlas.cli.MainKt" }
}

tasks.test {
    useJUnit()
}

// ---- explorer search behaviour test ----
// The ⌘K / list search lives in core/src/main/resources/frontend/explorer.js, a browser asset no JUnit
// test can execute: the goldens are Markdown/JSON and RenderersSmokeTest only asserts that strings are
// present, not that a query finds anything. `scripts/search-selftest.mjs` evaluates the engine block out
// of that file and runs a query table against a freshly generated report.
//
// node is not a build requirement for this repo, so the task SKIPS when it is missing rather than
// failing — `./gradlew build` stays green on a machine without it.
val searchSelfTestDir = layout.buildDirectory.dir("search-selftest")

val searchSelfTestReport by tasks.registering(JavaExec::class) {
    description = "Generates the miniproject report the search self-test runs against."
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.flowable.atlas.cli.MainKt")
    args(
        rootProject.file("core/src/test/resources/miniproject").absolutePath,
        "--all", "--quiet",
        "-o", searchSelfTestDir.get().asFile.absolutePath,
    )
    inputs.dir(rootProject.file("core/src/test/resources/miniproject"))
    inputs.dir(rootProject.file("core/src/main/resources/frontend"))
    outputs.dir(searchSelfTestDir)
}

val nodeExecutable: String? by lazy {
    listOf("/usr/local/bin/node", "/opt/homebrew/bin/node", "/usr/bin/node")
        .firstOrNull { File(it).canExecute() }
        ?: runCatching {
            providers.exec { commandLine("sh", "-c", "command -v node") }
                .standardOutput.asText.get().trim().ifEmpty { null }
        }.getOrNull()
}

val searchSelfTest by tasks.registering(Exec::class) {
    description = "Runs the explorer search behaviour test (skipped when node is unavailable)."
    group = "verification"
    dependsOn(searchSelfTestReport)
    val script = rootProject.file("scripts/search-selftest.mjs")
    inputs.file(script)
    inputs.dir(rootProject.file("core/src/main/resources/frontend"))
    onlyIf {
        val node = nodeExecutable
        if (node == null) logger.lifecycle("searchSelfTest: skipped — no node on PATH")
        node != null
    }
    // Configured unconditionally (Exec requires a command line even when onlyIf skips it).
    commandLine(
        nodeExecutable ?: "node",
        script.absolutePath,
        searchSelfTestDir.get().asFile.resolve("miniproject.explorer.html").absolutePath,
    )
}

tasks.named("check") { dependsOn(searchSelfTest) }
