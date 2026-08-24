// :core — the pure-Kotlin Flowable Atlas engine (model parsing, graph, expression validation,
// rendering). NO IntelliJ platform dependency: it is consumed both by the IDEA plugin (in-process)
// and by the standalone CLI. Kept dependency-light (Kotlin stdlib + JDK only) so it can run anywhere.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    // The root gradle.properties sets kotlin.stdlib.default.dependency=false so the IDEA plugin does
    // not bundle a stdlib (the platform provides one). :core still needs the stdlib to compile, but
    // as compileOnly so it is NOT pulled transitively into the plugin; the CLI adds it as a real
    // runtime dependency, and :core's own tests get it via testImplementation.
    compileOnly(kotlin("stdlib"))
    testImplementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()

    // Files this module's tests compare but that live OUTSIDE its source sets, so Gradle cannot infer
    // them: the sync tests (ClaudeTemplateSyncTest, ChangelogSyncTest) read them straight off the repo
    // via GoldenFiles.repoRoot. Without this the gates were only as good as luck — hand-editing
    // CHANGELOG.md or CLAUDE.template.md left `test` UP-TO-DATE, so `./gradlew build` reported success
    // on exactly the drift those tests exist to catch (verified: an injected line went unnoticed until
    // --rerun-tasks). Declaring them as inputs is what makes the gates gate. plugin.xml is here because
    // CHANGELOG.md is generated from its <change-notes>: editing the notes must re-run the comparison.
    //
    // The same reasoning covers the documentation gates: SiteDocsCoverageTest reads `site/pages/*` and
    // the sources those pages must stay in step with, SiteDemoProjectTest reads the sample project, and
    // DocsVersionSyncTest reads FEATURES.md. Without them declared, renaming every `guessedVars`
    // mention in site/pages/checks.md left `test` UP-TO-DATE and `build` reported success on a page that
    // no longer documented the check (verified the same way as above).
    inputs.files(
        rootProject.file("CLAUDE.template.md"),
        rootProject.file("CHANGELOG.md"),
        rootProject.file("FEATURES.md"),
        rootProject.file("idea-plugin/src/main/resources/META-INF/plugin.xml"),
        rootProject.file("idea-plugin/src/main/resources/messages/FlowableAtlasBundle.properties"),
        rootProject.file("cli/src/main/kotlin/com/flowable/atlas/cli/Main.kt"),
        rootProject.file("build.gradle.kts"),
        rootProject.fileTree("site/pages"),
        rootProject.fileTree("site/flowable-demo"),
    ).withPropertyName("syncedRepoFiles").optional()
}

// Re-baseline the committed golden artifacts (core/src/test/resources/golden/*) from the current
// generator output, then review the diff — the workflow the deleted Python suite had as
// `ATLAS_UPDATE_GOLDEN=1 pytest`. See com.flowable.atlas.GoldenFiles. Always re-runs (the tests it
// drives are up-to-date-checked against source, not against the goldens they rewrite).
val updateGoldens by tasks.registering(Test::class) {
    group = "verification"
    description = "Rewrite core/src/test/resources/golden/* from the current output (review the diff!)"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnit()
    environment("ATLAS_UPDATE_GOLDEN", "1")
    filter {
        includeTestsMatching("com.flowable.atlas.graph.GoldenExtractionTest")
        includeTestsMatching("com.flowable.atlas.render.SummaryRendererTest")
        includeTestsMatching("com.flowable.atlas.render.OverviewRendererTest")
        includeTestsMatching("com.flowable.atlas.render.ClaudeTemplateSyncTest")
        // CHANGELOG.md is generated from the plugin descriptor's <change-notes>; re-baseline it here
        // together with the other generated repo files instead of hand-editing it.
        includeTestsMatching("com.flowable.atlas.render.ChangelogSyncTest")
    }
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}

// Bake the Gradle version into a resource so :core (and thus the generated explorer HTML + the CLI) can
// stamp which Atlas version produced their output — read back by com.flowable.atlas.AtlasBuildInfo.
val generateVersionResource by tasks.registering {
    val versionValue = project.version.toString()
    val outputDir = layout.buildDirectory.dir("generated-resources/version")
    inputs.property("version", versionValue)
    outputs.dir(outputDir)
    doLast {
        val f = outputDir.get().file("atlas-version.txt").asFile
        f.parentFile.mkdirs()
        f.writeText(versionValue)
    }
}

sourceSets.main.get().resources.srcDir(generateVersionResource)
