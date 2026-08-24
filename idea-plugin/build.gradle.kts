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
    // Compatibility check: JetBrains' Plugin Verifier, run against every IDE in the `ides { }` block
    // below. It is the only gate that sees descriptor defects `build` cannot (an invalid structure, or
    // <change-notes> over its 65535-character cap).
    //
    // Verification targets 2026.2 only, by choice. Note that this is NARROWER than what the plugin
    // installs on: since-build stays 261 and the SDK is still 2026.1, so Atlas remains loadable on
    // 2026.1 — just unverified there. AtlasPlatformSupport carries that distinction and the Atlas Hub
    // shows it, so "it loads" is never presented as "it was tested".
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
            // This list IS the verified range AtlasPlatformSupport declares. Keep the two in step: those
            // constants are shown to users as a claim about what was actually checked.
            // Named `localIdes`, not `local`: a `local` val here would shadow the `local(File)` call below.
            val localIdes = providers.gradleProperty("atlas.verifyIdes").orNull
                ?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
                .orEmpty()
            if (localIdes.isNotEmpty()) {
                localIdes.forEach { local(file(it)) }
            } else {
                create(IntelliJPlatformType.IntellijIdea, "2026.2")
            }
        }
    }

    // Plugin signing. OPTIONAL, and off unless a key is actually configured — a hard requirement
    // here would break `./gradlew build` on every machine that has no signing key, which is all of
    // them except the release one.
    //
    // Why sign at all without a Marketplace: Atlas is side-loaded from a ZIP off the Releases page.
    // The Marketplace would otherwise be the thing vouching that the bytes are ours; with no
    // Marketplace in the path, the signature is. It makes tampering between "we built it" and
    // "a colleague installs it" detectable, which SHA256SUMS.txt cannot do on its own — whoever can
    // replace the asset can replace its checksum line in the same breath.
    //
    // Two ways in, because CI and a laptop hold the key differently:
    //
    //   * ATLAS_CERTIFICATE_CHAIN / ATLAS_PRIVATE_KEY hold the PEM *contents*. That is the shape a
    //     GitHub Actions secret has — there is no file to point at on a fresh runner.
    //   * -Patlas.signing.certificateChainFile / privateKeyFile hold *paths*, for a local release
    //     build where the key sits on disk and must not be pasted into a shell.
    //
    // The PASSWORD should always come from ATLAS_PRIVATE_KEY_PASSWORD, never from -Patlas.signing.password:
    // Gradle prints its own command line at --info, so the property form puts the passphrase in the build
    // log and in shell history. (The zip-signer subprocess still receives it as an argument, so it is
    // visible to `ps` for the moment it runs — that is the tool's design and not something this build can
    // fix; the property form simply adds two more places it leaks to.)
    //
    // One-time key generation (both files stay out of the repository — .gitignore covers *.pem/*.crt):
    //   openssl genpkey -algorithm RSA -out private.pem -aes-256-cbc -pkeyopt rsa_keygen_bits:4096
    //   openssl req -key private.pem -new -x509 -days 3650 -out chain.crt \
    //           -subj "/CN=Flowable Atlas/O=Flowable AG/C=CH"
    signing {
        val chainText = providers.environmentVariable("ATLAS_CERTIFICATE_CHAIN")
        val keyText = providers.environmentVariable("ATLAS_PRIVATE_KEY")
        val chainPath = providers.gradleProperty("atlas.signing.certificateChainFile")
        val keyPath = providers.gradleProperty("atlas.signing.privateKeyFile")

        if (chainText.isPresent && keyText.isPresent) {
            certificateChain = chainText.get()
            privateKey = keyText.get()
        } else if (chainPath.isPresent && keyPath.isPresent) {
            certificateChainFile = file(chainPath.get())
            privateKeyFile = file(keyPath.get())
        }
        providers.environmentVariable("ATLAS_PRIVATE_KEY_PASSWORD")
            .orElse(providers.gradleProperty("atlas.signing.password"))
            .orNull?.let { password = it }
    }

    // Not needed for this plugin; disabling avoids slow/headless build steps.
    buildSearchableOptions = false
    instrumentCode = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"   // 2026.1 — the branch :idea-plugin compiles against (the floor)
            // Deliberately wide, NOT the last verified branch — and still wide now that a custom plugin
            // repository exists (see scripts/make-update-plugins.mjs and the release job). The channel
            // changes the argument but not the conclusion: a tight until-build WOULD now produce
            // JetBrains' intended "update available" prompt, but only for someone who has added the
            // repository URL. For everyone who has not — and adoption of an opt-in URL is never
            // complete — it would still make Atlas silently vanish on the day they upgrade the IDE,
            // unrecoverable until a new release exists. Staying loadable is the better failure mode.
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

// Make `./gradlew build` produce the installable ZIP. The IntelliJ Platform plugin does not attach
// buildPlugin to the `build` lifecycle, so `build` compiled and tested everything and then stopped short
// of the one artifact this project actually ships — which meant packaging was only ever exercised by
// whoever remembered to type `buildPlugin`. CI found it the honest way: the workflow ran `build` and then
// had no ZIP to upload. Wiring it here keeps the local build and CI producing the same thing, instead of
// the workflow having to know about a module-specific task.
tasks.named("build") { dependsOn("buildPlugin") }

// Whether a signing key was supplied, by either of the two routes the `signing { }` block accepts.
val signingConfigured = providers.environmentVariable("ATLAS_CERTIFICATE_CHAIN").isPresent ||
    providers.gradleProperty("atlas.signing.certificateChainFile").isPresent

// Skip cleanly instead of failing, so `build` works on a machine with no key — which is every machine
// except the release one.
tasks.named("signPlugin") { onlyIf { signingConfigured } }

// verifyPluginSignature reads signPlugin's output, but the platform plugin wires no dependency between
// them. Without this line Gradle is free to schedule the verification FIRST, where the signed archive
// does not exist yet — and a task with no input is not a failure, it is NO-SOURCE. The release job
// would then report a green "Verify the signature" step having verified nothing at all, which is worse
// than not checking: it is a check that reports success by default. (Observed exactly that before the
// dependency was added.) onlyIf keeps it honest in the other direction — nothing to verify when nothing
// was signed.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginSignatureTask>("verifyPluginSignature") {
    onlyIf { signingConfigured }
    // Both lines are needed. dependsOn alone still left the task NO-SOURCE, because its @InputFile was
    // never pointed at signPlugin's output — so wiring the file is what actually gives it something to
    // verify, and the dependency is what guarantees that file exists by the time it looks.
    val sign = tasks.named<org.jetbrains.intellij.platform.gradle.tasks.SignPluginTask>("signPlugin")
    inputArchiveFile.convention(sign.flatMap { it.signedArchiveFile })
    certificateChainFile.convention(sign.flatMap { it.certificateChainFile })
    certificateChain.convention(sign.flatMap { it.certificateChain })
    dependsOn(sign)
}

// …but never skip *quietly*. A silent no-op is how an unsigned ZIP ends up on a Releases page that is
// believed to be signed. The warning cannot live in the onlyIf above: Gradle evaluates those specs
// during up-to-date checking and swallows their output at the default log level (verified — the
// message never appeared). The task graph is the first point where saying it is guaranteed to be seen,
// and asking there also means the warning only fires when signing was actually requested.
gradle.taskGraph.whenReady {
    if (!signingConfigured && hasTask("${project.path}:signPlugin")) {
        logger.warn("signPlugin: no signing key configured — the plugin ZIP will be UNSIGNED.")
    }
}
