package com.flowable.atlas.graph

import com.flowable.atlas.GoldenFiles
import com.flowable.atlas.model.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The explorer writes waivers.json too, and the file is meant to be committed: two writers that disagree
 * on a key, a default or the sort order make it churn in every diff until nobody reads it. This feeds one
 * set — every optional field present and absent, a parse-finding rule keyed by its file, non-ASCII and
 * quotes in a reason — through [Waivers.serialize] and through the browser's writer (lifted out of
 * explorer.js by `scripts/waiver-selftest.mjs`) and asserts the bytes are identical.
 *
 * Needs node. Skipped without it, unless `ATLAS_REQUIRE_BROWSER_TESTS=1` says the frontend checks are
 * mandatory — the same rule the explorer's browser tests follow.
 */
class WaiverWriterParityTest {

    @Test
    fun theBrowserWritesTheSameBytesAsCore() {
        val node = nodeOrSkip()
        val set = Waivers.Set(
            waivers = listOf(
                Waivers.Waiver(check = "unusedForms", node = "form:DEMO-F014",
                    reason = "kept for the pilot — Umlaute äöü, \"quotes\" and a \\ backslash",
                    by = "team-orders", at = "2026-09-01", until = "2026-12-01"),
                Waivers.Waiver(check = "missingRefs", node = "external:DEMO-Shared-P001", reason = "shared repository"),
                Waivers.Waiver(check = "schemaGaps", node = "service:customer", subject = "cust_customer.orphan_col_"),
                Waivers.Waiver(check = "unguardedTasks", node = "process:orderProcess", element = "notifyTask",
                    reason = "errors reach the caller", at = "2026-09-11"),
                Waivers.Waiver(check = "parseIssues", node = "archives/demo-export.zip!manifest.json", reason = "not a model"),
                Waivers.Waiver(check = "unguardedTasks", node = "process:orderProcess", element = "auditTask", reason = "audited"),
            ),
            notes = listOf(
                Waivers.Note(node = "process:orderProcess", text = "a note with no extras"),
                Waivers.Note(node = "form:DEMO-F014", text = "replace with the new intake form in Q3",
                    importance = "high", by = "kk", at = "2026-09-01", check = "unusedForms"),
            ),
            createdWith = "0.24.0",
        )
        val expected = Waivers.serialize(set, "0.25.0-test")

        // The browser receives the rules the way the payload carries them: unsorted, optional keys absent.
        fun rule(w: Waivers.Waiver) = linkedMapOf<String, Any?>("check" to w.check, "node" to w.node).apply {
            w.element?.let { put("element", it) }; w.subject?.let { put("subject", it) }
            put("reason", w.reason)
            w.by?.let { put("by", it) }; w.at?.let { put("at", it) }; w.until?.let { put("until", it) }
        }
        fun note(n: Waivers.Note) = linkedMapOf<String, Any?>("node" to n.node).apply {
            n.check?.let { put("check", it) }; n.element?.let { put("element", it) }; n.subject?.let { put("subject", it) }
            put("text", n.text); put("importance", n.importance)
            n.by?.let { put("by", it) }; n.at?.let { put("at", it) }
        }
        val input = MiniJson.stringify(linkedMapOf<String, Any?>(
            "atlasVersion" to "0.25.0-test", "createdWith" to "0.24.0",
            "rules" to set.waivers.map(::rule), "notes" to set.notes.map(::note),
        ))
        val tmp = Files.createTempFile("atlas-waivers-input", ".json").toFile().apply { writeText(input, Charsets.UTF_8) }
        val script = File(GoldenFiles.repoRoot, "scripts/waiver-selftest.mjs")
        val p = ProcessBuilder(node, script.path, tmp.path).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals("waiver-selftest.mjs failed:\n$out", 0, p.waitFor())
        assertEquals("the two writers disagree — see the KDoc on Waivers.serialize", expected, out)
    }

    private fun nodeOrSkip(): String {
        val candidates = listOf("node", "/opt/homebrew/bin/node", "/usr/local/bin/node")
        val found = candidates.firstOrNull { c ->
            runCatching {
                val p = ProcessBuilder(c, "--version").redirectErrorStream(true).start()
                p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
            }.getOrDefault(false)
        }
        if (found == null) {
            assumeTrue("no node on PATH — set ATLAS_REQUIRE_BROWSER_TESTS=1 to make this test mandatory",
                System.getenv("ATLAS_REQUIRE_BROWSER_TESTS") == "1")
            fail("ATLAS_REQUIRE_BROWSER_TESTS=1 but no node was found")
        }
        return found!!
    }
}
