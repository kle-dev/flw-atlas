package com.flowable.atlas

import com.flowable.atlas.intention.DataObjectBeanGenerator
import com.flowable.atlas.parsing.DataField
import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The generated bean is valid Java for every field type and for the names Java reserves — read by the
 * IDE's own Java parser, since the test runtime ships no compiler. A field called `default` produced
 * `private String default;`.
 */
class DataObjectBeanSourceTest : BasePlatformTestCase() {

    fun testTheGeneratedClassParsesForEveryTypeAndAwkwardName() {
        val types = listOf("STRING", "INTEGER", "LONG", "DOUBLE", "BOOLEAN", "DATE", "LOCAL-DATE", "JSON", "DATA-OBJECT")
        val fields = types.mapIndexed { i, type -> DataField("f$i", type) } + listOf(
            DataField("default", "STRING"), DataField("class", "JSON"), DataField("case", "LONG"),
            DataField("null", "BOOLEAN"), DataField("2nd value", "LONG"), DataField("_", "STRING"),
            DataField("bean", "STRING"),
        )
        val src = DataObjectBeanGenerator.generate("com.example.dto", "DemoDto", "DEMO-D010", fields)
        assertEquals(src, emptyList<String>(), errorsIn(src))
    }

    /** The check itself: the parser does reject a reserved word where an identifier belongs. */
    fun testTheParserRejectsAKeywordAsAFieldName() {
        assertFalse(errorsIn("class X { private String default; }").isEmpty())
    }

    private fun errorsIn(java: String): List<String> {
        val file = PsiFileFactory.getInstance(project).createFileFromText("DemoDto.java", JavaLanguage.INSTANCE, java)
        return PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
            .map { "${it.errorDescription} at ${it.textRange.startOffset}" }
    }
}
