package com.flowable.atlas.environment.auth

import java.net.http.HttpRequest
import java.util.Base64

/**
 * What Atlas puts on a request to prove who it is — one answer for every Flowable server it talks to.
 *
 * There were two, and they disagreed about what was possible. `DesignClient.Auth` was a sealed
 * Basic-or-Token and set exactly one `Authorization` header, so a Design server behind an identity
 * provider could not be reached at all. `InspectClient` took a username, a password and a loose map of
 * captured headers, and worked out at each call site whether the capture had already brought an
 * `Authorization`. Same problem, two shapes, one of them missing a case.
 *
 * ### The model
 *
 * A [credential] — basic or token, or none — plus the [sessionHeaders] a browser session contributed.
 * Both may be present: an SSO-fronted Flowable commonly wants the session cookie *and* basic auth
 * behind it, and its security chain takes whichever it honours. A captured `Authorization` wins over
 * the credential, because `HttpRequest.Builder.header` appends and two of them is a 400 from the
 * server rather than a fallback.
 *
 * Pure — no IDE, no keychain, no network — so [apply] is unit-tested rather than eyeballed against a
 * live server.
 */
data class AuthContext(
    val credential: Credential? = null,
    val sessionHeaders: Map<String, String> = emptyMap(),
) {

    /** The credential itself. Sealed, so "password *and* token" stays unrepresentable. */
    sealed interface Credential {
        data class Basic(val username: String, val password: String) : Credential
        data class Token(val token: String) : Credential
    }

    /**
     * True when nothing here would authenticate anything — the caller's cue to ask for credentials.
     *
     * Asked of what would actually be *sent*, not of whether a field is set: a `Token("")` and a
     * `Basic("", "")` are as good as absent, and treating them as configured is how a pull ends in a
     * 401 the user cannot explain instead of in the settings page.
     */
    val isEmpty: Boolean
        get() = authorizationHeader() == null && !hasSession

    /** True when a browser session was captured for this server. */
    val hasSession: Boolean get() = sessionHeaders.any { it.value.isNotBlank() }

    /**
     * Writes the headers onto [builder]. The one place that decides the precedence, so no client has to
     * remember it: captured headers first, then the credential — but only if the capture did not
     * already carry an `Authorization`.
     */
    fun apply(builder: HttpRequest.Builder): HttpRequest.Builder {
        var carriesAuthorization = false
        sessionHeaders.forEach { (name, value) ->
            if (value.isBlank()) return@forEach
            if (name.equals(AUTHORIZATION, ignoreCase = true)) carriesAuthorization = true
            builder.header(name, value)
        }
        if (!carriesAuthorization) {
            authorizationHeader()?.let { builder.header(AUTHORIZATION, it) }
        }
        return builder
    }

    /** The `Authorization` value the credential produces, or null when there is none to send. */
    fun authorizationHeader(): String? = when (val credential = credential) {
        null -> null
        is Credential.Basic ->
            if (credential.username.isBlank() && credential.password.isBlank()) null
            else "Basic " + Base64.getEncoder()
                .encodeToString("${credential.username}:${credential.password}".toByteArray())
        is Credential.Token ->
            normalizeToken(credential.token).takeIf { it.isNotBlank() }?.let { "Bearer $it" }
    }

    companion object {

        private const val AUTHORIZATION = "Authorization"

        fun basic(username: String, password: String, sessionHeaders: Map<String, String> = emptyMap()) =
            AuthContext(Credential.Basic(username, password), sessionHeaders)

        fun token(token: String, sessionHeaders: Map<String, String> = emptyMap()) =
            AuthContext(Credential.Token(token), sessionHeaders)

        /** Just the browser session — for a server that authenticates by cookie and nothing else. */
        fun session(sessionHeaders: Map<String, String>) = AuthContext(null, sessionHeaders)

        /**
         * A token as the user pasted it, minus what they pasted along with it: surrounding whitespace,
         * quotes, and a copied-along `Bearer ` prefix — so the header never becomes `Bearer Bearer …`.
         */
        fun normalizeToken(raw: String): String =
            raw.trim().trim('"', '\'').replace(BEARER_PREFIX, "").trim()

        private val BEARER_PREFIX = Regex("^bearer\\s+", RegexOption.IGNORE_CASE)
    }
}
