package com.flowable.atlas.design

import com.flowable.atlas.model.MiniJson
import com.intellij.openapi.diagnostic.logger
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

/**
 * A thin client for the Flowable Design REST API, used by "Pull from Flowable Design" to discover and
 * download the current app export:
 *
 *  - `GET {baseUrl}/design-api/workspaces` — paginated workspace list
 *  - `GET {baseUrl}/design-api/workspaces/{ws}/apps` — paginated app list (with version/lastUpdated)
 *  - `GET {baseUrl}/design-api/workspaces/{ws}/apps/{app}/export` — the app export ZIP, byte-identical
 *    to the Design UI's manual "Export app"
 *  - `POST {baseUrl}/design-api/current-user/access-tokens` — mints a personal access token
 *
 * Requests authenticate with either HTTP Basic or a Design **personal access token** sent as
 * `Authorization: Bearer …` (see [Auth]) — the token is the scheme the official Flowable CLI uses, and
 * the only one that works when Design sits behind an IdP (`application.design.security.type=oauth2`,
 * where Basic is switched off). Design validates it as a JWT it issued itself, so the server needs
 * `flowable.design.security.access-token.signing-secret` configured.
 *
 * URL building and response parsing are pure and unit-tested; the `list*`/`export*`/[createAccessToken]
 * functions perform network calls and must never run on the EDT.
 */
object DesignClient {

    private val LOG = logger<DesignClient>()

    private const val PAGE_SIZE = 200

    /** Runaway guard for the pagination loop — 50 × [PAGE_SIZE] items is far beyond any real server. */
    private const val MAX_PAGES = 50

    private val CONNECT_TIMEOUT = Duration.ofSeconds(10)
    private val LIST_TIMEOUT = Duration.ofSeconds(30)
    private val EXPORT_TIMEOUT = Duration.ofSeconds(120)

    /**
     * How a request authenticates. A sealed type on purpose: the two schemes are mutually exclusive, so
     * "password *and* token" is unrepresentable and [authorizationHeader] can only ever produce one
     * header value.
     */
    sealed interface Auth {
        data class Basic(val username: String, val password: String) : Auth
        data class Token(val token: String) : Auth
    }

    data class Connection(val baseUrl: String, val auth: Auth) {
        /** Basic-auth shorthand. */
        constructor(baseUrl: String, username: String, password: String) : this(baseUrl, Auth.Basic(username, password))
    }

    data class Workspace(val key: String, val name: String)

    data class App(val key: String, val name: String, val version: Int?, val lastUpdated: String?)

    /**
     * An app export: the ZIP [bytes] plus the server-suggested download [fileName] (the
     * `Content-Disposition` filename, or null when the server sent none). A plain class on purpose —
     * a `data class` holding a [ByteArray] would get reference-based equals/hashCode.
     */
    class Export(val bytes: ByteArray, val fileName: String?)

    /**
     * A freshly minted personal access token. [value] is the only time Design hands the token out — it
     * stores just a hash — so the caller must keep it right away.
     */
    data class NewToken(val value: String, val name: String?, val expirationTime: String?)

    /** One page of a Design `DataResponse` (`data` plus the server's `total` count). */
    data class Page<T>(val data: List<T>, val total: Int)

    sealed interface Result<out T> {
        data class Success<T>(val value: T) : Result<T>
        data class Failed(val message: String) : Result<Nothing>
    }

    /**
     * The server base incl. context path (e.g. `http://localhost:8888/flowable-design`), tolerating
     * a trailing slash and an accidentally pasted `/design-api` suffix.
     */
    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (url.endsWith("/design-api", ignoreCase = true)) url = url.dropLast("/design-api".length)
        return url.trimEnd('/')
    }

    fun workspacesEndpoint(baseUrl: String, start: Int = 0, size: Int = PAGE_SIZE): String =
        "${normalizeBaseUrl(baseUrl)}/design-api/workspaces?start=$start&size=$size"

    fun appsEndpoint(baseUrl: String, workspaceKey: String, start: Int = 0, size: Int = PAGE_SIZE): String =
        "${normalizeBaseUrl(baseUrl)}/design-api/workspaces/${encode(workspaceKey)}/apps?start=$start&size=$size"

    fun exportEndpoint(baseUrl: String, workspaceKey: String, appKey: String): String =
        "${normalizeBaseUrl(baseUrl)}/design-api/workspaces/${encode(workspaceKey)}/apps/${encode(appKey)}/export"

    fun accessTokensEndpoint(baseUrl: String): String =
        "${normalizeBaseUrl(baseUrl)}/design-api/current-user/access-tokens"

    /** The Design UI page where a user creates and revokes personal access tokens. */
    fun tokenManagementUrl(baseUrl: String): String = "${normalizeBaseUrl(baseUrl)}/#/token-mgmt"

    /**
     * An access token the way a human pastes it: surrounding whitespace and quotes are dropped, and a
     * copied-along `Bearer ` prefix is removed so the header never becomes `Bearer Bearer …`.
     */
    fun normalizeAccessToken(raw: String): String =
        raw.trim().trim('"', '\'').replace(Regex("^bearer\\s+", RegexOption.IGNORE_CASE), "").trim()

    private fun encode(pathSegment: String): String =
        URLEncoder.encode(pathSegment, StandardCharsets.UTF_8).replace("+", "%20")

    fun parseWorkspacePage(json: String): Page<Workspace> = parsePage(json) { m ->
        (m["key"] as? String)?.let { key ->
            Workspace(key, (m["name"] as? String).takeUnless { it.isNullOrBlank() } ?: key)
        }
    }

    fun parseAppPage(json: String): Page<App> = parsePage(json) { m ->
        (m["key"] as? String)?.let { key ->
            App(
                key = key,
                name = (m["name"] as? String).takeUnless { it.isNullOrBlank() } ?: key,
                version = (m["version"] as? Number)?.toInt(),
                lastUpdated = m["lastUpdated"] as? String,
            )
        }
    }

    private fun <T> parsePage(json: String, item: (Map<*, *>) -> T?): Page<T> {
        // A non-JSON body (e.g. an HTML login page from a proxy/SSO redirect) must surface as the
        // same "unexpected response" error as a JSON body of the wrong shape.
        val map = runCatching { MiniJson.parse(json) }.getOrNull() as? Map<*, *>
            ?: throw IllegalArgumentException("Unexpected response: ${json.take(200)}")
        val data = (map["data"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let(item) }
        val total = (map["total"] as? Number)?.toInt() ?: data.size
        return Page(data, total)
    }

    fun listWorkspaces(conn: Connection): Result<List<Workspace>> =
        fetchAllPages(conn, { start, size -> workspacesEndpoint(conn.baseUrl, start, size) }, ::parseWorkspacePage)

    fun listApps(conn: Connection, workspaceKey: String): Result<List<App>> =
        fetchAllPages(conn, { start, size -> appsEndpoint(conn.baseUrl, workspaceKey, start, size) }, ::parseAppPage)

    /**
     * Downloads the app's current export ZIP (what the Design UI's "Export app" produces), together
     * with the server-suggested filename from `Content-Disposition` so the pull can name the file the
     * same way Design does.
     */
    fun exportApp(conn: Connection, workspaceKey: String, appKey: String): Result<Export> {
        missingConfig(conn)?.let { return it }
        return try {
            val request = requestBuilder(conn, exportEndpoint(conn.baseUrl, workspaceKey, appKey), EXPORT_TIMEOUT)
                .header("Accept", "application/zip")
                .GET()
                .build()
            val resp = newClient().send(request, HttpResponse.BodyHandlers.ofByteArray())
            val body = resp.body() ?: ByteArray(0)
            when {
                resp.statusCode() !in 200..299 -> failedForStatus(conn.auth, resp.statusCode(), String(body, StandardCharsets.UTF_8))
                !isZip(body) -> Result.Failed(
                    "Server did not return a ZIP (got ${resp.headers().firstValue("Content-Type").orElse("no content type")}) — " +
                        "is the base URL a Flowable Design server, or did a proxy/SSO login page answer instead?",
                )
                else -> {
                    val suggested = parseContentDispositionFilename(resp.headers().firstValue("Content-Disposition").orElse(null))
                    Result.Success(Export(body, suggested))
                }
            }
        } catch (e: Exception) {
            LOG.warn("Design app export failed", e)
            Result.Failed(failureMessage(conn.baseUrl, e))
        }
    }

    /**
     * Mints a personal access token for [username], so the pull can authenticate with a token from then
     * on and no password has to stay in the keychain. [validFor] is an ISO-8601 duration (e.g. `P365D`)
     * or null for the server default.
     *
     * Deliberately Basic-only: Design strips the `accessTokens` capability from requests that are
     * themselves authenticated with a Design token, so a token cannot mint its own successor. On an
     * IdP-fronted Design (where Basic is off) this fails with 401 and the caller is pointed at the Design
     * UI instead.
     */
    fun createAccessToken(
        baseUrl: String,
        username: String,
        password: String,
        name: String,
        validFor: String?,
    ): Result<NewToken> {
        if (baseUrl.isBlank()) return Result.Failed("Design base URL is required")
        if (name.isBlank()) return Result.Failed("A token name is required")
        val auth = Auth.Basic(username, password)
        val fields = LinkedHashMap<String, Any?>()
        fields["name"] = name
        if (!validFor.isNullOrBlank()) fields["validFor"] = validFor
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(accessTokensEndpoint(baseUrl)))
                .timeout(LIST_TIMEOUT)
                .header("Authorization", authorizationHeader(auth))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MiniJson.stringify(fields)))
                .build()
            val resp = newClient().send(request, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                return if (resp.statusCode() == 401) {
                    Result.Failed(
                        "Could not sign in to create a token — check username/password. If this Design is behind " +
                            "SSO, username/password is disabled there: create the token in Design instead (HTTP 401)",
                    )
                } else {
                    failedForStatus(auth, resp.statusCode(), resp.body())
                }
            }
            parseNewToken(resp.body())?.let { Result.Success(it) }
                ?: Result.Failed("The server did not return a token value — is the base URL a Flowable Design server?")
        } catch (e: Exception) {
            LOG.warn("Design access-token creation failed", e)   // never logs the token: only 2xx bodies carry one
            Result.Failed(failureMessage(baseUrl, e))
        }
    }

    /** The `CreateAccessTokenResponse` body, or null when it carries no token value. */
    fun parseNewToken(json: String): NewToken? {
        val map = runCatching { MiniJson.parse(json) }.getOrNull() as? Map<*, *> ?: return null
        val value = (map["value"] as? String)?.takeUnless { it.isBlank() } ?: return null
        return NewToken(value, map["name"] as? String, map["expirationTime"] as? String)
    }

    /**
     * The download filename the server suggests via `Content-Disposition`, or null when the header is
     * absent/blank or carries no filename. Honors RFC 6266: the extended `filename*=charset''pct-encoded`
     * form wins over a plain `filename=` (quoted or bare). The extended value is percent-decoded
     * manually — never via `URLDecoder`, which would turn a literal `+` into a space.
     */
    fun parseContentDispositionFilename(header: String?): String? {
        if (header.isNullOrBlank()) return null
        Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            decodeExtendedValue(m.groupValues[1].trim())?.let { return it }
        }
        Regex("""filename\s*=\s*("([^"]*)"|[^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            val raw = if (m.groupValues[1].startsWith("\"")) m.groupValues[2] else m.groupValues[1].trim()
            return raw.takeUnless { it.isBlank() }
        }
        return null
    }

    /** Decodes an RFC 5987 `charset'lang'pct-encoded` value, e.g. `UTF-8''r%C3%A9sum%C3%A9.zip`. */
    private fun decodeExtendedValue(value: String): String? {
        val firstQuote = value.indexOf('\'')
        val secondQuote = if (firstQuote >= 0) value.indexOf('\'', firstQuote + 1) else -1
        val charset = if (secondQuote >= 0) {
            runCatching { Charset.forName(value.substring(0, firstQuote).ifBlank { "UTF-8" }) }.getOrDefault(StandardCharsets.UTF_8)
        } else {
            StandardCharsets.UTF_8
        }
        val encoded = if (secondQuote >= 0) value.substring(secondQuote + 1) else value
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            if (c == '%' && i + 2 < encoded.length) {
                val b = encoded.substring(i + 1, i + 3).toIntOrNull(16)
                if (b != null) {
                    out.write(b)
                    i += 3
                    continue
                }
            }
            out.write(c.code)   // token chars in an extended value are ASCII by spec
            i++
        }
        return out.toByteArray().toString(charset).takeUnless { it.isBlank() }
    }

    private fun <T> fetchAllPages(
        conn: Connection,
        endpoint: (start: Int, size: Int) -> String,
        parse: (String) -> Page<T>,
    ): Result<List<T>> {
        missingConfig(conn)?.let { return it }
        return try {
            val all = mutableListOf<T>()
            repeat(MAX_PAGES) {
                val request = requestBuilder(conn, endpoint(all.size, PAGE_SIZE), LIST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build()
                val resp = newClient().send(request, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() !in 200..299) return failedForStatus(conn.auth, resp.statusCode(), resp.body())
                val page = parse(resp.body())
                all += page.data
                if (all.size >= page.total || page.data.isEmpty()) return Result.Success(all)
            }
            Result.Success(all)   // page cap hit — return what we have
        } catch (e: Exception) {
            LOG.warn("Design request failed", e)
            Result.Failed(failureMessage(conn.baseUrl, e))
        }
    }

    private fun failedForStatus(auth: Auth, code: Int, body: String): Result.Failed = when (code) {
        401 -> Result.Failed(unauthorizedMessage(auth))
        403 -> Result.Failed("No read access to this workspace/app (HTTP 403)")
        404 -> Result.Failed("Not found — is the base URL a Flowable Design server, and do workspace/app still exist? (HTTP 404)")
        else -> Result.Failed("HTTP $code: ${body.take(200)}")
    }

    /** The 401 hint for the scheme actually used — a wrong password and a stale token need different fixes. */
    internal fun unauthorizedMessage(auth: Auth): String = when (auth) {
        is Auth.Basic -> "Authentication failed — check username/password (HTTP 401)"
        is Auth.Token -> "Authentication failed — the access token is invalid or expired; " +
            "create a new one in Design under \"Access tokens\" (HTTP 401)"
    }

    private fun failureMessage(baseUrl: String, e: Exception): String = when (e) {
        is IllegalArgumentException -> e.message ?: "Invalid request"
        else -> "Cannot reach ${normalizeBaseUrl(baseUrl)}: ${e.message ?: e.javaClass.simpleName}"
    }

    /** Configuration pre-flight shared by the list and export paths — never touches the network. */
    private fun missingConfig(conn: Connection): Result.Failed? {
        if (conn.baseUrl.isBlank()) return Result.Failed("Design base URL is required")
        val auth = conn.auth
        if (auth is Auth.Token && normalizeAccessToken(auth.token).isBlank()) {
            return Result.Failed("A Flowable Design access token is required")
        }
        return null   // a blank Basic username stays a server-side 401, exactly as before
    }

    private fun requestBuilder(conn: Connection, url: String, timeout: Duration): HttpRequest.Builder =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(timeout)
            // `header()` appends rather than replaces, so this single call — together with the sealed
            // Auth type — is what guarantees exactly one Authorization header.
            .header("Authorization", authorizationHeader(conn.auth))

    internal fun authorizationHeader(auth: Auth): String = when (auth) {
        is Auth.Basic -> "Basic " + Base64.getEncoder().encodeToString("${auth.username}:${auth.password}".toByteArray())
        is Auth.Token -> "Bearer " + normalizeAccessToken(auth.token)
    }

    private fun newClient(): HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()

    private fun isZip(body: ByteArray): Boolean =
        body.size >= 2 && body[0] == 'P'.code.toByte() && body[1] == 'K'.code.toByte()
}
