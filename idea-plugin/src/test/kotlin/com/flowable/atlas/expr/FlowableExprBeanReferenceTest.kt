package com.flowable.atlas.expr

import com.intellij.psi.PsiClass
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The root of a backend expression that names a Spring bean is a reference to the bean's class — in
 * the playground and inside an injected model expression; an engine root or a variable is not.
 */
class FlowableExprBeanReferenceTest : BasePlatformTestCase() {

    private fun addOrderService() {
        myFixture.addFileToProject(
            "com/demo/OrderService.java",
            "package com.demo; /** Orders. */ public class OrderService { public int total() { return 1; } }",
        )
    }

    fun testTheRootResolvesToTheProjectClassInThePlayground() {
        addOrderService()
        myFixture.configureByText("t.flowable-be", "orderSer<caret>vice.total() > 10")
        val target = myFixture.elementAtCaret
        assertTrue("expected the bean's class, got $target", target is PsiClass && target.qualifiedName == "com.demo.OrderService")
    }

    fun testTheRootResolvesInsideAnInjectedBpmnExpression() {
        addOrderService()
        myFixture.configureByText(
            "task.bpmn20.xml",
            """<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn">
                 <process id="P1">
                   <serviceTask id="t" flowable:expression="${'$'}{orderSer<caret>vice.total()}"/>
                 </process>
               </definitions>""",
        )
        val target = myFixture.elementAtCaret
        assertTrue("expected the bean's class through the injection, got $target", target is PsiClass && target.qualifiedName == "com.demo.OrderService")
    }

    fun testAnEngineRootHasNoReferenceAndAVariableResolvesToNothing() {
        addOrderService()
        myFixture.configureByText("t.flowable-be", "execution.getVariable('order') != null && order.total > 10")
        val text = myFixture.file.text
        assertNull("an engine root is documented by the catalog, not a class", myFixture.file.findReferenceAt(text.indexOf("execution") + 2))
        val order = myFixture.file.findReferenceAt(text.indexOf("order.total") + 1)
        assertNotNull("a root that might be a bean carries a reference", order)
        assertNull("…that resolves to nothing when no such class exists — and is soft, so nothing is painted", order!!.resolve())
        assertTrue(order.isSoft)
    }

    fun testAPropertyKeyResolvesToEveryProfileThatSetsIt() {
        myFixture.addFileToProject("src/main/resources/application.properties", "server.port=8080\ncrm.baseUrl=https://crm\n")
        myFixture.addFileToProject("src/main/resources/application-k8s.yml", "crm:\n  base-url: https://k8s\n")
        myFixture.addFileToProject("src/test/resources/application-test.properties", "crm.base-url=test\n")
        myFixture.configureByText("t.flowable-be", "environment.getProperty('crm.base-<caret>url', '')")
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset) as com.intellij.psi.PsiPolyVariantReference
        val files = ref.multiResolve(false).mapNotNull { it.element?.containingFile?.name }.sorted()
        assertEquals("both profiles of the application, not the test one", listOf("application-k8s.yml", "application.properties"), files)
    }
}
