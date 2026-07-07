package com.flowable.atlas

import com.flowable.atlas.generate.ConstantFormat
import com.flowable.atlas.generate.ConstantNaming
import com.flowable.atlas.generate.ModelConstantsGenerator
import com.flowable.atlas.generate.ModelInfo
import com.flowable.atlas.model.ModelType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConstantsGeneratorTest {

    private val sample = listOf(
        ModelInfo(ModelType.PROCESS, "P_0001", "Order Fulfilment"),
        ModelInfo(ModelType.CASE, "C_0001", "Periodic Review"),
        ModelInfo(ModelType.DATA_OBJECT, "D_0010", "Shopping List"),
    )

    @Test fun keyNaming() {
        val src = ModelConstantsGenerator.generate(sample, "app.gen.FlowableModelKeys", ConstantNaming.KEY, ConstantFormat.CLASS)
        assertTrue(src.contains("package app.gen;"))
        assertTrue(src.contains("public static final class Process"))
        assertTrue(src.contains("public static final String P_0001 = \"P_0001\";"))
        assertTrue(src.contains("public static final String D_0010 = \"D_0010\";"))
        assertTrue(src.contains("/** Order Fulfilment */"))
    }

    @Test fun nameAndKeyIsDefault() {
        val src = ModelConstantsGenerator.generate(sample, "Gen")
        assertTrue("name+key: $src", src.contains("public static final String ORDER_FULFILMENT_P_0001 = \"P_0001\";"))
        assertTrue(src.contains("public static final String SHOPPING_LIST_D_0010 = \"D_0010\";"))
    }

    @Test fun nameNaming() {
        val src = ModelConstantsGenerator.generate(sample, "Gen", ConstantNaming.NAME, ConstantFormat.CLASS)
        assertTrue("name-only: $src", src.contains("public static final String ORDER_FULFILMENT = \"P_0001\";"))
        assertTrue(src.contains("public static final String PERIODIC_REVIEW = \"C_0001\";"))
    }

    @Test fun longNameIsCappedOnWordBoundary() {
        val models = listOf(ModelInfo(ModelType.PROCESS, "P_0009", "Order Fulfilment For Corporate Onboarding Of Premium Customers"))
        val src = ModelConstantsGenerator.generate(models, "Gen", ConstantNaming.NAME_AND_KEY, ConstantFormat.CLASS)
        assertTrue("capped prefix kept: $src", src.contains("ORDER_FULFILMENT_FOR_CORPORATE_P_0009"))
        assertFalse("tail words dropped: $src", src.contains("ONBOARDING"))
        assertFalse(src.contains("PREMIUM"))
    }

    @Test fun enumFormat() {
        val src = ModelConstantsGenerator.generate(sample, "Gen", ConstantNaming.NAME_AND_KEY, ConstantFormat.ENUM)
        assertTrue("enum decl: $src", src.contains("public enum Process {"))
        assertTrue("entry: $src", src.contains("ORDER_FULFILMENT_P_0001(\"P_0001\", \"Order Fulfilment\")"))
        assertTrue(src.contains("public String key() {"))
        assertTrue(src.contains("public String displayName() {"))
    }

    @Test fun dedupesCollidingNames() {
        val models = listOf(
            ModelInfo(ModelType.FORM, "a", "Duplicate"),
            ModelInfo(ModelType.FORM, "b", "Duplicate"),
        )
        val src = ModelConstantsGenerator.generate(models, "Gen", ConstantNaming.NAME, ConstantFormat.CLASS)
        assertTrue(src.contains("DUPLICATE = \"a\";"))
        assertTrue(src.contains("DUPLICATE_2 = \"b\";"))
    }

    @Test fun handlesDefaultPackageAndDigitLeadingKey() {
        val src = ModelConstantsGenerator.generate(
            listOf(ModelInfo(ModelType.PROCESS, "123start", "P")),
            "Gen", ConstantNaming.KEY, ConstantFormat.CLASS,
        )
        assertFalse(src.contains("package "))
        assertTrue(src.contains("public final class Gen"))
        assertTrue(src.contains("_123START = \"123start\";"))
    }
}
