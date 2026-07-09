// Explicit plugin repositories so plugin resolution (Kotlin, IntelliJ Platform, Java)
// works identically on the command line and inside the IDE's Gradle sync.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "flowable-atlas"

// :core       — pure-Kotlin analysis engine (parsing, graph, expression validation, rendering).
//               No IntelliJ platform dependency; consumed by both front-ends below.
// :cli        — standalone command-line front-end (fat-jar), the successor to flowable_atlas.py.
// :idea-plugin — the IntelliJ IDEA plugin; depends on :core and runs it in-process.
include(":core", ":cli", ":idea-plugin")
