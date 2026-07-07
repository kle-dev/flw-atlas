package com.flowable.atlas.expr

import com.flowable.atlas.expr.inject.ExpressionSegmentScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionSegmentScannerTest {

    private val both = setOf(ExpressionDialect.BACKEND, ExpressionDialect.FRONTEND)

    @Test
    fun backendSegmentInnerRange() {
        val segs = ExpressionSegmentScanner.scan("x=\${execution.id}!", both)
        assertEquals(1, segs.size)
        val s = segs[0]
        assertEquals(ExpressionDialect.BACKEND, s.dialect)
        assertEquals("execution.id", "x=\${execution.id}!".substring(s.innerStart, s.innerEnd))
    }

    @Test
    fun frontendSegmentInnerRange() {
        val text = "Hi {{ flw.sum(items) }}!"
        val segs = ExpressionSegmentScanner.scan(text, both)
        assertEquals(1, segs.size)
        assertEquals(ExpressionDialect.FRONTEND, segs[0].dialect)
        assertEquals(" flw.sum(items) ", text.substring(segs[0].innerStart, segs[0].innerEnd))
    }

    @Test
    fun multipleSegmentsSortedByPosition() {
        val text = "{{a}} and {{b}}"
        val segs = ExpressionSegmentScanner.scan(text, both)
        assertEquals(2, segs.size)
        assertTrue(segs[0].innerStart < segs[1].innerStart)
        assertEquals("a", text.substring(segs[0].innerStart, segs[0].innerEnd))
        assertEquals("b", text.substring(segs[1].innerStart, segs[1].innerEnd))
    }

    @Test
    fun emptyBodiesAreSkipped() {
        assertTrue(ExpressionSegmentScanner.scan("\${}", both).isEmpty())
        assertTrue(ExpressionSegmentScanner.scan("{{}}", both).isEmpty())
    }

    @Test
    fun dialectFilterHonored() {
        val text = "\${a} {{b}}"
        assertEquals(
            listOf(ExpressionDialect.BACKEND),
            ExpressionSegmentScanner.scan(text, setOf(ExpressionDialect.BACKEND)).map { it.dialect },
        )
        assertEquals(
            listOf(ExpressionDialect.FRONTEND),
            ExpressionSegmentScanner.scan(text, setOf(ExpressionDialect.FRONTEND)).map { it.dialect },
        )
    }

    @Test
    fun hashDelimiterIsBackend() {
        val segs = ExpressionSegmentScanner.scan("#{bean.method()}", both)
        assertEquals(1, segs.size)
        assertEquals(ExpressionDialect.BACKEND, segs[0].dialect)
    }
}
