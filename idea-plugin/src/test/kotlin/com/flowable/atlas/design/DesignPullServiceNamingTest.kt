package com.flowable.atlas.design

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The project-independent filename logic behind "Pull from Flowable Design": how the pulled ZIP is
 * named (Content-Disposition → display name → key), how collisions are disambiguated, and how a
 * pre-multi-app `{key}.zip` is replaced rather than left as a duplicate.
 */
class DesignPullServiceNamingTest {

    private var tmp: Path? = null

    @After fun cleanup() {
        tmp?.let { dir -> Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    @Test fun `prefers the server filename, stripping path and extension`() {
        val used = mutableSetOf<String>()
        assertEquals("HR App", DesignPullService.uniqueFileBase("HR App.zip", "ignored", "hr", used))
        assertEquals("My App", DesignPullService.uniqueFileBase("/exports\\sub/My App.zip", "n", "k", mutableSetOf()))
    }

    @Test fun `falls back to display name then key`() {
        assertEquals("HR App", DesignPullService.uniqueFileBase(null, "HR App", "hr", mutableSetOf()))
        assertEquals("hrKey", DesignPullService.uniqueFileBase(null, null, "hrKey", mutableSetOf()))
        assertEquals("hrKey", DesignPullService.uniqueFileBase("   ", "  ", "hrKey", mutableSetOf()))
    }

    @Test fun `preserves spaces and parentheses in the display name`() {
        assertEquals("HR App (Prod)", DesignPullService.uniqueFileBase(null, "HR App (Prod)", "k", mutableSetOf()))
    }

    @Test fun `disambiguates same-named apps with the key, then a counter`() {
        val used = mutableSetOf<String>()
        assertEquals("HR", DesignPullService.uniqueFileBase(null, "HR", "a", used))
        assertEquals("HR-b", DesignPullService.uniqueFileBase(null, "HR", "b", used))
        // Contrived repeat of the same key exercises the numeric fallback.
        assertEquals("HR-b-2", DesignPullService.uniqueFileBase(null, "HR", "b", used))
    }

    @Test fun `sanitize keeps friendly chars but replaces path-hostile ones`() {
        assertEquals("HR App", DesignPullService.sanitize("HR App"))
        assertEquals("a-b-c", DesignPullService.sanitize("a<b>c"))
        assertEquals("name", DesignPullService.sanitize("  .name.  "))
        assertEquals("app", DesignPullService.sanitize("   "))   // blank → safe fallback
    }

    @Test fun `removes a superseded legacy key zip but leaves unrelated files`() {
        val dir = Files.createTempDirectory("atlas-pull").also { tmp = it }
        listOf("oldKey.zip", "manual-export.zip", "Payroll.zip").forEach { Files.write(dir.resolve(it), byteArrayOf(1)) }

        DesignPullService.removeSupersededLegacyZips(
            dir,
            appKeys = listOf("oldKey", "Payroll"),
            currentFileNames = setOf("HR App.zip", "Payroll.zip"),   // what this pull just wrote
        )

        assertFalse("legacy oldKey.zip should be replaced", Files.exists(dir.resolve("oldKey.zip")))
        assertTrue("a manual export must be left alone", Files.exists(dir.resolve("manual-export.zip")))
        assertTrue("a current output must be kept", Files.exists(dir.resolve("Payroll.zip")))
    }

    @Test fun `never deletes a current output that only differs from the legacy name by case`() {
        val dir = Files.createTempDirectory("atlas-pull").also { tmp = it }
        // On a case-insensitive filesystem "bar.zip" and "Bar.zip" are the same file — must not delete it.
        Files.write(dir.resolve("bar.zip"), byteArrayOf(1))

        DesignPullService.removeSupersededLegacyZips(dir, appKeys = listOf("bar"), currentFileNames = setOf("Bar.zip"))

        assertTrue(Files.exists(dir.resolve("bar.zip")))
    }
}
