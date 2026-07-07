package com.flowable.atlas

import com.flowable.atlas.explorer.AtlasScript
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Gradle wiring that bundles the repo-root `flowable_atlas.py` into the plugin as
 * `/atlas/flowable_atlas.py`. If the copy in build.gradle.kts breaks, this fails fast instead of
 * shipping a plugin whose "Generate Atlas Explorer" action can't find its generator.
 */
class AtlasScriptTest {

    @Test
    fun bundledGeneratorResourceIsPresentAndComplete() {
        assertTrue("flowable_atlas.py must be bundled as a plugin resource", AtlasScript.isBundled())

        val text = AtlasScript::class.java.getResourceAsStream("/atlas/flowable_atlas.py")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        assertNotNull("bundled generator must be readable", text)

        // Sanity-check it's the real generator: the HTML renderer and its template placeholder.
        assertTrue("expected the html_render entry point", text!!.contains("def html_render"))
        assertTrue("expected the __ATLAS_DATA__ template placeholder", text.contains("__ATLAS_DATA__"))
    }
}
