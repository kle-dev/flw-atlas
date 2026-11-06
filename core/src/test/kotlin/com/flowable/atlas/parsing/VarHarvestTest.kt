package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Ctx
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The attribute patterns of [VarHarvest] start with their literal and keep the word boundary as a
 * lookbehind after it — for speed only. These pin that the rewrite matches exactly what the old
 * `\bname=` patterns matched: the boundary still holds, a namespace prefix still counts, and names still
 * come out in document order.
 */
class VarHarvestTest {

    private fun directed(raw: String): List<String> {
        val ctx = Ctx()
        VarHarvest.collectDirectedVars(ctx, raw, listOf("DEMO-P001"))
        return ctx.varSites.map { "${it["via"]}:${it["variable"]}" }
    }

    private fun declared(raw: String): List<String> {
        val ctx = Ctx()
        VarHarvest.collectDeclaredVars(ctx, raw, listOf("DEMO-P001"))
        return ctx.varUse.keys.toList()
    }

    @Test
    fun theWordBoundaryStillHolds() {
        assertEquals(emptyList<String>(), directed("""<x xelementVariable="a" xcounterVariable="b" xinitiatorVariableName="c"/>"""))
        assertEquals(emptyList<String>(), declared("""<x fooresultVariableName="b" xvariableName="c" elementVariableName="d"/>"""))
    }

    @Test
    fun aNamespacedOrBareAttributeIsHarvested() {
        assertEquals(
            listOf("multiInstanceElement:item", "multiInstanceCounter:i", "multiInstanceCounter:n",
                "multiInstanceCollection:c", "multiInstanceCollection:d"),
            directed("""<m flowable:elementVariable="item" flowable:elementIndexVariable="i" counterVariable="n"
                        flowable:collection="c" collection="d"/>"""),
        )
        assertEquals(listOf("r", "item", "starter", "c"),
            declared("""<t flowable:resultVariableName="r" flowable:elementVariable="item" flowable:collection="c"
                        flowable:initiatorVariableName="starter"/>"""))
    }

    /** One pattern over the shared literal keeps the order the attributes are written in. */
    @Test
    fun countersComeOutInDocumentOrder() {
        assertEquals(
            listOf("multiInstanceCounter:a", "multiInstanceCounter:b", "multiInstanceCounter:c"),
            directed("""<x counterVariable="a"/><y elementIndexVariable="b"/><z counterVariable="c"/>"""),
        )
        assertEquals(listOf("a", "b", "c", "d"),
            declared("""<x variableName="a"/><y counterVariable="b"/><z additionalVariableName="c" collectionVariable="d"/>"""))
    }

    @Test
    fun aDelegateClassNeedsItsNamespace() {
        val found = { raw: String -> Constants.DELEGATE_CLASS_RE.findAll(raw).map { it.groupValues[1] }.toList() }
        assertEquals(listOf("com.example.A", "com.example.B"),
            found("""<s flowable:class="com.example.A"/><s activiti:class="com.example.B"/><s class="com.example.C"/><s x:class="com.example.D"/>"""))
    }
}
