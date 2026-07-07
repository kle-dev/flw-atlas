package com.flowable.atlas

import com.flowable.atlas.index.ModelUsageScanner
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelUsageScannerTest {

    @Test
    fun findsMethodInExpression() {
        val text = """<x flowable:expression="${'$'}{myBean.doWork()}"/>"""
        val ranges = ModelUsageScanner.findUsages(text, setOf("doWork"))
        assertTrue("should find doWork", ranges.any { text.substring(it.first, it.last + 1) == "doWork" })
        assertTrue("should not match a longer identifier", ModelUsageScanner.findUsages(text, setOf("Work")).isEmpty())
    }

    @Test
    fun findsDelegateFqnInClassAttribute() {
        val text = """<serviceTask flowable:class="com.acme.MyDelegate"/>"""
        val ranges = ModelUsageScanner.findUsages(text, setOf("com.acme.MyDelegate", "MyDelegate"))
        assertTrue(ranges.any { text.substring(it.first, it.last + 1) == "com.acme.MyDelegate" })
    }

    @Test
    fun ignoresPlainTextOutsideExpressions() {
        val text = """<documentation>myBean doWork happens here</documentation>"""
        assertTrue(ModelUsageScanner.findUsages(text, setOf("doWork")).isEmpty())
    }
}
