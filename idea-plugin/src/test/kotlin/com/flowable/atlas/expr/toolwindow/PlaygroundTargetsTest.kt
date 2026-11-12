package com.flowable.atlas.expr.toolwindow

import com.flowable.atlas.environment.AtlasConnectionSelection
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.environment.auth.BrowserSessions
import com.flowable.atlas.expr.inspect.InspectSessionTargets
import com.flowable.atlas.expr.inspect.PasteWorkUrlDialog
import com.flowable.atlas.expr.inspect.WorkUrlParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The backend card's target logic, driven without a window. */
class PlaygroundTargetsTest : BasePlatformTestCase() {

    fun testNothingToEvaluateAgainstIsOfferedOnlyWhileItIsTheState() {
        val targets = PlaygroundTargets(project)
        assertEquals(listOf(Target.None), targets.choices())
        assertEquals("no environment yet", targets.label(Target.None))
        assertEquals("", targets.baseUrl())
    }

    fun testAPastedTargetWinsUntilAnEnvironmentIsPickedElsewhere() {
        val targets = PlaygroundTargets(project)
        val catalog = AtlasEnvironments.getInstance()
        val env = catalog.addEnvironment("QA")
        try {
            val id = catalog.addConnection(env, ConnectionKind.WORK, "https://work-qa.example.com")!!
            AtlasConnectionSelection.select(project, ConnectionKind.WORK, id)
            assertEquals("https://work-qa.example.com", targets.baseUrl())

            val pasted = targets.applyPasted(
                PasteWorkUrlDialog.Result(null, "http://localhost:9914", "demo", "secret", WorkUrlParser.parse("")),
            )!!
            assertEquals("localhost:9914", pasted.where)
            assertFalse(pasted.repeated)
            assertEquals("the most recent thing the user said wins", "http://localhost:9914", targets.baseUrl())
            assertEquals("localhost:9914 (this session)", targets.label(targets.current()))
            assertEquals("the environment stays a click away", 2, targets.choices().size)
            assertNotNull("credentials go to the in-memory session store", BrowserSessions.get("http://localhost:9914"))

            // A Work pick made anywhere else is a decision about this card too.
            targets.followEnvironmentPick = true
            targets.adopt()
            assertEquals("QA", targets.connection()?.environmentName)

            // Forgetting takes the captured credentials with it and leaves nothing selected in its place.
            targets.forget("http://localhost:9914")
            assertNull(BrowserSessions.get("http://localhost:9914"))
            assertEquals(1, targets.choices().size)
        } finally {
            InspectSessionTargets.clear()
            BrowserSessions.clear("http://localhost:9914")
            AtlasConnectionSelection.clear(project, ConnectionKind.WORK)
            catalog.removeEnvironment(env)
        }
    }

    fun testSavingASessionTargetJoinsAnExistingEnvironmentByName() {
        val targets = PlaygroundTargets(project)
        val catalog = AtlasEnvironments.getInstance()
        val env = catalog.addEnvironment("QA")
        try {
            targets.applyPasted(PasteWorkUrlDialog.Result(null, "https://work-qa.example.com", "", "", WorkUrlParser.parse("")))
            assertEquals(PlaygroundTargets.Saved.Ok("QA"), targets.saveAs("https://work-qa.example.com", "QA", protected = false))
            assertEquals("no second QA", 1, catalog.environments().size)
            assertEquals("https://work-qa.example.com", targets.baseUrl())
            assertTrue("it is an environment now, not a session target", InspectSessionTargets.all().isEmpty())
            // A second app for the same environment is refused, not silently replaced.
            targets.applyPasted(PasteWorkUrlDialog.Result(null, "http://localhost:9914", "", "", WorkUrlParser.parse("")))
            assertEquals(PlaygroundTargets.Saved.AlreadyHasApp("QA"), targets.saveAs("http://localhost:9914", "QA", protected = false))
        } finally {
            InspectSessionTargets.clear()
            AtlasConnectionSelection.clear(project, ConnectionKind.WORK)
            catalog.environments().forEach { catalog.removeEnvironment(it.id) }
        }
    }
}
