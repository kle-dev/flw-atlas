package com.flowable.atlas.explorer

import com.flowable.atlas.model.ModelType

/**
 * The explorer page's own URL grammar, written from the IDE. A node route is `#<encodeURIComponent(id)>`
 * where the id is `<type>:<key>` in the graph's type vocabulary — `ModelType.id` is that vocabulary.
 */
object ExplorerRoutes {

    /** `process%3ADEMO-P001` — the hash (without `#`) that opens the page of [type] `:` [key]. */
    fun node(type: ModelType, key: String): String = encodeUriComponent("${type.id}:$key")

    /**
     * JavaScript's `encodeURIComponent`, minus the four marks it leaves alone (`!*'()`) — every one of
     * those is a character a page route can do without and a JS string literal is happier without.
     */
    fun encodeUriComponent(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c in 0x30..0x39 || c in 0x41..0x5a || c in 0x61..0x7a || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xf])
            }
        }
        return sb.toString()
    }
}
