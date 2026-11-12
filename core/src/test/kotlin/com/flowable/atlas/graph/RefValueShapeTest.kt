package com.flowable.atlas.graph

import com.flowable.atlas.parsing.ModelParsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reference value arrives as a bare key or, from newer Design exports, as `{"id": …, "key": …}`.
 * Both name the same model, and the unwrapping happens once, in [Ctx.addRef], so no parser can turn
 * the map into a missing model called `{id=…, key=…}`.
 */
class RefValueShapeTest {

    private fun values(ctx: Ctx, rel: String) = ctx.refs.filter { it["rel"] == rel }.map { it["value"] }

    @Test
    fun aMapWithAKeyIsAReferenceToThatKey() {
        val ctx = Ctx()
        ctx.addRef("d", "document", "d.document", "document-edit-form", "form", mapOf("id" to "FORM_MODEL-1", "key" to "DEMO-F001"))
        ctx.addRef("d", "document", "d.document", "document-view-form", "form", "DEMO-F002")
        ctx.addRef("d", "document", "d.document", "document-create-form", "form", mapOf("id" to "FORM_MODEL-2"))
        assertEquals(listOf("DEMO-F001"), values(ctx, "document-edit-form"))
        assertEquals(listOf("DEMO-F002"), values(ctx, "document-view-form"))
        assertTrue("a map without a key is not a reference", values(ctx, "document-create-form").isEmpty())
    }

    @Test
    fun aDocumentModelsFormsResolveWhicheverShapeDesignWrote() {
        val ctx = Ctx()
        val info = ModelParsers.parseDocumentModel(
            """{"key": "DEMO-DOC", "name": "Doc",
                "forms": {"view": "DEMO-F001", "edit": {"id": "FORM_MODEL-9", "key": "DEMO-F002"}}}""".toByteArray(),
            ctx, "doc.document",
        )
        assertEquals(listOf("DEMO-F001"), values(ctx, "document-view-form"))
        assertEquals(listOf("DEMO-F002"), values(ctx, "document-edit-form"))
        // the page shows keys, whatever the export wrote
        assertEquals(mapOf("view" to "DEMO-F001", "edit" to "DEMO-F002"), info["forms"])
    }
}
