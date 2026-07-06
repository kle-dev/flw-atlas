import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    // Must be >= the Kotlin version the target IDE is built with (2026.1 ships Kotlin 2.3.x),
    // otherwise the compiler can't read the platform's Kotlin metadata.
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

group = "com.flowable.keys"
version = "0.2.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Build against the locally installed IntelliJ IDEA — exact match to the target IDE
        // (2026.1.2) and no multi-GB SDK download. To build against a downloaded SDK instead,
        // replace this line with: intellijIdeaCommunity("2026.1")
        local("/Applications/IntelliJ IDEA.app")

        // Java PSI — required by the Java completion contributor.
        bundledPlugin("com.intellij.java")

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
