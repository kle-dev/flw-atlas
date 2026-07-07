// Explicit plugin repositories so plugin resolution (Kotlin, IntelliJ Platform, Java)
// works identically on the command line and inside the IDE's Gradle sync.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "flowable-atlas"
