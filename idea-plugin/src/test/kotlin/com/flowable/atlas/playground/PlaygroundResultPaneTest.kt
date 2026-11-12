package com.flowable.atlas.playground

import com.flowable.atlas.playground.PlaygroundResultPane.ResultState
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** One pane, explicit states — and the empty hint follows the dialect without losing a shown value. */
class PlaygroundResultPaneTest : BasePlatformTestCase() {

    fun testStatesAreExplicitAndTheEmptyHintIsPerDialect() {
        val pane = PlaygroundResultPane("Type an expression to evaluate")
        assertEquals(ResultState.Empty("Type an expression to evaluate"), pane.state)

        // The type sits beside the caption, not padded onto the value with spaces.
        pane.showOk("42", "number")
        assertEquals(ResultState.Ok("42", "number"), pane.state)
        pane.showLoading("Evaluating against QA…")
        assertEquals(ResultState.Loading("Evaluating against QA…"), pane.state)
        pane.showError("Unknown property 'amout'")
        assertEquals(ResultState.Error("Unknown property 'amout'"), pane.state)
        pane.showInfo("valid, not previewable statically")
        assertEquals(ResultState.Info("valid, not previewable statically"), pane.state)

        // A new hint does not overwrite a value that is showing…
        pane.setEmptyHint("Choose an environment")
        assertTrue(pane.state is ResultState.Info)
        // …but an empty pane re-reads it, and a later hint change lands in place.
        pane.showEmpty()
        assertEquals(ResultState.Empty("Choose an environment"), pane.state)
        pane.setEmptyHint("Choose an environment and a live instance id")
        assertEquals(ResultState.Empty("Choose an environment and a live instance id"), pane.state)
    }
}
