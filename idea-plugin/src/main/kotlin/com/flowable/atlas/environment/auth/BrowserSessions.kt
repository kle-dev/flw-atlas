package com.flowable.atlas.environment.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * The auth headers captured from the user's own browser session, kept **in memory for this IDE
 * session** and keyed by base URL — for any Flowable server behind an identity provider.
 *
 * This was `InspectSession`, and it served only the Expression Playground, which is why a Design
 * server behind OAuth2 could not be reached at all: the capture existed, the client that needed it did
 * not know about it. Nothing here was ever Work-specific — a cookie for a host is a cookie for a host —
 * so it lives beside the rest of the auth machinery now and both clients read it.
 *
 * The headers come either from the embedded-browser login ([BrowserSignInDialog], a `Cookie`) or from a
 * pasted "Copy as cURL" ([CurlAuthParser], which can also carry `Authorization` and the CSRF token).
 *
 * Deliberately **not** persisted, unlike [AtlasCredentials]: session cookies and tokens are short-lived
 * and sensitive, so they live only as long as the IDE runs — re-capturing repopulates them, and nothing
 * is written to disk or the OS keychain. Purely in-memory, so it is safe to read on the EDT.
 */
object BrowserSessions {

    private val headersByBaseUrl = ConcurrentHashMap<String, Map<String, String>>()

    private fun normalize(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    /** The captured replay headers for [baseUrl], or null if none was captured (or it was cleared). */
    fun get(baseUrl: String): Map<String, String>? =
        if (baseUrl.isBlank()) null else headersByBaseUrl[normalize(baseUrl)]?.takeIf { it.isNotEmpty() }

    /** Store (or, for an empty map, drop) the captured replay headers for [baseUrl]. */
    fun set(baseUrl: String, headers: Map<String, String>) {
        if (baseUrl.isBlank()) return
        val key = normalize(baseUrl)
        if (headers.isEmpty()) headersByBaseUrl.remove(key) else headersByBaseUrl[key] = LinkedHashMap(headers)
    }

    fun clear(baseUrl: String) {
        if (baseUrl.isNotBlank()) headersByBaseUrl.remove(normalize(baseUrl))
    }

    /**
     * The `username to password` pair behind a captured `Authorization: Basic …`, or null when this
     * session holds something else (an SSO cookie, a bearer token) or nothing at all.
     *
     * Only ever decodes a header this plugin wrote itself — the paste dialog encodes what the user
     * typed into its own fields — so that promoting a session target to a real environment can carry
     * those credentials into the PasswordSafe instead of asking for them a second time.
     */
    fun basicAuth(baseUrl: String): Pair<String, String>? {
        val header = get(baseUrl)?.get("Authorization")?.trim() ?: return null
        if (!header.startsWith("Basic ", ignoreCase = true)) return null
        val decoded = runCatching {
            String(java.util.Base64.getDecoder().decode(header.substring("Basic ".length).trim()))
        }.getOrNull() ?: return null
        // A password may contain ':'; a username may not — so the first separator is the only one.
        val separator = decoded.indexOf(':')
        if (separator < 0) return null
        return decoded.substring(0, separator) to decoded.substring(separator + 1)
    }
}
