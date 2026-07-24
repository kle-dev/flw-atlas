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
