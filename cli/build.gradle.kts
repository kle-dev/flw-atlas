// :cli — the standalone command-line front-end over :core (successor to flowable_atlas.py).
// Produces a self-contained fat-jar via the Shadow plugin; the repo-root `atlas` script runs it with
// `java -jar`.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.gradleup.shadow")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    // The CLI is a plain JVM app (no platform to provide the stdlib), so it needs a real runtime
    // stdlib — see the note in core/build.gradle.kts.
    implementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
}

// No `application` plugin: the `atlas` launcher runs the fat-jar with `java -jar`, and the
// application+shadow integration (startShadowScripts) is incompatible with Gradle 9's removal of
// `mainClassName`. Set the entry point on the fat-jar's manifest directly instead.
tasks.shadowJar {
    archiveClassifier.set("all")
    manifest { attributes["Main-Class"] = "com.flowable.atlas.cli.MainKt" }
}

tasks.test {
    useJUnit()
}
