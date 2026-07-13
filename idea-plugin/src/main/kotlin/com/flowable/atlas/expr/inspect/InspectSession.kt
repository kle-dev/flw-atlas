package com.flowable.atlas.expr.inspect

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory (per-IDE-session) store of the auth headers captured from the user's browser session,
 * keyed by the normalized app base URL. This is the SSO/OAuth2 counterpart of the basic-auth
 * [InspectCredentials]: for an app fronted by an IdP, basic auth can't pass the login redirect, so the
 * playground replays the headers the browser already authenticates with. Those headers come either from
 * the embedded-browser login ([InspectSignInDialog], a `Cookie`) or from a pasted "Copy as cURL"
 * ([CurlAuthParser], which can also carry `Authorization` and the CSRF token).
 *
 * Deliberately **not** persisted (unlike [InspectCredentials]): session cookies/tokens are short-lived
 * and sensitive, so they live only as long as the IDE runs — re-capturing re-populates them, and nothing
 * is written to disk or the OS keychain. Purely in-memory, so it is safe to read on the EDT.
 */
object InspectSession {

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
}
