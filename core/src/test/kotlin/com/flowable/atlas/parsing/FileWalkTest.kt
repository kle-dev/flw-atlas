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
}
