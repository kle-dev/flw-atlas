package com.flowable.atlas.expr.inspect

/**
 * Parses the auth-relevant request headers out of text copied from a browser's DevTools — either a
 * full "Copy as cURL" command or a block of raw `Name: value` header lines — so the playground's
 * "Paste session from browser" can replay the user's already-authenticated browser session against the
 * Inspect endpoint. This is the reliable alternative to the embedded-browser login ([InspectSignInDialog])
 * for apps behind SSO/OAuth2 that block embedded webviews (e.g. Microsoft Entra Conditional Access).
 *
 * Only a small allow-list of headers is kept — [ALLOWLIST] (`Cookie`, `Authorization`, the XSRF/CSRF
 * token headers, `X-Requested-With`) — because copying a request that already succeeded carries exactly
 * the credentials that authenticate it: the session cookie, any bearer/basic header, and the CSRF token
 * whose value must match the XSRF cookie (so a CSRF-protected POST survives). The target URL's origin is
 * surfaced as a best-effort [Parsed.baseUrl] hint.
 *
 * Pure and unit-tested — no IDE, PSI, network or EDT dependency.
 */
object CurlAuthParser {

    data class Parsed(val headers: LinkedHashMap<String, String>, val baseUrl: String?) {
        val hasAny: Boolean get() = headers.isNotEmpty()
    }

    private val EMPTY = Parsed(LinkedHashMap(), null)

    /** Header name (lower-case) → canonical casing to send. Everything else is dropped. */
    private val ALLOWLIST = mapOf(
        "cookie" to "Cookie",
        "authorization" to "Authorization",
        "x-xsrf-token" to "X-XSRF-TOKEN",
        "x-csrf-token" to "X-CSRF-TOKEN",
        "x-requested-with" to "X-Requested-With",
    )

    /** cURL flags that consume the following token, so their value is never mistaken for the URL. */
    private val VALUE_FLAGS = setOf(
        "-X", "--request", "-d", "--data", "--data-raw", "--data-binary", "--data-urlencode",
        "-A", "--user-agent", "-e", "--referer", "-u", "--user", "-o", "--output",
        "--connect-to", "--resolve", "-H", "--header", "-b", "--cookie",
    )

    fun parse(raw: String): Parsed {
        val input = raw.trim()
        if (input.isEmpty()) return EMPTY
        return if (looksLikeCurl(input)) parseCurl(input) else parseHeaderLines(input)
    }

    private fun looksLikeCurl(input: String): Boolean =
        input.startsWith("curl ", ignoreCase = true) || input.contains(" curl ") || Regex("(^|\\s)curl\\s").containsMatchIn(input)

    // --- cURL ------------------------------------------------------------------------------------

    private fun parseCurl(input: String): Parsed {
        val toks = tokenize(input)
        val headers = LinkedHashMap<String, String>()
        var url: String? = null
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            when {
                t == "-H" || t == "--header" -> { toks.getOrNull(i + 1)?.let { addHeaderLine(headers, it) }; i += 2 }
                t == "-b" || t == "--cookie" -> { toks.getOrNull(i + 1)?.let { putIfAllowed(headers, "cookie", it) }; i += 2 }
                t.startsWith("--header=") -> { addHeaderLine(headers, t.removePrefix("--header=")); i++ }
                t.startsWith("--cookie=") -> { putIfAllowed(headers, "cookie", t.removePrefix("--cookie=")); i++ }
                t == "--url" -> { if (url == null) url = toks.getOrNull(i + 1); i += 2 }
                t.equals("curl", ignoreCase = true) -> i++
                t in VALUE_FLAGS -> i += 2
                t.startsWith("-") -> i++ // unknown flag with no value (e.g. --compressed)
                else -> { if (url == null && t.contains("://")) url = t; i++ }
            }
        }
        return Parsed(headers, originOf(url))
    }

    /**
     * Splits a shell command into tokens, honouring single quotes, double quotes (with `\` escapes),
     * ANSI-C `$'…'` quoting and `\`-newline line continuations — the forms Chromium's "Copy as cURL
     * (bash)" emits.
     */
    private fun tokenize(s: String): List<String> {
        val tokens = ArrayList<String>()
        val sb = StringBuilder()
        var inToken = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && (s[i + 1] == '\n' || s[i + 1] == '\r') -> i += 2 // line continuation
                c == '$' && i + 1 < s.length && s[i + 1] == '\'' -> { inToken = true; i = readAnsiC(s, i + 2, sb) }
                c == '\'' -> { inToken = true; i = readSingle(s, i + 1, sb) }
                c == '"' -> { inToken = true; i = readDouble(s, i + 1, sb) }
                c.isWhitespace() -> { if (inToken) { tokens.add(sb.toString()); sb.setLength(0); inToken = false }; i++ }
                else -> { inToken = true; sb.append(c); i++ }
            }
        }
        if (inToken) tokens.add(sb.toString())
        return tokens
    }

    private fun readSingle(s: String, from: Int, sb: StringBuilder): Int {
        var i = from
        while (i < s.length && s[i] != '\'') { sb.append(s[i]); i++ }
        return if (i < s.length) i + 1 else i
    }

    private fun readDouble(s: String, from: Int, sb: StringBuilder): Int {
        var i = from
        while (i < s.length && s[i] != '"') {
            if (s[i] == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i += 2 } else { sb.append(s[i]); i++ }
        }
        return if (i < s.length) i + 1 else i
    }

    private fun readAnsiC(s: String, from: Int, sb: StringBuilder): Int {
        var i = from
        while (i < s.length && s[i] != '\'') {
            if (s[i] == '\\' && i + 1 < s.length) {
                sb.append(when (s[i + 1]) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '\\' -> '\\'; '\'' -> '\''; else -> s[i + 1] })
                i += 2
            } else { sb.append(s[i]); i++ }
        }
        return if (i < s.length) i + 1 else i
    }

    // --- raw header lines ------------------------------------------------------------------------

    private fun parseHeaderLines(input: String): Parsed {
        val headers = LinkedHashMap<String, String>()
        val lines = input.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        // A single line with no ':' is a bare cookie string pasted directly.
        if (lines.size == 1 && !lines[0].contains(':')) {
            putIfAllowed(headers, "cookie", lines[0])
            return Parsed(headers, null)
        }
        for (line in lines) if (line.contains(':')) addHeaderLine(headers, line)
        return Parsed(headers, null)
    }

    // --- shared --------------------------------------------------------------------------------

    private fun addHeaderLine(headers: LinkedHashMap<String, String>, line: String) {
        val sep = line.indexOf(':')
        if (sep <= 0) return
        putIfAllowed(headers, line.substring(0, sep).trim(), line.substring(sep + 1).trim())
    }

    private fun putIfAllowed(headers: LinkedHashMap<String, String>, name: String, value: String) {
        val canonical = ALLOWLIST[name.trim().lowercase()] ?: return
        if (value.isNotBlank()) headers[canonical] = value // cookie values are kept verbatim (no URL-decode)
    }

    /** Best-effort `scheme://authority` origin of the copied request's URL. */
    private fun originOf(url: String?): String? {
        if (url == null) return null
        val schemeSep = url.indexOf("://")
        if (schemeSep < 0) return null
        val afterScheme = schemeSep + 3
        val slash = url.indexOf('/', afterScheme)
        val origin = if (slash < 0) url else url.substring(0, slash)
        return origin.ifBlank { null }
    }
}
