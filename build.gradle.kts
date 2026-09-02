// Root build: declare plugin versions once (apply false) so each module can apply them without a
// version. Kotlin must be >= the version the target IDE is built with (2.3.21 covers 2026.2).
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
    id("org.jetbrains.intellij.platform") version "2.18.1" apply false
    // Fat-jar packaging for :cli (the maintained Gradle-9-compatible Shadow fork).
    id("com.gradleup.shadow") version "8.3.5" apply false
}

allprojects {
    group = "com.flowable.atlas"
    version = "0.21.0"
}

// Convenience aliases so the documentation site is discoverable from the root: the tasks themselves
// live in :cli, which already has the CLI classpath and the node helper they need.
tasks.register("site") {
    description = "Builds the documentation site into build/site."
    group = "documentation"
    dependsOn(":cli:site")
}
tasks.register("siteShots") {
    description = "Renders the documentation site's screenshots (needs Chrome)."
    group = "documentation"
    dependsOn(":cli:siteShots")
}
