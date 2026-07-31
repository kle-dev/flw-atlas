package com.flowable.atlas.render

import com.flowable.atlas.GoldenFiles
import org.junit.Test
import java.io.File

/**
 * The repo's `CLAUDE.template.md` must be exactly what [ClaudeRenderer.renderGeneric] produces.
 *
 * It used to be a second, hand-maintained copy of the primer that `ClaudeRenderer` holds inline, with
 * nothing keeping the two in step — and it had drifted: it pointed readers at `APP_SUMMARY.md` and
 * `python3 flowable_atlas.py` (neither exists any more) and documented a `usedBy` field that model nodes
 * never had. Whichever copy a reader found, one of them was wrong.
 *
 * Same shape as [DesignTermsSyncTest]: one source of truth, a test that fails on divergence,
 * `./gradlew :core:updateGoldens` to re-baseline.
 */
class ClaudeTemplateSyncTest {

    @Test
    fun templateMatchesTheGenerator() {
        val f = File(GoldenFiles.repoRoot, "CLAUDE.template.md")
        GoldenFiles.assertFileMatches(f, ClaudeRenderer.renderGeneric().trimEnd('\n') + "\n")
    }
}
