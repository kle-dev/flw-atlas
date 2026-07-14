package com.flowable.atlas

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.usage.FlowableRenameWarningProvider
import com.intellij.openapi.components.service
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Feature 4: renaming a Java symbol a model references gets a warning listener; an unreferenced one
 * does not.
 */
class FlowableRenameWarningTest : BasePlatformTestCase() {

    private val provider = FlowableRenameWarningProvider()

    fun testListenerOnlyForReferencedSymbol() {
        myFixture.addFileToProject(
            "models/P.bpmn",
            """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="P">""" +
                """<serviceTask id="t" flowable:expression="${'$'}{myBean.doWork()}"/></process></definitions>""",
        )
        myFixture.addFileToProject(
            "com/acme/MyBean.java",
            "package com.acme; public class MyBean { public void doWork() {} public void neverReferenced() {} }",
        )
        project.service<FlowableModelIndexService>().index()

        val bean = JavaPsiFacade.getInstance(project).findClass("com.acme.MyBean", GlobalSearchScope.allScope(project))!!
        val doWork = bean.findMethodsByName("doWork", false).first()
        val nope = bean.findMethodsByName("neverReferenced", false).first()

        assertNotNull("referenced method should get a rename listener", provider.getListener(doWork))
        assertNull("unreferenced method should not get a rename listener", provider.getListener(nope))
    }
}
