package com.flowable.atlas.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Guards the determinism of file discovery.
 *
 * Atlas used `File.walkTopDown()`, which yields children in `File.listFiles()` order — sorted on
 * APFS/HFS+, hash-ordered on ext4. That order reached the generated artifacts through insertion-ordered
 * maps, so the same project produced a different `overview.md` and `summary.md` on macOS than on Linux.
 * The byte-exact goldens could not catch it because they only ever ran on one OS; CI on Linux failed on
 * its first run with exactly that diff (a REST endpoint list and a Java-class count in another order).
 *
 * These tests state the property directly, so it holds regardless of which OS runs the suite.
 */
class FileWalkTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    @Test
    fun filesComeBackInNameOrderWithinEachDirectory() {
        // Created in deliberately non-alphabetical order: on a filesystem that reports creation order
        // (or hash order) an unsorted walk would hand these back exactly as made.
        for (name in listOf("zebra.java", "apple.java", "Mango.java", "banana.java")) {
            tmp.newFile(name)
        }
        val names = FileWalk.files(tmp.root).map { it.name }.toList()
        assertEquals(listOf("Mango.java", "apple.java", "banana.java", "zebra.java"), names)
    }

    @Test
    fun subdirectoriesAreVisitedInNameOrderAndDepthFirst() {
        tmp.newFolder("zoo"); tmp.newFolder("aardvark")
        tmp.newFile("zoo/b.java"); tmp.newFile("zoo/a.java")
        tmp.newFile("aardvark/y.java"); tmp.newFile("aardvark/x.java")
        tmp.newFile("middle.java")

        val rel = FileWalk.files(tmp.root).map { it.relativeTo(tmp.root).path.replace(File.separatorChar, '/') }
        assertEquals(
            listOf("aardvark/x.java", "aardvark/y.java", "middle.java", "zoo/a.java", "zoo/b.java"),
            rel.toList(),
        )
    }

    /** `enterDir` must see the root too, matching the `walkTopDown().onEnter` semantics it replaced. */
    @Test
    fun enterDirIsAskedAboutTheRootAndCanPruneSubtrees()  {
        tmp.newFolder("build"); tmp.newFile("build/generated.java"); tmp.newFile("kept.java")

        val pruned = FileWalk.files(tmp.root) { it.name != "build" }.map { it.name }.toList()
        assertEquals(listOf("kept.java"), pruned)

        val rootRefused = FileWalk.files(tmp.root) { false }.toList()
        assertTrue("refusing the root must yield nothing, got $rootRefused", rootRefused.isEmpty())
    }

    /**
     * The property the goldens actually depend on: discovery buckets are ordered, so everything built from
     * them downstream is reproducible. Asserted on the real fixture the goldens are generated from.
     */
    @Test
    fun discoveryBucketsAreSorted() {
        val fixture = File(javaClass.classLoader.getResource("miniproject")!!.toURI())
        val d = Discovery.discover(fixture)
        for ((label, files) in listOf(
            "models" to d.models, "archives" to d.archives, "javas" to d.javas, "xmls" to d.xmls,
        )) {
            val paths = files.map { it.path }
            assertEquals("$label is not in a deterministic order", paths.sorted(), paths)
        }
        assertTrue("fixture yielded no models — is the resource still there?", d.models.isNotEmpty())
    }

    @Test
    fun aSymlinkBackToAnAncestorIsNotWalkedAgain() {
        val sub = tmp.newFolder("models")
        File(sub, "a.bpmn").writeText("<definitions/>")
        java.nio.file.Files.createSymbolicLink(File(sub, "loop").toPath(), tmp.root.toPath())
        val names = FileWalk.files(tmp.root).map { it.name }.toList()
        assertEquals(listOf("a.bpmn"), names)
    }

    /**
     * A committed link out of the project — to a sibling checkout, to the parent folder — is not followed,
     * and is reported instead; one that stays inside is followed. A link the walk would have pruned, or a
     * file discovery would not have read, costs the report nothing and is not reported.
     */
    @Test
    fun aSymlinkOutOfTheRootIsNotFollowedButReported() {
        val root = tmp.newFolder("project")
        val outside = tmp.newFolder("outside")
        File(outside, "DEMO-elsewhere.bpmn").writeText("<definitions/>")
        File(outside, ".editorconfig").writeText("root = true")
        File(root, "models").mkdirs()
        File(root, "models/DEMO-here.bpmn").writeText("<definitions/>")
        val made = runCatching {
            java.nio.file.Files.createSymbolicLink(File(root, "vendor").toPath(), outside.toPath())
            java.nio.file.Files.createSymbolicLink(File(root, "up").toPath(), java.nio.file.Paths.get(".."))
            java.nio.file.Files.createSymbolicLink(File(root, "one.bpmn").toPath(), File(outside, "DEMO-elsewhere.bpmn").toPath())
            java.nio.file.Files.createSymbolicLink(File(root, "alias.bpmn").toPath(), java.nio.file.Paths.get("models/DEMO-here.bpmn"))
            java.nio.file.Files.createSymbolicLink(File(root, "build").toPath(), outside.toPath())
            java.nio.file.Files.createSymbolicLink(File(root, ".editorconfig").toPath(), File(outside, ".editorconfig").toPath())
        }.isSuccess
        // a file system or an account that cannot create links (Windows without the privilege) has nothing to test
        org.junit.Assume.assumeTrue(made)
        val left = ArrayList<String>()
        val files = FileWalk.files(root, onLeave = { left.add(it.name) }) { it.name != "build" }.toList()
        val rootReal = root.canonicalFile.toPath()
        assertTrue(files.toString(), files.all { it.canonicalFile.toPath().startsWith(rootReal) })
        assertEquals("a link inside the project is still followed", listOf("alias.bpmn", "models/DEMO-here.bpmn"),
            files.map { it.relativeTo(root).invariantSeparatorsPath }.sorted())
        assertEquals("a pruned folder is not reported, however it is linked", listOf(".editorconfig", "one.bpmn", "up", "vendor"), left.sorted())
        val d = Discovery.discover(root)
        // a linked file discovery would not read is no loss to the report, and no warning either
        assertEquals(listOf("one.bpmn", "up", "vendor"), d.outsideLinks.map { it.name }.sorted())
        // …and the report says so, rather than leaving a team that links shared models in to guess
        val diags = (com.flowable.atlas.graph.Atlas.extract(root)["diagnostics"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("one.bpmn", "up", "vendor"),
            diags.filter { it["kind"] == "skip" && it["message"].toString().contains("symbolic link") }.map { it["path"].toString() }.sorted())
    }
}
