package com.flowable.atlas.usage

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usageView.UsageInfo
import java.util.Collections

/** Find Usages on a process's own `id`: the call activity that calls it, the Java that starts it — not itself. */
class ModelKeyFindUsagesTest : BasePlatformTestCase() {

    fun testAProcessKeyFindsItsCallersInModelsAndJava() {
        val (id, handler) = processWithCallers()

        val found = mutableListOf<UsageInfo>()
        handler.processElementUsages(id, { found.add(it); true }, FindUsagesOptions(project))
        assertCallersFound(found)
    }

    /**
     * Find Usages runs on a background thread, and the model files are read there *without* the read lock:
     * one read action around every model and archive made each keystroke wait for the whole scan. Only a
     * file with a hit takes the lock, for its PSI.
     */
    fun testTheModelScanHoldsNoReadLock() {
        val (id, handler) = processWithCallers()
        val options = FindUsagesOptions(project)
        val lockedWhileReading = Collections.synchronizedList(ArrayList<Boolean>())
        ModelReferenceScan.onUnlockedFile = { lockedWhileReading.add(ApplicationManager.getApplication().isReadAccessAllowed) }
        try {
            val found = Collections.synchronizedList(ArrayList<UsageInfo>())
            val run = ApplicationManager.getApplication().executeOnPooledThread {
                handler.processElementUsages(id, { found.add(it); true }, options)
            }
            PlatformTestUtil.waitForFuture(run, 30_000)
            assertTrue("the scan visited the models", lockedWhileReading.isNotEmpty())
            assertFalse("no model was read under the read lock: $lockedWhileReading", true in lockedWhileReading)
            assertCallersFound(ReadAction.computeBlocking<List<UsageInfo>, RuntimeException> { found.toList() })
        } finally {
            ModelReferenceScan.onUnlockedFile = null
        }
    }

    /** Cancel stops the scan between files, instead of after it. */
    fun testCancelStopsTheModelScan() {
        val (id, handler) = processWithCallers()
        val indicator = EmptyProgressIndicator()
        ModelReferenceScan.onUnlockedFile = { indicator.cancel() }
        try {
            assertThrows(ProcessCanceledException::class.java) {
                ProgressManager.getInstance().runProcess(
                    { handler.processElementUsages(id, { true }, FindUsagesOptions(project)) }, indicator,
                )
            }
        } finally {
            ModelReferenceScan.onUnlockedFile = null
        }
    }

    private fun processWithCallers(): Pair<XmlAttributeValue, FindUsagesHandler> {
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
        return id to factory.createFindUsagesHandler(id, false)!!
    }

    private fun assertCallersFound(found: List<UsageInfo>) {
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
