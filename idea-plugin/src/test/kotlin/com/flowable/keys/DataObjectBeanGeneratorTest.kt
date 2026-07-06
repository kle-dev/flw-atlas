package com.flowable.keys

import com.flowable.keys.index.DataField
import com.flowable.keys.intention.DataObjectBeanGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the data-object → Java bean generator (pure). */
class DataObjectBeanGeneratorTest {

    @Test fun generates_typed_pojo_with_getters_and_setters() {
        val src = DataObjectBeanGenerator.generate(
            "com.acme", "ShoppingList", "KYC-D010",
            listOf(DataField("label", "STRING"), DataField("count", "LONG"), DataField("payload", "JSON")),
        )
        assertTrue(src.contains("package com.acme;"))
        assertTrue(src.contains("public class ShoppingList {"))
        assertTrue(src.contains("private String label;"))
        assertTrue(src.contains("private Long count;"))
        assertTrue("KYC-D010 documented: $src", src.contains("KYC-D010"))
        assertTrue(src.contains("public String getLabel() {"))
        assertTrue(src.contains("public void setCount(Long count) {"))
    }

    @Test fun generates_fromContainer_mapper() {
        val src = DataObjectBeanGenerator.generate(
            null, "Order", "KYC-D010",
            listOf(DataField("label", "STRING"), DataField("count", "LONG"), DataField("due", "LOCAL-DATE")),
        )
        assertTrue("imports the container: $src",
            src.contains("import com.flowable.dataobject.api.runtime.DataObjectInstanceVariableContainer;"))
        assertTrue("static mapper: $src",
            src.contains("public static Order fromContainer(DataObjectInstanceVariableContainer container) {"))
        assertTrue("typed getString: $src", src.contains("bean.setLabel(container.getString(\"label\"));"))
        assertTrue("typed getLong: $src", src.contains("bean.setCount(container.getLong(\"count\"));"))
        assertTrue("typed getLocalDate: $src", src.contains("bean.setDue(container.getLocalDate(\"due\"));"))
    }

    @Test fun no_package_line_for_default_package() {
        val src = DataObjectBeanGenerator.generate(null, "Bean", "K", listOf(DataField("a", "STRING")))
        assertTrue(!src.contains("package "))
    }

    @Test fun classNameFor_builds_pascal_case() {
        assertEquals("ShoppingList", DataObjectBeanGenerator.classNameFor("Shopping List", "KYC-D010"))
        assertEquals("KYCD010", DataObjectBeanGenerator.classNameFor(null, "KYC-D010"))
        assertEquals("M123", DataObjectBeanGenerator.classNameFor(null, "123"))
    }

    @Test fun javaType_maps_logical_types() {
        assertEquals("String", DataObjectBeanGenerator.javaType("STRING"))
        assertEquals("Long", DataObjectBeanGenerator.javaType("LONG"))
        assertEquals("java.util.Date", DataObjectBeanGenerator.javaType("DATE"))
        assertEquals("Object", DataObjectBeanGenerator.javaType("DATA-OBJECT"))
        assertEquals("Object", DataObjectBeanGenerator.javaType(null))
    }
}
