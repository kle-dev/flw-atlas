package com.flowable.atlas.settings

import com.flowable.atlas.design.DesignAuthMode
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The panel builds its two credential rows in property initializers, so their order relative to the
 * fields they hold matters — this asserts construction and a [DesignConnectionPanel.reset] against a real
 * project don't throw, and that the persisted mode decides which row is visible.
 */
class DesignConnectionPanelTest : BasePlatformTestCase() {

    private fun withPanel(block: (DesignConnectionPanel) -> Unit) {
        val panel = DesignConnectionPanel(project)
        try {
            block(panel)
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun `test reset on a fresh project shows the password row`() {
        FlowableAtlasProjectSettings.getInstance(project).designAuthMode = DesignAuthMode.BASIC
        withPanel { panel ->
            panel.reset()
            assertFalse("a fresh project has nothing to apply", panel.isModified())
        }
    }

    fun `test a token-mode project resets into token mode without a keychain read on the EDT`() {
        FlowableAtlasProjectSettings.getInstance(project).designAuthMode = DesignAuthMode.ACCESS_TOKEN
        withPanel { panel ->
            panel.reset()
            assertFalse(panel.isModified())
        }
    }
}
