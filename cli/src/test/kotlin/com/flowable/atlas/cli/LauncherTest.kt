package com.flowable.atlas.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The repository-root `atlas` launcher, run by bash against a stand-in `java` that records what it was
 * asked to run — so what is under test is the script's own decisions: which jar, which output folder,
 * what it says when there is nothing to run.
 *
 * Each case copies the script into a fresh folder, because the launcher resolves everything relative to
 * where it lives: that folder is a clone (with `lib/` and `cli/build/libs/`) or a release download (the
 * script and a jar side by side), depending on what the case puts in it. The docs describe both
 * (site/pages/start.md, site/pages/cli.md, lib/README.md), and the jar-search rule they state is the one
 * asserted here.
 */
class LauncherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun needsBash() {
        assumeTrue("the launcher is a bash script", !System.getProperty("os.name").startsWith("Windows"))
        assumeTrue("no bash on this machine", File("/bin/bash").canExecute())
    }

    /**
     * One race across three folders, not an ordered search: in a clone with an older download in `lib/`,
     * a fresh `./gradlew :cli:shadowJar` must be what runs — and the newer file wins whichever folder it is in.
     */
    @Test
    fun theNewestJarWinsAcrossTheThreeFolders() {
        val home = launcherHome()
        val now = System.currentTimeMillis()
        val inLib = jar(home, "lib/cli-0.1.0-all.jar", now - 2 * DAY)
        val built = jar(home, "cli/build/libs/cli-0.2.0-all.jar", now - DAY)
        assertTrue(launch(home).jar.endsWith(built.relativeTo(home).path))

        inLib.setLastModified(now)
        assertTrue("a jar in lib/ that is newer than the build wins", launch(home).jar.endsWith(inLib.relativeTo(home).path))

        val beside = jar(home, "cli-0.3.0-all.jar", now + DAY)
        assertTrue("so does one beside the script", launch(home).jar.endsWith(beside.relativeTo(home).path))
    }

    /** What a release ships: the script and the jar in one folder, and no `lib/` or Gradle anywhere. */
    @Test
    fun aDownloadedLauncherRunsTheJarBesideIt() {
        val home = launcherHome()
        jar(home, "cli-0.3.0-all.jar", System.currentTimeMillis())
        val run = launch(home)
        assertEquals(run.stderr, 0, run.exit)
        assertTrue(run.jar, run.jar.endsWith("/cli-0.3.0-all.jar"))
    }

    /**
     * Without a jar, a clone builds one with Gradle; a downloaded launcher has no Gradle beside it, so it
     * must say what to download rather than fail on a missing `./gradlew`.
     */
    @Test
    fun aDownloadedLauncherWithoutAJarSaysWhatToDownload() {
        val home = launcherHome()
        val run = launch(home)
        assertEquals(run.stderr, 1, run.exit)
        assertTrue(run.stderr, run.stderr.contains("Download cli-<version>-all.jar"))
        assertFalse("it does not try to build: ${run.stderr}", run.stderr.contains("building it with Gradle"))
        assertFalse("java never ran", run.argsFile.exists())
    }

    /**
     * `ATLAS_NO_BUILD=1` does not change that: its own message sends a clone's user to `lib/` and
     * `lib/README.md`, which a download has neither of. In a clone it still says exactly that.
     */
    @Test
    fun noBuildSendsADownloadToItsOwnFolderAndACloneToLib() {
        val download = launch(launcherHome(), "ATLAS_NO_BUILD" to "1")
        assertEquals(download.stderr, 1, download.exit)
        assertTrue(download.stderr, download.stderr.contains("into the same folder"))
        assertFalse(download.stderr, download.stderr.contains("lib/"))

        val clone = launcherHome()
        File(clone, "gradlew").apply { writeText("#!/bin/sh\nexit 1\n"); setExecutable(true) }
        val inClone = launch(clone, "ATLAS_NO_BUILD" to "1")
        assertEquals(inClone.stderr, 1, inClone.exit)
        assertTrue(inClone.stderr, inClone.stderr.contains("ATLAS_NO_BUILD=1 is set"))
        assertTrue(inClone.stderr, inClone.stderr.contains("into lib/"))
    }

    /**
     * The default output folder is beside the launcher, not inside the analysed project — which is why the
     * docs tell a reader who wants a `CLAUDE.md` import line to pass `<project>/atlas-output` explicitly.
     */
    @Test
    fun theDefaultOutputFolderIsBesideTheLauncher() {
        val home = launcherHome()
        jar(home, "cli-0.3.0-all.jar", System.currentTimeMillis())
        val run = launch(home)
        val args = run.args
        val out = args[args.indexOf("-o") + 1]
        // Canonical on both sides: the launcher spells its folder with `pwd`, and the temp dir may sit
        // behind a symlink (/var → /private/var on macOS).
        assertEquals(File(home, "atlas-output/DEMO-project").canonicalPath, File(out).canonicalPath)
        assertEquals("--all, and no --open after --no-open", listOf("--all"), args.filter { it.startsWith("--") })
    }

    // ---- helpers ----

    private class Run(val exit: Int, val stderr: String, val argsFile: File) {
        val args: List<String> get() = argsFile.readLines()
        /** The jar `java -jar` was given. */
        val jar: String get() = args[args.indexOf("-jar") + 1]
    }

    /** A fresh folder holding a copy of the launcher, and a project to point it at. */
    private fun launcherHome(): File {
        val home = tmp.newFolder()
        val script = File(home, "atlas")
        File(repoRoot(), "atlas").copyTo(script)
        script.setExecutable(true)
        File(home, "work/DEMO-project").mkdirs()
        return home
    }

    private fun jar(home: File, rel: String, mtime: Long): File =
        File(home, rel).also { it.parentFile.mkdirs(); it.writeText("not a real jar"); it.setLastModified(mtime) }

    /**
     * Runs the copied launcher on the demo project with `--no-open`. `JAVA` is a stand-in that writes its
     * arguments one per line, so nothing is analysed and no browser opens. The environment is cleared of
     * the launcher's own knobs, so a developer's `ATLAS_JAR` cannot decide the outcome; a case that wants
     * one passes it in [env].
     */
    private fun launch(home: File, vararg env: Pair<String, String>): Run {
        val argsFile = File(home, "java-args.txt").also { it.delete() }
        val java = File(home, "fake-java").apply {
            writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\" > '${argsFile.path}'\n")
            setExecutable(true)
        }
        val pb = ProcessBuilder("/bin/bash", File(home, "atlas").path, File(home, "work/DEMO-project").path, "--no-open")
            .directory(home)
        pb.environment().apply {
            remove("ATLAS_JAR"); remove("ATLAS_NO_BUILD")
            put("JAVA", java.path)
            putAll(env)
        }
        val p = pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        val stderr = p.errorStream.bufferedReader().readText()
        assertTrue("the launcher did not finish", p.waitFor(30, TimeUnit.SECONDS))
        return Run(p.exitValue(), stderr, argsFile)
    }

    /** Walk up from the test working dir (the module dir under Gradle) to the repo root. */
    private fun repoRoot(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").isFile) d = d.parentFile
        return d ?: error("repo root (settings.gradle.kts) not found from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
