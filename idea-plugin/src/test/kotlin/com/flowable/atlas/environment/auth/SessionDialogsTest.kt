package com.flowable.atlas.environment.auth

import com.flowable.atlas.design.DesignCreateTokenDialog
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The dialogs that take a session or mint a token refuse what cannot work before they close. */
class SessionDialogsTest : BasePlatformTestCase() {

    fun testAPasteWithoutASessionHeaderDoesNotClose() {
        val dialog = PasteSessionDialog(project)
        try {
            assertNotNull("an empty paste", dialog.validateWith(""))
            assertNotNull("a request copied without its cookie", dialog.validateWith("curl 'https://work.example.com/api' -H 'Accept: */*'"))
            assertNull(dialog.validateWith("curl 'https://work.example.com/api' -H 'Cookie: JSESSIONID=DEMO'"))
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun testTheTokenDialogBuilds() {
        val dialog = DesignCreateTokenDialog(project, "https://design.example.com")
        try {
            assertTrue(dialog.formForTest().componentCount > 0)
            assertEquals("a year unless changed", "P365D", dialog.validFor)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }
}
