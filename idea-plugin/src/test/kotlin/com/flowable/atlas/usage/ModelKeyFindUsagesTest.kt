package com.flowable.atlas.usage

import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usageView.UsageInfo

/** Find Usages on a process's own `id`: the call activity that calls it, the Java that starts it — not itself. */
class ModelKeyFindUsagesTest : BasePlatformTestCase() {

    fun testAProcessKeyFindsItsCallersInModelsAndJava() {
        myFixture.addFileToProject(
            "org/flowable/engine/RuntimeService.java",
            "package org.flowable.engine; public interface RuntimeService { Object startProcessInstanceByKey(String key); }",
        )
        val target = myFixture.addFileToProject(
            "models/target.bpmn20.xml",
            """<definitions><process id="DEMO-P100" name="Target DEMO-P100"><userTask id="t"/></process></definitions>""",
        )
        myFixture.addFileToProject(
            "models/caller.bpmn20.xml",
            """<definitions><process id="DEMO-P200"><callActivity id="c" calledElement="DEMO-P100"/></process></definitions>""",
        )
        myFixture.addFileToProject(
            "demo/Starter.java",
            "package demo; class Starter { void m(org.flowable.engine.RuntimeService rs) { rs.startProcessInstanceByKey(\"DEMO-P100\"); } }",
        )
        val id = PsiTreeUtil.findChildrenOfType(target, XmlAttributeValue::class.java).first { it.value == "DEMO-P100" }
        val factory = ModelKeyFindUsagesHandlerFactory()
        assertTrue("a process's own id is a find-usages target", factory.canFindUsages(id))
        val handler = factory.createFindUsagesHandler(id, false)!!

        val found = mutableListOf<UsageInfo>()
        handler.processElementUsages(id, { found.add(it); true }, FindUsagesOptions(project))
        val files = found.map { it.virtualFile?.name }
        assertTrue("the call activity is a usage: $files", "caller.bpmn20.xml" in files)
        assertTrue("the Java call site is a usage: $files", "Starter.java" in files)
        assertFalse("the declaration itself is not a usage: $files", "target.bpmn20.xml" in files)
    }

    fun testAnAttributeThatIsNotAModelsIdIsNoTarget() {
        val f = myFixture.addFileToProject(
            "models/p.bpmn20.xml",
            """<definitions><process id="DEMO-P1"><userTask id="task1" name="T"/></process></definitions>""",
        )
        val taskId = PsiTreeUtil.findChildrenOfType(f, XmlAttributeValue::class.java).first { it.value == "task1" }
        assertFalse(ModelKeyFindUsagesHandlerFactory().canFindUsages(taskId))
    }
}
