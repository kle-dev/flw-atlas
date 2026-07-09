import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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
        // Build against the locally installed IntelliJ IDEA — exact match to the target IDE
        // (2026.1.2) and no multi-GB SDK download. To build against a downloaded SDK instead,
        // replace this line with: intellijIdeaCommunity("2026.1")
        local("/Applications/IntelliJ IDEA.app")

        // Java PSI — required by the Java completion contributor.
        bundledPlugin("com.intellij.java")

        // JSON PSI — required for injecting the frontend expression language into form-model JSON
        // ({{…}} inside JsonStringLiteral) and for treating .form files as JSON.
        bundledPlugin("com.intellij.modules.json")

        // Functional tests (BasePlatformTestCase + completion fixtures).
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    // Not needed for this plugin; disabling avoids slow/headless build steps.
    buildSearchableOptions = false
    instrumentCode = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"   // 2026.1
            untilBuild = "299.*" // wide open for "latest" (internal tool)
        }
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
