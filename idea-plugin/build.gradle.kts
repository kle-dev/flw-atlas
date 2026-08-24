import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java")
    // Versions are declared once in the root build.gradle.kts (apply false); applied here without a
    // version. Kotlin must be >= the version the target IDE is built with (2026.1 ships Kotlin 2.3.x),
    // otherwise the compiler can't read the platform's Kotlin metadata.
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

// group/version are inherited from the root build (allprojects { … }).

// Keep the released artifact name stable as "flowable-atlas-<version>.zip". Without this the zip
// would take the Gradle subproject name ("idea-plugin") now that this is no longer the root project.
base { archivesName.set("flowable-atlas") }

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // The shared pure-Kotlin engine (parsing, graph, expression validation, rendering). Consumed
    // in-process; :core declares its stdlib as compileOnly so nothing extra is bundled here.
    implementation(project(":core"))

    intellijPlatform {
        // Portable build against a downloaded IntelliJ IDEA 2026.1 SDK — no dependency on a locally
        // installed IDE, so it builds on any machine / CI. Since 2025.3 the separate Community SDK
        // (ideaIC) is no longer published; 2026.x ships a single "IntelliJ IDEA" distribution with a
        // free tier, requested via intellijIdea(...). The plugin only uses APIs available in that free
        // tier (java/json/xml/platform). Compile target is the *oldest* supported platform, 2026.1
        // (build 261) — building against the floor is what keeps one artifact loadable on 2026.2 and
        // later. JCEF is in the platform core on 261 and in the bundled "Web Browser (JCEF)" plugin
        // from 262 on; plugin.xml handles that with an optional <depends>, so nothing is needed here.
        // `verifyPlugin` (below) proves both versions.
        intellijIdea("2026.1")

        // Java PSI — required by the Java completion contributor.
        bundledPlugin("com.intellij.java")

        // JSON PSI — required for injecting the frontend expression language into form-model JSON
        // ({{…}} inside JsonStringLiteral) and for treating .form files as JSON.
        bundledPlugin("com.intellij.modules.json")

        // Functional tests (BasePlatformTestCase + completion fixtures).
        testFramework(TestFrameworkType.Platform)

        // Test-only: load the Groovy plugin into the test IDE so the script-injection/playground
        // tests can assert that script bodies really receive the language. The shipped plugin
        // resolves script languages by ID at runtime (Language.findLanguageByID) and has no
        // compile-time or plugin.xml dependency on any of them, so it still installs and runs on
        // the free tier. The JavaScript plugin ships in the unified distribution but is tied to the
        // paid tier and does not load in the test IDE (verified: registers no language there) — the
        // JS path degrades to plain text by design and is covered manually on an Ultimate sandbox.
        testBundledPlugin("org.intellij.groovy")
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    // Cross-version compatibility check: JetBrains' Plugin Verifier, run against every IDE in the
    // `ides { }` block below. This is how we prove one artifact really loads on both 2026.1 and 2026.2,
    // and it is the only gate that sees descriptor defects `build` cannot (an invalid structure, or
    // <change-notes> over its 65535-character cap).
    //
    //   ./gradlew :idea-plugin:verifyPlugin                                          # downloads both IDEs (CI)
    //   ./gradlew :idea-plugin:verifyPlugin -Patlas.verifyIdes="/Applications/IntelliJ IDEA.app"
    //                                                                               # uses an installed one
    pluginVerification {
        // Only real breakage fails the check. The plugin knowingly touches a handful of internal /
        // experimental / deprecated platform APIs (AppMode, PluginManagerCore, the weighted
        // Search-Everywhere contributor); those are reported in the HTML report but must not fail the
        // gate, or the gate is useless. NOT_DYNAMIC is expected too — the plugin is require-restart.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
        )
        ides {
            // Two ways in, because the two callers need different things:
            //
            //  * `-Patlas.verifyIdes="/Applications/IntelliJ IDEA.app"` verifies against IDEs already
            //    installed here. Fast, no download — the developer loop.
            //  * With the property absent, the versions below are DOWNLOADED. That is what makes the
            //    check runnable on a machine with no IDE installed (CI), which is the whole point: with
            //    only the local() path, the cross-version gate could never run unattended, and a
            //    descriptor defect that `build` does not see (e.g. <change-notes> over its 65535-char
            //    cap) reaches users unnoticed.
            //
            // The list is the verified range AtlasPlatformSupport declares. Keep the two in step: that
            // constant is shown to users as a claim about what was actually checked.
            // Named `localIdes`, not `local`: a `local` val here would shadow the `local(File)` call below.
            val localIdes = providers.gradleProperty("atlas.verifyIdes").orNull
                ?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
                .orEmpty()
            if (localIdes.isNotEmpty()) {
                localIdes.forEach { local(file(it)) }
            } else {
                create(IntelliJPlatformType.IntellijIdea, "2026.1")
                create(IntelliJPlatformType.IntellijIdea, "2026.2")
            }
        }
    }

    // Not needed for this plugin; disabling avoids slow/headless build steps.
    buildSearchableOptions = false
    instrumentCode = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"   // 2026.1 — the branch :idea-plugin compiles against (the floor)
            // Deliberately wide, NOT the last verified branch. Atlas ships as a ZIP committed to
            // idea-plugin/dist/ with no Marketplace update channel, so a tight until-build would not
            // produce JetBrains' intended "update the plugin" prompt — it would just make Atlas vanish
            // from every colleague's IDE on the day they upgrade the IDE, unrecoverable until a new ZIP
            // is built and pulled. Staying loadable is the right trade-off for that distribution model.
            //
            // The compatibility claim is kept honest in code instead: AtlasPlatformSupport states the
            // range actually covered by `verifyPlugin` and the Atlas Hub footer says so, flagging the
            // running IDE when it is newer than that. Bump the constant there, never this line.
            untilBuild = "299.*"
        }
    }
}

// Sandbox IDE on a *locally installed* IDE instead of the downloaded 2026.1 SDK — the only way to
// smoke-test against the real plugin classloader of another platform version (e.g. the JCEF plugin
// split in 2026.2):
//   ./gradlew :idea-plugin:runIdeLocal -Patlas.runIdePath="/Applications/IntelliJ IDEA.app"
providers.gradleProperty("atlas.runIdePath").orNull?.takeIf { it.isNotBlank() }?.let { path ->
    intellijPlatformTesting.runIde.register("runIdeLocal") {
        localPath = file(path.trim())
    }
}

kotlin {
    // Compile to Java 21 bytecode so the plugin runs on a JBR/JDK 21 target system.
    jvmToolchain(21)
}

// Keep the released plugin distribution named "flowable-atlas-<version>.zip". As a subproject of the
// multi-module build, the IntelliJ Platform `buildPlugin` Zip is otherwise named after the Gradle
// subproject ("idea-plugin"); base.archivesName above only renames the jars, not this distribution.
tasks.named<org.gradle.api.tasks.bundling.Zip>("buildPlugin") {
    archiveBaseName.set("flowable-atlas")
}
