package com.flowable.atlas.explorer

import com.intellij.idea.AppMode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale

/**
 * How the explorer reaches the embedded viewer under Remote Development.
 *
 * On a Remote Dev host the [com.intellij.ui.jcef.JBCefBrowser] is a proxy: the page renders in the thin
 * client's Chromium, and every resource that page requests — a `file://` URL included — travels
 * host→client through the IDE protocol in 16 KB packets, one round trip each (the thin client's
 * `remoteDev.jcef.packet.size`). A 3.5 MB report is ~220 sequential round trips: half a minute on a
 * 150 ms link, for a file the host reads in milliseconds.
 *
 * The JS-query channel the page already uses for its copy/open bridges has no such ceiling — one
 * request, one response, whatever the size. So under Remote Dev the editor does not load the file. It
 * loads [Transfer.stub], a small page at the report's own URL, and the stub pulls the report through
 * `window.__atlasFetch` in a few large [Transfer.parts], all in flight at once: one round trip however
 * large the report. The stub keeps the page in the client's localStorage under its content
 * [Transfer.hash] — reopening the tab (or the IDE) transfers nothing, a regenerated report has a new hash
 * and is fetched once — and then replaces itself with the report, URL and window intact
 * (`frontend/remote-stub.html`, which also says why localStorage and not IndexedDB).
 *
 * Locally nothing changes: there `file://` is a disk read, and the generated file stays what it is for
 * browsers and the CLI — the stub is only the viewer's way of delivering it.
 */
internal object RemoteExplorerPage {

    /**
     * Characters per part: ~1 MB of UTF-16 per protocol message, and few enough messages that a small
     * report is one. A part never ends between the halves of a surrogate pair — the parts travel as
     * strings, and a lone surrogate would arrive as a replacement character.
     */
    const val PART_CHARS = 512 * 1024

    class Transfer(val hash: String, val parts: List<String>, val stub: String, val sizeLabel: String)

    fun isRemoteDevHost(): Boolean = AppMode.isRemoteDevHost()

    /** Read [page] and prepare it for the stub — off the EDT, a report can be several MB. */
    fun prepare(page: Path): Transfer {
        val bytes = Files.readAllBytes(page)
        return prepare(String(bytes, Charsets.UTF_8), sha256(bytes), bytes.size.toLong())
    }

    internal fun prepare(html: String, hash: String, sizeBytes: Long): Transfer {
        val parts = split(html)
        val size = sizeLabel(sizeBytes)
        val stub = asset("remote-stub.html")
            .replace("__ATLAS_HASH__", hash)
            .replace("__ATLAS_PARTS__", parts.size.toString())
            .replace("__ATLAS_SIZE__", size)
        return Transfer(hash, parts, stub, size)
    }

    internal fun split(html: String): List<String> {
        if (html.length <= PART_CHARS) return listOf(html)
        val parts = ArrayList<String>()
        var start = 0
        while (start < html.length) {
            var end = minOf(start + PART_CHARS, html.length)
            if (end < html.length && html[end - 1].isHighSurrogate()) end--
            parts.add(html.substring(start, end))
            start = end
        }
        return parts
    }

    /** What the stub's card says while it waits: `587 KB`, `3.5 MB`. */
    internal fun sizeLabel(bytes: Long): String =
        if (bytes >= 1_000_000) String.format(Locale.ROOT, "%.1f MB", bytes / 1e6)
        else String.format(Locale.ROOT, "%d KB", (bytes + 999) / 1000)

    internal fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun asset(name: String): String =
        (RemoteExplorerPage::class.java.getResourceAsStream("/frontend/$name")
            ?: error("frontend asset /frontend/$name not on the classpath"))
            .use { it.readBytes().toString(Charsets.UTF_8) }
}
