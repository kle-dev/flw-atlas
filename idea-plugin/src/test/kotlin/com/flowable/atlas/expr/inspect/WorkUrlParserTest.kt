package com.flowable.atlas.expr.inspect

import com.flowable.atlas.expr.inspect.InspectClient.ScopeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkUrlParserTest {

    @Test fun hashRouteCase() {
        val p = WorkUrlParser.parse("https://host/flowable-work/#/work/all/case/CAS-9f3a")
        assertEquals("https://host/flowable-work", p.baseUrl)
        assertEquals(ScopeType.CMMN, p.scopeType)
        assertEquals("CAS-9f3a", p.scopeId)
        assertNull(p.subScopeId)
    }

    @Test fun hashRouteProcess() {
        val p = WorkUrlParser.parse("https://host/flowable-work/#/work/all/process/PRC-1b2c")
        assertEquals(ScopeType.BPMN, p.scopeType)
        assertEquals("PRC-1b2c", p.scopeId)
    }

    @Test fun hashRouteTask() {
        val p = WorkUrlParser.parse("https://host/flowable-work/#/work/all/task/TSK-7d8e")
        assertEquals(ScopeType.TASK, p.scopeType)
        assertEquals("TSK-7d8e", p.scopeId)
    }

    @Test fun nestedTaskInCaseBecomesSubScope() {
        val p = WorkUrlParser.parse("https://host/flowable-work/#/work/all/case/CAS-1/task/TSK-2")
        assertEquals(ScopeType.CMMN, p.scopeType)
        assertEquals("CAS-1", p.scopeId)
        assertEquals("TSK-2", p.subScopeId)
    }

    @Test fun trailingTabSegmentIsIgnored() {
        val p = WorkUrlParser.parse("https://host/flowable-work/#/work/open/case/CAS-1/tab/workForm")
        assertEquals(ScopeType.CMMN, p.scopeType)
        assertEquals("CAS-1", p.scopeId)
        assertNull(p.subScopeId)
    }

    @Test fun caseViewWithExplicitType() {
        val p = WorkUrlParser.parse("https://host/flowable-work/#/case-view/case/CAS-1")
        assertEquals(ScopeType.CMMN, p.scopeType)
        assertEquals("CAS-1", p.scopeId)
    }

    @Test fun caseViewShortForm() {
        val p = WorkUrlParser.parse("https://host/flowable-work/#/case-view/CAS-1")
        assertEquals(ScopeType.CMMN, p.scopeType)
        assertEquals("CAS-1", p.scopeId)
    }

    @Test fun caseViewShortFormWithTask() {
        val p = WorkUrlParser.parse("https://host/flowable-work/#/case-view/CAS-1/task/TSK-2")
        assertEquals(ScopeType.CMMN, p.scopeType)
        assertEquals("CAS-1", p.scopeId)
        assertEquals("TSK-2", p.subScopeId)
    }

    @Test fun ssoQueryForm() {
        val p = WorkUrlParser.parse("https://host/flowable-work?app=work&filter=all&type=process&id=PRC-1")
        assertEquals("https://host/flowable-work", p.baseUrl)
        assertEquals(ScopeType.BPMN, p.scopeType)
        assertEquals("PRC-1", p.scopeId)
    }

    @Test fun componentQueryCaseInstanceId() {
        val p = WorkUrlParser.parse("https://host/flowable-work?caseInstanceId=CAS-1")
        assertEquals(ScopeType.CMMN, p.scopeType)
        assertEquals("CAS-1", p.scopeId)
    }

    @Test fun componentQueryCaseWithTask() {
        val p = WorkUrlParser.parse("https://host/flowable-work?caseInstanceId=CAS-1&taskInstanceId=TSK-2")
        assertEquals(ScopeType.CMMN, p.scopeType)
        assertEquals("CAS-1", p.scopeId)
        assertEquals("TSK-2", p.subScopeId)
    }

    @Test fun componentQueryTaskId() {
        val p = WorkUrlParser.parse("https://host/flowable-work?taskId=TSK-1")
        assertEquals(ScopeType.TASK, p.scopeType)
        assertEquals("TSK-1", p.scopeId)
    }

    @Test fun rootContextPathAndPort() {
        val p = WorkUrlParser.parse("http://localhost:8090/#/work/all/task/TSK-1")
        assertEquals("http://localhost:8090", p.baseUrl)
        assertEquals(ScopeType.TASK, p.scopeType)
        assertEquals("TSK-1", p.scopeId)
    }

    @Test fun urlEncodedIdIsDecoded() {
        val p = WorkUrlParser.parse("https://host/flowable-work?type=case&id=CAS%2F1")
        assertEquals(ScopeType.CMMN, p.scopeType)
        assertEquals("CAS/1", p.scopeId)
    }

    @Test fun baseUrlOnlyWhenNoRoute() {
        val p = WorkUrlParser.parse("https://host/flowable-work/")
        assertEquals("https://host/flowable-work", p.baseUrl)
        assertNull(p.scopeId)
        assertTrue(p.hasAny)
    }

    @Test fun bareIdOrPartialTypingIsIgnored() {
        assertFalse(WorkUrlParser.parse("CAS-1").hasAny)
        assertFalse(WorkUrlParser.parse("  ").hasAny)
        assertFalse(WorkUrlParser.parse("flowable-work/case/CAS-1").hasAny)
    }
}
