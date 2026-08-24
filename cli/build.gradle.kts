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

// The skip-when-missing behaviour below is right for a developer machine but wrong for CI: a runner
// without node would report a green build having executed none of the frontend tests — a pipeline that
// claims coverage it does not have is worse than no pipeline. Set ATLAS_REQUIRE_BROWSER_TESTS=1 there
// and the absence becomes a failure. The Chrome half of the same switch lives in the .mjs scripts,
// which are the only place that knows whether a browser was found.
val requireBrowserTests: Boolean =
    providers.environmentVariable("ATLAS_REQUIRE_BROWSER_TESTS").orNull == "1"

/** True when the task may run; throws instead of skipping when the tests are declared mandatory. */
fun nodePresentOrFail(taskName: String): Boolean {
    val node = nodeExecutable
    if (node == null) {
        require(!requireBrowserTests) {
            "$taskName: no node on PATH and ATLAS_REQUIRE_BROWSER_TESTS=1 — install node or unset the variable"
        }
        logger.lifecycle("$taskName: skipped — no node on PATH")
    }
    return node != null
}

val searchSelfTest by tasks.registering(Exec::class) {
    description = "Runs the explorer search behaviour test (skipped when node is unavailable)."
    group = "verification"
    dependsOn(searchSelfTestReport)
    val script = rootProject.file("scripts/search-selftest.mjs")
    inputs.file(script)
    inputs.dir(rootProject.file("core/src/main/resources/frontend"))
    onlyIf { nodePresentOrFail("searchSelfTest") }
    // Configured unconditionally (Exec requires a command line even when onlyIf skips it).
    commandLine(
        nodeExecutable ?: "node",
        script.absolutePath,
        searchSelfTestDir.get().asFile.resolve("miniproject.explorer.html").absolutePath,
    )
}

// Runtime counterpart to the above: the engine test proves the ranking is right, this one proves the
// page still WORKS — that boot completes and every way of activating a hit (click, ⌘-click, Shift-click,
// Enter) actually navigates. A renamed variable once left the result-row click handler throwing, which
// no golden, smoke test or `node --check` could see; the page just quietly stopped responding to clicks.
// Skips itself when Chrome is absent (the script exits 0 saying so), so CI without a browser stays green.
val explorerUiTest by tasks.registering(Exec::class) {
    description = "Drives the generated explorer in headless Chrome (skipped without node/Chrome)."
    group = "verification"
    dependsOn(searchSelfTestReport)
    val script = rootProject.file("scripts/explorer-uitest.mjs")
    inputs.file(script)
    inputs.dir(rootProject.file("core/src/main/resources/frontend"))
    onlyIf { nodePresentOrFail("explorerUiTest") }
    commandLine(
        nodeExecutable ?: "node",
        script.absolutePath,
        searchSelfTestDir.get().asFile.resolve("miniproject.explorer.html").absolutePath,
    )
}

// Same idea for the diagram element card, which needs its own fixture: the card only exists where a model
// carries DI, and miniproject has none — `core/src/test/resources/diagram` is the fixture that does.
val diagramUiTestDir = layout.buildDirectory.dir("diagram-uitest")

val diagramUiTestReport by tasks.registering(JavaExec::class) {
    description = "Generates the DI-carrying report the diagram UI test runs against."
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.flowable.atlas.cli.MainKt")
    args(
        rootProject.file("core/src/test/resources/diagram").absolutePath,
        "--all", "--quiet",
        "-o", diagramUiTestDir.get().asFile.absolutePath,
    )
    inputs.dir(rootProject.file("core/src/test/resources/diagram"))
    inputs.dir(rootProject.file("core/src/main/resources/frontend"))
    outputs.dir(diagramUiTestDir)
}

val diagramUiTest by tasks.registering(Exec::class) {
    description = "Drives the diagram element card in headless Chrome (skipped without node/Chrome)."
    group = "verification"
    dependsOn(diagramUiTestReport)
    val script = rootProject.file("scripts/diagram-uitest.mjs")
    inputs.file(script)
    inputs.dir(rootProject.file("core/src/main/resources/frontend"))
    onlyIf { nodePresentOrFail("diagramUiTest") }
    commandLine(
        nodeExecutable ?: "node",
        script.absolutePath,
        diagramUiTestDir.get().asFile.resolve("diagram.explorer.html").absolutePath,
    )
}

tasks.named("check") { dependsOn(searchSelfTest, explorerUiTest, diagramUiTest) }

// ---- documentation site (site/ -> build/site) ----
// The site lives here rather than in the root build because everything it needs is already wired up
// in this module: the CLI main class that generates the live demo, and the node-present-or-fail
// helper the browser tests use.
//
// Nothing the site build produces is committed. The demo artifacts and the screenshots are generated
// on every deploy (see .github/workflows/pages.yml), which is what keeps the images from ever showing
// a version of the product that no longer exists.

val siteDemoDir = layout.buildDirectory.dir("site-demo")

val siteDemo by tasks.registering(JavaExec::class) {
    description = "Generates the documentation site's live demo artifacts from site/flowable-demo."
    group = "documentation"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.flowable.atlas.cli.MainKt")
    args(
        rootProject.file("site/flowable-demo").absolutePath,
        "--all", "--quiet",
        "-o", siteDemoDir.get().asFile.absolutePath,
    )
    inputs.dir(rootProject.file("site/flowable-demo"))
    inputs.dir(rootProject.file("core/src/main/resources/frontend"))
    outputs.dir(siteDemoDir)
}

val siteShots by tasks.registering(Exec::class) {
    description = "Renders the site's screenshots and checks every mockup's geometry (needs Chrome)."
    group = "documentation"
    dependsOn(siteDemo)
    onlyIf { nodePresentOrFail("siteShots") }
    commandLine(
        nodeExecutable ?: "node",
        rootProject.file("scripts/site-shots.mjs").absolutePath,
        "--explorer", siteDemoDir.get().asFile.resolve("flowable-demo.explorer.html").absolutePath,
    )
}

val site by tasks.registering(Exec::class) {
    description = "Builds the documentation site into build/site."
    group = "documentation"
    dependsOn(siteDemo)
    onlyIf { nodePresentOrFail("site") }
    commandLine(
        nodeExecutable ?: "node",
        rootProject.file("scripts/site-build.mjs").absolutePath,
        "--demo", siteDemoDir.get().asFile.absolutePath,
    )
}

// Deliberately NOT --strict and deliberately without the demo: this is the gate every `./gradlew
// build` runs, so it must not need Chrome or a generated demo. It still catches what actually rots —
// an unparseable page, a dead internal link, a hardcoded version, a missing mockup. The strict build,
// which additionally requires every screenshot and the live demo to exist, runs in the Pages workflow.
val siteCheck by tasks.registering(Exec::class) {
    description = "Validates the site sources: markdown subset, internal links, versions, mockups."
    group = "verification"
    onlyIf { nodePresentOrFail("siteCheck") }
    commandLine(
        nodeExecutable ?: "node",
        rootProject.file("scripts/site-build.mjs").absolutePath,
        "--out", layout.buildDirectory.dir("site-check").get().asFile.absolutePath,
        // Point --demo at a directory this task never creates, so the gate behaves the same whether or
        // not a previous `siteDemo` run left artifacts lying around.
        "--demo", layout.buildDirectory.dir("site-check-no-demo").get().asFile.absolutePath,
    )
}

tasks.named("check") { dependsOn(siteCheck) }
