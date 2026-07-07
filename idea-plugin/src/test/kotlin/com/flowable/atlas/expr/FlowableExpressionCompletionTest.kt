package com.flowable.atlas.expr

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Completion in a standalone expression fragment (as edited in the playground / a `.flowable-be` /
 * `.flowable-fe` scratch file). The fragment is the expression *body* — no `${…}` / `{{…}}` wrapper.
 */
class FlowableExpressionCompletionTest : BasePlatformTestCase() {

    fun testBackendNamespaceFunctions() {
        myFixture.configureByText("t.flowable-be", "vars:<caret>")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: emptyList()
        assertTrue(items.containsAll(listOf("get", "contains", "equals")))
    }

    fun testBackendRootOffersRootsNamespacesAndNoPrefixFunctions() {
        myFixture.configureByText("t.flowable-be", "<caret>")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("roots", items.contains("execution"))
        assertTrue("namespaces", items.contains("date"))
        assertTrue("no-prefix functions", items.contains("listOf"))
    }

    fun testFrontendFlwMembers() {
        myFixture.configureByText("t.flowable-fe", "flw.<caret>")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: emptyList()
        assertTrue(items.containsAll(listOf("sum", "formatDate", "round")))
    }

    fun testFrontendRoots() {
        myFixture.configureByText("t.flowable-fe", "<caret>")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: emptyList()
        assertTrue(items.contains("flw"))
        assertTrue(items.contains("\$currentUser"))
    }

    fun testBackendBeanMethodsAndPropertiesOfferedAfterDot() {
        myFixture.addFileToProject(
            "OrderService.java",
            """
            public class OrderService {
                public String process(String id) { return id; }
                public int getCount() { return 0; }
                public boolean isActive() { return true; }
                private void hidden() {}
                public static void util() {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText("t.flowable-be", "orderService.<caret>")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("public method", items.contains("process"))
        assertTrue("getter method", items.contains("getCount"))
        assertTrue("getter-derived property", items.contains("count"))   // getCount -> count
        assertTrue("boolean is-property", items.contains("active"))      // isActive -> active
        assertFalse("private hidden", items.contains("hidden"))
        assertFalse("static excluded", items.contains("util"))
    }

    fun testReferencedIdentifiersLabelledBeanOnlyWhenTheyResolveToAProjectClass() {
        myFixture.addFileToProject("OrderService.java", "public class OrderService { public String process(String s){return s;} }")
        // A model whose expressions reference: orderService (a project class), process/order (not classes),
        // and the catalog function `now` / namespace `date` (must not appear as spurious beans).
        myFixture.addFileToProject(
            "models/P.bpmn20.xml",
            """<?xml version="1.0"?>
               <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn">
                 <process id="P">
                   <serviceTask id="t" flowable:expression="${'$'}{orderService.process(order)}"/>
                   <sequenceFlow id="f"><conditionExpression>${'$'}{date:now()}</conditionExpression></sequenceFlow>
                 </process>
               </definitions>""",
        )
        myFixture.configureByText("t.flowable-be", "<caret>")
        myFixture.completeBasic()
        val elements = myFixture.lookupElements ?: emptyArray()
        fun typeOf(s: String): String? = elements.firstOrNull { it.lookupString == s }
            ?.let { val p = LookupElementPresentation(); it.renderElement(p); p.typeText }

        assertEquals("resolvable project class → bean", "bean", typeOf("orderService"))
        assertEquals("non-class identifier → referenced, not bean", "referenced", typeOf("order"))
        // `now` is a catalog function local name and must be excluded from the referenced-identifier dump.
        assertTrue("catalog function must not show as bean", typeOf("now") != "bean")
    }

    fun testProjectVariablesOfferedAtRoot() {
        myFixture.addFileToProject(
            "models/P1.bpmn20.xml",
            """<?xml version="1.0"?>
               <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                            xmlns:flowable="http://flowable.org/bpmn">
                 <process id="P1"><dataObject id="orderTotal" name="orderTotal"/></process>
               </definitions>""",
        )
        myFixture.configureByText("t.flowable-be", "<caret>")
        myFixture.completeBasic()
        // orderTotal is a project variable and should be offered at the root position.
        assertTrue(myFixture.lookupElementStrings?.contains("orderTotal") == true)
    }
}
