package com.flowable.atlas.generate.dto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DTO naming rules (pure): class name + suffix, per-app package segments and target paths.
 * DEMO-* placeholder keys — this repo is public.
 */
class DataObjectDtoPlannerTest {

    @Test fun class_name_derives_from_the_model_name_and_appends_the_suffix() {
        assertEquals("CustomerDto", DataObjectDtoPlanner.defaultClassName("Customer", "DEMO-D010", "Dto"))
        assertEquals("ShoppingListDto", DataObjectDtoPlanner.defaultClassName("Shopping List", "DEMO-D010", "Dto"))
        assertEquals("DEMOD010Dto", DataObjectDtoPlanner.defaultClassName(null, "DEMO-D010", "Dto"))
    }

    @Test fun an_empty_suffix_keeps_the_plain_name() {
        assertEquals("Customer", DataObjectDtoPlanner.defaultClassName("Customer", "DEMO-D010", ""))
        assertEquals("Customer", DataObjectDtoPlanner.defaultClassName("Customer", "DEMO-D010", "  "))
    }

    @Test fun the_suffix_is_never_doubled() {
        assertEquals("CustomerDto", DataObjectDtoPlanner.defaultClassName("Customer Dto", "DEMO-D010", "Dto"))
        assertEquals("CustomerDTO", DataObjectDtoPlanner.defaultClassName("Customer DTO", "DEMO-D010", "Dto"))
    }

    @Test fun package_segment_sanitises_an_app_key() {
        assertEquals("demoapp", DataObjectDtoPlanner.packageSegment("DEMO-App"))
        assertEquals("demoappv2", DataObjectDtoPlanner.packageSegment("DEMO App v2"))
        assertEquals("_2ndapp", DataObjectDtoPlanner.packageSegment("2nd-app"))
        assertEquals("_package", DataObjectDtoPlanner.packageSegment("package"))
        assertEquals("app", DataObjectDtoPlanner.packageSegment("---"))
    }

    @Test fun package_for_nests_only_when_asked() {
        assertEquals("com.acme.dto", DataObjectDtoPlanner.packageFor("com.acme.dto", "DEMO-App", false))
        assertEquals("com.acme.dto.demoapp", DataObjectDtoPlanner.packageFor("com.acme.dto", "DEMO-App", true))
        assertEquals("demoapp", DataObjectDtoPlanner.packageFor("", "DEMO-App", true))
        assertEquals("com.acme.dto", DataObjectDtoPlanner.packageFor("com.acme.dto", null, true))
    }

    @Test fun target_path_maps_the_package_to_folders() {
        assertEquals("com/acme/dto/CustomerDto.java", DataObjectDtoPlanner.targetPath("com.acme.dto", "CustomerDto"))
        assertEquals("CustomerDto.java", DataObjectDtoPlanner.targetPath("", "CustomerDto"))
    }

    @Test fun package_and_class_validation() {
        assertTrue(DataObjectDtoPlanner.isValidPackage(""))
        assertTrue(DataObjectDtoPlanner.isValidPackage("com.acme.flowable_dto"))
        assertFalse(DataObjectDtoPlanner.isValidPackage("com..acme"))
        assertFalse(DataObjectDtoPlanner.isValidPackage("com.acme."))
        assertFalse(DataObjectDtoPlanner.isValidPackage("com.9acme"))
        assertFalse(DataObjectDtoPlanner.isValidPackage("com.new.dto"))

        assertTrue(DataObjectDtoPlanner.isValidClassName("CustomerDto"))
        assertFalse(DataObjectDtoPlanner.isValidClassName("Customer Dto"))
        assertFalse(DataObjectDtoPlanner.isValidClassName("class"))
        assertFalse(DataObjectDtoPlanner.isValidClassName(""))
    }
}
