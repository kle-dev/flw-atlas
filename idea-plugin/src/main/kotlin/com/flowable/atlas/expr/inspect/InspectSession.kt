package com.flowable.atlas.expr.inspect

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory (per-IDE-session) store of the browser session cookie harvested by the "Sign in to app"
 * login, keyed by the normalized app base URL. This is the SSO/OAuth2 counterpart of the basic-auth
 * [InspectCredentials]: for an app fronted by an IdP, basic auth can't pass the login redirect, so the
 * playground replays the `Cookie` header the embedded-browser login produced instead.
 *
 * Deliberately **not** persisted (unlike [InspectCredentials]): session cookies are short-lived and
 * sensitive, so they live only as long as the IDE runs — a re-login re-populates them, and nothing is
 * written to disk or the OS keychain. Purely in-memory, so it is safe to read on the EDT.
 */
object InspectSession {

    private val cookiesByBaseUrl = ConcurrentHashMap<String, String>()

    private fun normalize(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    /** The captured `Cookie` header for [baseUrl], or null if none was captured (or it was cleared). */
    fun get(baseUrl: String): String? =
        if (baseUrl.isBlank()) null else cookiesByBaseUrl[normalize(baseUrl)]?.takeIf { it.isNotBlank() }

    /** Store (or, for a blank header, drop) the captured `Cookie` header for [baseUrl]. */
    fun set(baseUrl: String, cookieHeader: String) {
        if (baseUrl.isBlank()) return
        val key = normalize(baseUrl)
        if (cookieHeader.isBlank()) cookiesByBaseUrl.remove(key) else cookiesByBaseUrl[key] = cookieHeader
    }

    fun clear(baseUrl: String) {
        if (baseUrl.isNotBlank()) cookiesByBaseUrl.remove(normalize(baseUrl))
    }
}
