package com.flowable.atlas.expr.eval

import com.flowable.atlas.expr.eval.PayloadScopePath.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadScopePathTest {

    private fun parsed(text: String): PayloadScopePath {
        val r = PayloadScopePath.parse(text)
        assertTrue("expected Ok for '$text' but got: ${(r as? PayloadScopePath.ParseResult.Err)?.message}", r is PayloadScopePath.ParseResult.Ok)
        return (r as PayloadScopePath.ParseResult.Ok).path
    }

    private fun error(text: String): String {
        val r = PayloadScopePath.parse(text)
        assertTrue("expected Err for '$text' but got: $r", r is PayloadScopePath.ParseResult.Err)
        return (r as PayloadScopePath.ParseResult.Err).message
    }

    @Test fun blankIsRoot() {
        assertTrue(parsed("").isRoot)
        assertTrue(parsed("   ").isRoot)
        assertEquals("", PayloadScopePath.ROOT.format())
    }

    @Test fun keysAndIndexes() {
        assertEquals(
            listOf(Segment.Key("orders"), Segment.Index(2), Segment.Key("items"), Segment.Index(0)),
            parsed("orders[2].items[0]").segments,
        )
        assertEquals(listOf(Segment.Key("customer"), Segment.Key("address")), parsed("customer.address").segments)
        assertEquals(listOf(Segment.Key("matrix"), Segment.Index(1), Segment.Index(0)), parsed("matrix[1][0]").segments)
    }

    @Test fun quotedKeys() {
        assertEquals(listOf(Segment.Key("a.b"), Segment.Key("c")), parsed("""["a.b"].c""").segments)
        assertEquals(listOf(Segment.Key("orders"), Segment.Key("odd key")), parsed("""orders['odd key']""").segments)
        assertEquals(listOf(Segment.Key("""say "hi"""")), parsed("""["say \"hi\""]""").segments)
    }

    @Test fun formatRoundTrip() {
        for (text in listOf("orders[2].items[0]", "customer.address", "matrix[1][0]", """["a.b"].c""", "\$temp.draft")) {
            assertEquals(text, parsed(text).format())
        }
        // non-identifier keys render bracket-quoted, then survive a re-parse
        val odd = PayloadScopePath(listOf(Segment.Key("a.b"), Segment.Index(2), Segment.Key("plain")))
        assertEquals("""["a.b"][2].plain""", odd.format())
        assertEquals(odd, parsed(odd.format()))
    }

    @Test fun malformedPaths() {
        assertTrue(error("orders[").contains("expected an index"))
        assertTrue(error("a..b").contains("expected a key"))
        assertTrue(error("[x]").contains("expected an index"))
        assertTrue(error(".a").contains("cannot start"))
        assertTrue(error("orders[2]items").contains("unexpected character"))
        assertTrue(error("""["unterminated""").contains("unterminated"))
        assertTrue(error("orders[12").contains("expected ']'"))
    }
}
