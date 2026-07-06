package com.flowable.keys

import com.flowable.keys.generate.ModelConstantsGenerator
import com.flowable.keys.generate.ModelInfo
import com.flowable.keys.model.ModelType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConstantsGeneratorTest {

    @Test
    fun generatesGroupedSanitizedConstants() {
        val models = listOf(
            ModelInfo(ModelType.PROCESS, "DEMO-P001", "DEMO-P001 Client Outreach"),
            ModelInfo(ModelType.CASE, "DEMO-C001", "Periodic Review"),
            ModelInfo(ModelType.DATA_OBJECT, "DEMO-D010", "Shopping List"),
            ModelInfo(ModelType.FORM, "form.with.dots", "Weird Form"),
        )
        val src = ModelConstantsGenerator.generate(models, "app.gen.FlowableModelKeys")

        assertTrue(src.contains("package app.gen;"))
        assertTrue(src.contains("public final class FlowableModelKeys"))
        assertTrue(src.contains("public static final class Process"))
        assertTrue(src.contains("public static final class Case"))
        assertTrue(src.contains("public static final class DataObject"))
        assertTrue(src.contains("public static final String DEMO_P001 = \"DEMO-P001\";"))
        assertTrue(src.contains("public static final String DEMO_D010 = \"DEMO-D010\";"))
        assertTrue(src.contains("public static final String FORM_WITH_DOTS = \"form.with.dots\";"))
        assertTrue(src.contains("/** DEMO-P001 Client Outreach */"))
    }

    @Test
    fun dedupesCollidingIdentifiers() {
        val models = listOf(
            ModelInfo(ModelType.FORM, "a-b", "x"),
            ModelInfo(ModelType.FORM, "a.b", "y"),
        )
        val src = ModelConstantsGenerator.generate(models, "Gen")
        assertTrue(src.contains("A_B = \"a-b\";"))
        assertTrue(src.contains("A_B_2 = \"a.b\";"))
    }

    @Test
    fun handlesDefaultPackageAndDigitLeadingKey() {
        val src = ModelConstantsGenerator.generate(
            listOf(ModelInfo(ModelType.PROCESS, "123start", "P")),
            "Gen",
        )
        assertFalse(src.contains("package "))
        assertTrue(src.contains("public final class Gen"))
        assertTrue(src.contains("_123START = \"123start\";"))
    }
}
