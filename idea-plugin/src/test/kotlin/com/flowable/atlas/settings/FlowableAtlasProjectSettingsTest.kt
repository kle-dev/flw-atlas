package com.flowable.atlas.settings

import com.flowable.atlas.expr.ExprProblem
import com.flowable.atlas.expr.ExprProblemKind
import com.flowable.atlas.expr.ExprSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowableAtlasProjectSettingsTest {

    private fun target(environment: String, vararg apps: String, workspace: String = "") =
        FlowableAtlasProjectSettings.DesignPullTarget(environment).also {
            it.workspaceKey = workspace
            it.appKeys = apps.toMutableList()
        }

    private fun problem(kind: ExprProblemKind, subject: String?) =
        ExprProblem(0, 1, "m", ExprSeverity.WARNING, null, kind, subject)

    private fun settings(vararg setup: (FlowableAtlasProjectSettings.State) -> Unit): FlowableAtlasProjectSettings {
        val s = FlowableAtlasProjectSettings(null)
        val state = FlowableAtlasProjectSettings.State()
        setup.forEach { it(state) }
        s.loadState(state)
        return s
    }

    @Test
    fun `namespace entry covers the namespace and its functions`() {
        val s = settings({ it.allowedNamespaces.add("myns") })
        assertTrue(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_NAMESPACE, "myns")))
        assertTrue(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_FUNCTION, "myns:doIt")))
        assertFalse(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_FUNCTION, "other:doIt")))
    }

    @Test
    fun `function entry is exact`() {
        val s = settings({ it.allowedFunctions.add("myns:doIt") })
        assertTrue(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_FUNCTION, "myns:doIt")))
        assertFalse(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_FUNCTION, "myns:other")))
        assertFalse(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_NAMESPACE, "myns")))
    }

    @Test
    fun `flw member allowlisting`() {
        val s = settings({ it.allowedFunctions.add("flw.custom") })
        assertTrue(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_FUNCTION, "flw.custom")))
        assertFalse(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_FUNCTION, "flw.other")))
    }

    @Test
    fun `grounding roots are a separate list`() {
        val s = settings({ it.allowedGroundingRoots.add("runtimeVar") })
        assertTrue(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_ROOT, "runtimeVar")))
        assertFalse(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_FUNCTION, "runtimeVar")))
    }

    @Test
    fun `syntax and dialect problems are never allowlisted`() {
        val s = settings({ it.allowedNamespaces.add("x") }, { it.allowedFunctions.add("x") })
        assertFalse(s.isAllowlisted(problem(ExprProblemKind.SYNTAX, "x")))
        assertFalse(s.isAllowlisted(problem(ExprProblemKind.DIALECT_MISUSE, "x")))
        assertFalse(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_FUNCTION, null)))
    }

    @Test
    fun `allow() deduplicates and routes by kind`() {
        val s = settings()
        s.allow("myns", ExprProblemKind.UNKNOWN_NAMESPACE)
        s.allow("myns", ExprProblemKind.UNKNOWN_NAMESPACE)
        s.allow("flw.custom", ExprProblemKind.UNKNOWN_FUNCTION)
        s.allow("root1", ExprProblemKind.UNKNOWN_ROOT)
        s.allow("ignored", ExprProblemKind.SYNTAX)
        assertTrue(s.state.allowedNamespaces == mutableListOf("myns"))
        assertTrue(s.state.allowedFunctions == mutableListOf("flw.custom"))
        assertTrue(s.state.allowedGroundingRoots == mutableListOf("root1"))
    }

    // ---- per-sub-project scoping -------------------------------------------------------------

    @Test
    fun `sub-project scopes are independent of the default scope`() {
        val s = FlowableAtlasProjectSettings(null)
        s.scope("").designPullTargets.add(target("DEV", "root-app"))   // "" == the flat default scope
        s.scope("svc-a").designPullTargets.add(target("DEV", "a-app"))
        s.scope("svc-b").designPullTargets.add(target("DEV", "b-app"))
        assertEquals(listOf("root-app"), s.scope("").designPullTargets.single().appKeys)
        assertEquals(listOf("a-app"), s.scope("svc-a").designPullTargets.single().appKeys)
        assertEquals(listOf("b-app"), s.scope("svc-b").designPullTargets.single().appKeys)
    }

    @Test
    fun `getState keeps a sub-project whose only change is its workspace`() {
        val s = FlowableAtlasProjectSettings(null)
        s.scope("svc").designPullTargets.add(target("DEV", workspace = "ws-1"))
        s.scope("untouched")                         // left at defaults → pruned
        assertEquals(listOf("svc"), s.state.subProjects.map { it.path })
    }

    @Test
    fun `public accessors target the default scope when there is no project`() {
        val s = FlowableAtlasProjectSettings(null)
        s.pullTarget("DEV").appKeys = mutableListOf("root")
        // written through to the flat fields, which are the "" scope
        assertEquals(listOf("root"), s.scope("").designPullTargets.single().appKeys)
        assertEquals(listOf("root"), s.pullTarget("DEV").appKeys)
    }

    @Test
    fun `getState keeps configured sub-projects sorted and prunes untouched ones`() {
        val s = FlowableAtlasProjectSettings(null)
        s.scope("z-svc").designPullTargets.add(target("DEV", "z"))
        s.scope("a-svc")                             // touched but left at defaults → pruned
        s.scope("m-svc").atlasOutputDir = "out"
        val out = s.state                            // getState()
        assertEquals(listOf("m-svc", "z-svc"), out.subProjects.map { it.path })
    }

    @Test
    fun `loadState drops blank-path entries and de-duplicates by path (last wins)`() {
        val s = FlowableAtlasProjectSettings(null)
        val state = FlowableAtlasProjectSettings.State()
        state.subProjects = mutableListOf(
            FlowableAtlasProjectSettings.SubProjectState("").also { it.designPullTargets.add(target("DEV", "stray")) },
            FlowableAtlasProjectSettings.SubProjectState("svc").also { it.designPullTargets.add(target("DEV", "first")) },
            FlowableAtlasProjectSettings.SubProjectState("svc").also { it.designPullTargets.add(target("DEV", "second")) },
        )
        s.loadState(state)
        val subs = s.getState().subProjects
        assertEquals(listOf("svc"), subs.map { it.path })
        assertEquals(listOf("second"), subs.single().designPullTargets.single().appKeys)
    }

    @Test
    fun `dto defaults are the generation defaults and are scoped per sub-project`() {
        val s = settings()
        assertEquals(FlowableAtlasProjectSettings.DEFAULT_DTO_PACKAGE, s.dtoPackage)
        assertEquals(FlowableAtlasProjectSettings.DEFAULT_DTO_CLASS_SUFFIX, s.dtoClassSuffix)
        assertEquals(FlowableAtlasProjectSettings.DEFAULT_DTO_CLASS_PATTERN, s.dtoClassNamePattern)
        assertEquals("", s.dtoRenameFind)
        assertEquals("", s.dtoRenameReplace)
        assertEquals("", s.dtoSourceRootUrl)
        assertFalse(s.dtoPackagePerApp)
        // A blanked pattern is the default pattern, never an empty class name.
        s.dtoClassNamePattern = "   "
        assertEquals(FlowableAtlasProjectSettings.DEFAULT_DTO_CLASS_PATTERN, s.dtoClassNamePattern)

        s.scope("svc-a").dtoPackage = "com.acme.a.dto"
        s.scope("svc-a").dtoPackagePerApp = true
        assertEquals("com.acme.a.dto", s.scope("svc-a").dtoPackage)
        assertEquals(FlowableAtlasProjectSettings.DEFAULT_DTO_PACKAGE, s.scope("").dtoPackage)
        assertFalse(s.scope("").dtoPackagePerApp)
    }

    @Test
    fun `a sub-project left at the dto defaults is still pruned`() {
        val s = settings()
        s.scope("untouched").dtoPackage = FlowableAtlasProjectSettings.DEFAULT_DTO_PACKAGE
        s.scope("untouched-pattern").dtoClassNamePattern = FlowableAtlasProjectSettings.DEFAULT_DTO_CLASS_PATTERN
        s.scope("configured").dtoClassSuffix = "Bean"
        s.scope("patterned").dtoClassNamePattern = "{app}{name}Bean"
        assertEquals(listOf("configured", "patterned"), s.getState().subProjects.map { it.path })
    }


    @Test
    fun `each environment keeps its own workspace and apps`() {
        // A workspace key belongs to one server: DEV's does not exist on QA's Design instance, so one
        // project-wide value would be right for at most one environment and quietly wrong elsewhere.
        val s = FlowableAtlasProjectSettings(null)
        s.pullTarget("DEV").also { it.workspaceKey = "dev-ws"; it.appKeys = mutableListOf("appA") }
        s.pullTarget("QA").also { it.workspaceKey = "qa-ws"; it.appKeys = mutableListOf("appB", "appC") }
        assertEquals("dev-ws", s.pullTargetOrNull("DEV")!!.workspaceKey)
        assertEquals(listOf("appB", "appC"), s.pullTargetOrNull("QA")!!.appKeys)
        assertNull("an environment nothing was picked in has no entry", s.pullTargetOrNull("PROD"))
    }

    @Test
    fun `pullTarget creates on first use and returns the same entry afterwards`() {
        val s = FlowableAtlasProjectSettings(null)
        val first = s.pullTarget("QA")
        first.workspaceKey = "qa-ws"
        assertSame(first, s.pullTarget("QA"))
        assertEquals("qa-ws", s.pullTarget(" QA ").workspaceKey)   // the name is trimmed
    }

    @Test
    fun `an environment that was opened and left untouched never reaches the file`() {
        val s = FlowableAtlasProjectSettings(null)
        s.pullTarget("DEV").workspaceKey = "dev-ws"
        s.pullTarget("QA")                                  // opened, nothing picked
        assertEquals(listOf("DEV"), s.state.designPullTargets.map { it.environment })
    }
}
