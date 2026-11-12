package com.flowable.atlas.inspection

import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * A broken model key inside a JSON model is flagged where it is written — the same verdict as the report's
 * missing reference, at the sites Ctrl+click follows — and nothing else is: not a binding, not a name, not
 * a key the platform ships.
 */
class FlowableJsonBrokenKeyInspectionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(FlowableJsonBrokenKeyInspection::class.java)
    }

    private fun addTargets() {
        myFixture.addFileToProject("models/DEMO-orderForm.form", """{"key":"DEMO-orderForm","name":"Order form","components":[]}""")
        myFixture.addFileToProject("models/DEMO-orderPlaced.event", """{"key":"DEMO-orderPlaced","name":"Order placed","payload":[]}""")
        myFixture.addFileToProject("models/DEMO-orderPage.page", """{"key":"DEMO-orderPage","name":"Order page","components":[]}""")
        project.service<FlowableModelIndexService>().index()
    }

    private fun subform(key: String) =
        """{"key":"DEMO-review","name":"Review","components":[{"type":"subform","id":"sub","extraSettings":{"formRef":{"id":"FORM-1","key":"$key"}}}]}"""

    private fun unknownKeyWarnings() = myFixture.doHighlighting().filter { (it.description ?: "").contains("is not a known") }

    fun testAnUnknownSubformIsFlaggedAndFixedToTheNearestForm() {
        addTargets()
        myFixture.configureByText("DEMO-review.form", subform("DEMO-order<caret>From"))
        val warning = unknownKeyWarnings().single()
        assertEquals("'DEMO-orderFrom' is not a known Form key — did you mean 'DEMO-orderForm'?", warning.description)

        myFixture.launchAction(myFixture.findSingleIntention("Replace with 'DEMO-orderForm'"))
        val text = myFixture.editor.document.text
        assertTrue(text, Regex(""""key":\s*"DEMO-orderForm"""").containsMatchIn(text))
        assertTrue("fixed, the warning is gone", unknownKeyWarnings().isEmpty())
    }

    fun testAKnownKeyABindingAndAPageForAFormAreNotFlagged() {
        addTargets()
        myFixture.configureByText("DEMO-review.form", subform("DEMO-orderForm"))
        assertTrue(unknownKeyWarnings().isEmpty())
        myFixture.configureByText("DEMO-review2.form", subform("{{subformKey}}"))
        assertTrue("a binding names the key at run time", unknownKeyWarnings().isEmpty())
        myFixture.configureByText("DEMO-review3.form", subform("DEMO-orderPage"))
        assertTrue("a form key may name a page — the report resolves it so", unknownKeyWarnings().isEmpty())
    }

    fun testAJsonFileThatIsNoModelIsLeftAlone() {
        addTargets()
        myFixture.configureByText("DEMO-settings.json", subform("DEMO-orderFrom"))
        assertTrue("outside a Design *-models folder a .json is no model", unknownKeyWarnings().isEmpty())
    }

    fun testANameHoldingAMissingKeyIsNotASite() {
        addTargets()
        myFixture.configureByText("DEMO-review.form", """{"key":"DEMO-review","name":"DEMO-orderFrom","components":[]}""")
        assertTrue(unknownKeyWarnings().isEmpty())
    }

    fun testAPlatformEventOnAChannelIsNotMissing() {
        addTargets()
        myFixture.configureByText(
            "DEMO-mail.channel",
            """{"key":"DEMO-mail","name":"Mail","channelEventKeyDetection":{"fixedValue":"_flowableMailEvent"}}""",
        )
        assertTrue("the platform ships that event; the report shows it as a platform external", unknownKeyWarnings().isEmpty())
        myFixture.configureByText(
            "DEMO-mail2.channel",
            """{"key":"DEMO-mail2","name":"Mail","channelEventKeyDetection":{"fixedValue":"DEMO-orderPlacd"}}""",
        )
        assertEquals(1, unknownKeyWarnings().size)
    }

    /** The report's platform-provided models, by kind: a default form the platform ships is a form, not an event. */
    fun testAFormThePlatformShipsIsNotMissingWhereAFormIsExpected() {
        addTargets()
        myFixture.configureByText("DEMO-review.form", subform("F07-deactivateUser"))
        assertTrue("a copied action's platform form resolves in the report", unknownKeyWarnings().isEmpty())
        myFixture.configureByText(
            "DEMO-mail3.channel",
            """{"key":"DEMO-mail3","name":"Mail","channelEventKeyDetection":{"fixedValue":"F07-deactivateUser"}}""",
        )
        assertEquals("the platform ships no event of that key", 1, unknownKeyWarnings().size)
    }

    fun testNoVerdictWithoutAnyModelOfTheType() {
        addTargets()
        myFixture.configureByText(
            "DEMO-customer.data",
            """{"key":"DEMO-customer","name":"Customer","referencedServiceDefinitionModelKey":"DEMO-customerService","fieldMappings":[]}""",
        )
        assertTrue("a project with no service model indexed is not guessed at", unknownKeyWarnings().isEmpty())
    }
}
