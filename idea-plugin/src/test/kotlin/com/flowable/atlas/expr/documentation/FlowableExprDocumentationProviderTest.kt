package com.flowable.atlas.expr.documentation

import com.intellij.navigation.ItemPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Regression guard for the quick-doc "… cannot be presented" crash: the doc element returned by
 * [FlowableExprDocumentationProvider.getCustomDocumentationElement] must carry a non-blank presentable
 * text, otherwise the 2026.1 documentation backend throws when building the doc target's presentation.
 */
class FlowableExprDocumentationProviderTest : BasePlatformTestCase() {

    fun testCustomDocElementIsPresentable() {
        myFixture.configureByText("t.flowable-be", "executio<caret>n")   // "execution" is a known backend root
        val element = FlowableExprDocumentationProvider().getCustomDocumentationElement(
            myFixture.editor, myFixture.file,
            myFixture.file.findElementAt(myFixture.caretOffset), myFixture.caretOffset,
        )
        assertNotNull("doc element expected on a known token", element)
        val text = (element as ItemPresentation).presentableText
        assertFalse("presentable text must be non-blank so the platform can present the doc target", text.isNullOrBlank())
    }
}
