// Root build: declare plugin versions once (apply false) so each module can apply them without a
// version. Kotlin must be >= the version the target IDE is built with (2026.1 ships Kotlin 2.3.x).
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
    id("org.jetbrains.intellij.platform") version "2.17.0" apply false
    // Fat-jar packaging for :cli (the maintained Gradle-9-compatible Shadow fork).
    id("com.gradleup.shadow") version "8.3.5" apply false
}

allprojects {
    group = "com.flowable.atlas"
    version = "0.8.0"
}
