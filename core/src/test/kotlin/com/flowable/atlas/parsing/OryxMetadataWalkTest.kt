package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Ctx
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The legacy editor's `pathProperties` table (`{"id": "id", "url": "extraSettings.url"}`) sits on every
 * Oryx form body. It is bookkeeping, not a component — and it used to be one REST call per form.
 */
class OryxMetadataWalkTest {

    @Test
    fun theEditorsPropertyTableIsNotAComponentAndNotARestCall() {
        val ctx = Ctx()
        val form = ModelParsers.parseForm(
            """{"metadata":{"key":"DEMO-F001","name":"F","modelType":"form"},
                "pathProperties":{"id":"id","url":"extraSettings.url","label":"label"},
                "pathMappingProperties":{"id":"id","url":"extraSettings.url"},
                "rows":[[{"id":"go","type":"restButton","extraSettings":{"url":"/api/orders","method":"post"}},
                         {"id":"odd","url":"/not/a/component"}]]}""".toByteArray(),
            ctx, "f.form",
        )
        @Suppress("UNCHECKED_CAST")
        val calls = form["restCalls"] as List<Map<String, Any?>>
        assertEquals(listOf("/api/orders"), calls.map { it["url"] })
        assertEquals(listOf("/api/orders"), ctx.restCalls.map { it["url"] })
        @Suppress("UNCHECKED_CAST")
        val fields = (form["fields"] as List<Map<String, Any?>>).map { it["id"] }
        assertEquals(listOf("go"), fields)
    }
}
