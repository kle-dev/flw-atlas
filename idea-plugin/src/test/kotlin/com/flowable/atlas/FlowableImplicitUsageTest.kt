package com.flowable.atlas

import com.flowable.atlas.usage.FlowableImplicitUsageProvider
import com.flowable.atlas.usage.FlowableModelUsageSearcher
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usages.Usage
import com.intellij.usages.rules.UsageInFile

class FlowableImplicitUsageTest : BasePlatformTestCase() {

    private val provider = FlowableImplicitUsageProvider()

    private fun findClass(fqn: String) =
        JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))!!

    fun testDelegateClassIsImplicitlyUsed() {
        myFixture.addFileToProject(
            "models/P.bpmn",
            """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="P">""" +
                """<serviceTask id="t" flowable:class="com.acme.MyDelegate"/></process></definitions>""",
        )
        myFixture.addFileToProject("com/acme/MyDelegate.java", "package com.acme; public class MyDelegate {}")
        assertTrue(provider.isImplicitUsage(findClass("com.acme.MyDelegate")))
    }

    fun testExpressionMethodAndBeanAreImplicitlyUsed() {
        myFixture.addFileToProject(
            "models/P2.bpmn",
            """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="P2">""" +
                """<serviceTask id="t" flowable:expression="${'$'}{myBean.doWork()}"/></process></definitions>""",
        )
        myFixture.addFileToProject(
            "com/acme/MyBean.java",
            "package com.acme; public class MyBean { public void doWork() {} public void neverReferenced() {} }",
        )
        val bean = findClass("com.acme.MyBean")
        assertTrue("bean class must be implicitly used", provider.isImplicitUsage(bean))
        assertTrue("doWork() must be implicitly used", provider.isImplicitUsage(bean.findMethodsByName("doWork", false).first()))
        assertFalse("neverReferenced() must NOT be implicitly used", provider.isImplicitUsage(bean.findMethodsByName("neverReferenced", false).first()))
    }

    fun testModelUsageSearcherReportsModelFile() {
        myFixture.addFileToProject(
            "models/P.bpmn",
            """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="P">""" +
                """<serviceTask id="t" flowable:expression="${'$'}{myBean.doWork()}"/></process></definitions>""",
        )
        myFixture.addFileToProject("com/acme/MyBean.java", "package com.acme; public class MyBean { public void doWork() {} }")
        val method = findClass("com.acme.MyBean").findMethodsByName("doWork", false).first()

        val found = mutableListOf<Usage>()
        FlowableModelUsageSearcher().processElementUsages(method, { found.add(it); true }, FindUsagesOptions(project))

        assertTrue(
            "the model-usage searcher should report P.bpmn as a usage of doWork()",
            found.any { (it as? UsageInFile)?.file?.name == "P.bpmn" },
        )
    }
}
