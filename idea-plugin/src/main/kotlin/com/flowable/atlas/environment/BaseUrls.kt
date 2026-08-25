package com.flowable.atlas.environment

import com.flowable.atlas.design.DesignClient
import java.net.URI

/**
 * The one place that decides how a Flowable base URL is written down and how two of them are
 * compared.
 *
 * This used to be two private helpers — [DesignClient.normalizeBaseUrl] and `InspectCredentials`'
 * own `normalize` — which was harmless while each only had to key one keychain entry. It is not
 * harmless any more: the same answer now decides whether two catalog entries are duplicates
 * ([AtlasEnvironments]), whether a pasted Work URL is recognised as a known environment, and which
 * PasswordSafe record a credential lands in. Three callers disagreeing about a trailing slash would
 * be three different bugs, so there is one function.
 *
 * Pure — no IDE, no network. [DesignClient.normalizeBaseUrl] stays the public spelling for the
 * Design side and simply delegates here in spirit: this object calls it, rather than reimplementing
 * the `/design-api` rule in a second place.
 */
object BaseUrls {

    /** Hosts that mean "this machine" — [EnvironmentNames] suggests calling them *Local*. */
    private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0")

    /**
     * The stored spelling of [raw] for [kind]: trimmed, no trailing slash, and for
     * [ConnectionKind.DESIGN] without an accidentally pasted `/design-api` suffix. Case is preserved —
     * this is what the user sees in the field, so it is not the place to lowercase a path.
     */
    fun normalize(kind: ConnectionKind, raw: String): String = when (kind) {
        ConnectionKind.DESIGN -> DesignClient.normalizeBaseUrl(raw)
        ConnectionKind.WORK -> raw.trim().trimEnd('/')
    }

    /**
     * The key two URLs are compared by: [normalize], a lowercased scheme and host, and the default
     * port dropped. Scheme and host are case-insensitive per RFC 3986 while a context path is not, so
     * `HTTP://Host/x` and `http://host/x` are one server while `…/App` and `…/app` are deliberately
     * still two. `http://h:80/x` is the same server as `http://h/x`; `https` never equals `http`.
     */
    fun comparisonKey(kind: ConnectionKind, raw: String): String {
        val normalized = normalize(kind, raw)
        val separator = normalized.indexOf("://")
        if (separator < 0) return normalized.lowercase()   // not a URL at all: compare as typed
        val authorityEnd = normalized.indexOf('/', separator + 3).takeIf { it >= 0 } ?: normalized.length
        val scheme = normalized.substring(0, separator).lowercase()
        val authority = normalized.substring(separator + 3, authorityEnd).lowercase()
        val defaultPort = if (scheme == "https") ":443" else ":80"
        val withoutDefaultPort = authority.removeSuffix(defaultPort)
        return "$scheme://$withoutDefaultPort" + normalized.substring(authorityEnd)
    }

    /** True when [a] and [b] address the same server for [kind]. */
    fun sameUrl(kind: ConnectionKind, a: String, b: String): Boolean =
        a.isNotBlank() && comparisonKey(kind, a) == comparisonKey(kind, b)

    /**
     * The host of [raw], lowercased and without the port, or `""` when it cannot be parsed. What the
     * narrow status lines show instead of a full URL, and what [EnvironmentNames] suggests a name from.
     */
    fun host(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        // URI parsing fails on plenty of things a human pastes (spaces, an underscore in a host), so
        // the manual split is the fallback, not the other way round.
        runCatching { URI(trimmed).host }.getOrNull()?.takeUnless { it.isBlank() }?.let { return it.lowercase() }
        val separator = trimmed.indexOf("://")
        if (separator < 0) return ""
        val authority = trimmed.substring(separator + 3).substringBefore('/').substringBefore('?')
        val hostOnly = if (authority.startsWith("[")) authority.substringBefore(']') + "]"
        else authority.substringBefore(':')
        return hostOnly.substringAfter('@').lowercase()   // drop any user-info prefix
    }

    /** True when [host] names the developer's own machine. */
    fun isLoopback(host: String): Boolean = host.lowercase() in LOOPBACK
}
