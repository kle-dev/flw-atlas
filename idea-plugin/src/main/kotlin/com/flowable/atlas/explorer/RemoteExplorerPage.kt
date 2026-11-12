package com.flowable.atlas.explorer

import com.intellij.idea.AppMode
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.zip.GZIPOutputStream

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
 * `window.__atlasFetch` in [Transfer.parts], a few in flight at a time, and says how far it has got. The
 * stub keeps the page in the client's localStorage under its content [Transfer.hash] — reopening the tab
 * (or the IDE) transfers nothing, a regenerated report has a new hash and is fetched once — and then
 * replaces itself with the report, URL and window intact (`frontend/remote-stub.html`, which also says why
 * localStorage and not IndexedDB).
 *
 * The report travels **gzipped, as Base64**, and the stub keeps only a few parts in flight. Nothing in the
 * platform caps a query's answer (2026.2 bytecode: one rd message per answer, raw UTF-16, a 300 MB frame
 * limit), but every answer joins one ordered, unpaced queue shared by the whole IDE session, and an answer
 * can be lost without either callback firing. A 17.8 MB report asked for as 35 parts at once — ~37 MB of
 * UTF-16 in that queue — never arrived. The report is mostly its JSON island, which compresses six to
 * eight times, so the same report is now ~3 MB in a dozen parts. The compressed form is also what the stub
 * caches, which keeps a report of that size under Chromium's localStorage quota. Base64 is ASCII, so a part
 * boundary can fall anywhere.
 *
 * Locally nothing changes: there `file://` is a disk read, and the generated file stays what it is for
 * browsers and the CLI — the stub is only the viewer's way of delivering it.
 */
internal object RemoteExplorerPage {

    /**
     * Base64 characters per part: small enough that the stub's progress moves and no single answer is a
     * burden on the connection, large enough that a big report is still only a dozen or so round trips.
     */
    const val PART_CHARS = 256 * 1024

    /** [sizeLabel] states the report; [wireChars] is what actually crosses the connection. */
    class Transfer(val hash: String, val parts: List<String>, val stub: String, val sizeLabel: String, val wireChars: Int)

    fun isRemoteDevHost(): Boolean = AppMode.isRemoteDevHost()

    /** Read [page] and prepare it for the stub — off the EDT, a report can be several MB. */
    fun prepare(page: Path): Transfer = prepare(Files.readAllBytes(page))

    internal fun prepare(bytes: ByteArray, hash: String = sha256(bytes)): Transfer {
        val wire = Base64.getEncoder().encodeToString(gzip(bytes))
        val parts = split(wire)
        val size = sizeLabel(bytes.size.toLong())
        val stub = asset("remote-stub.html")
            .replace("__ATLAS_HASH__", hash)
            .replace("__ATLAS_PARTS__", parts.size.toString())
            .replace("__ATLAS_SIZE__", size)
            .replace("__ATLAS_WIRE__", wire.length.toString())
        return Transfer(hash, parts, stub, size, wire.length)
    }

    internal fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream(bytes.size / 4 + 64).also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    internal fun split(wire: String): List<String> =
        if (wire.length <= PART_CHARS) listOf(wire) else wire.chunked(PART_CHARS)

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
