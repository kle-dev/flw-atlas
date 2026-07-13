package com.flowable.atlas.design

import com.flowable.atlas.model.MiniJson
import com.intellij.openapi.diagnostic.logger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

/**
 * A thin client for the Flowable Design REST API (HTTP Basic auth), used by "Pull from Flowable
 * Design" to discover and download the current app export:
 *
 *  - `GET {baseUrl}/design-api/workspaces` — paginated workspace list
 *  - `GET {baseUrl}/design-api/workspaces/{ws}/apps` — paginated app list (with version/lastUpdated)
 *  - `GET {baseUrl}/design-api/workspaces/{ws}/apps/{app}/export` — the app export ZIP, byte-identical
 *    to the Design UI's manual "Export app"
 *
 * URL building and response parsing are pure and unit-tested; the `list*`/`export*` functions
 * perform network calls and must never run on the EDT.
 */
object DesignClient {

    private val LOG = logger<DesignClient>()

    private const val PAGE_SIZE = 200

    /** Runaway guard for the pagination loop — 50 × [PAGE_SIZE] items is far beyond any real server. */
    private const val MAX_PAGES = 50

    private val CONNECT_TIMEOUT = Duration.ofSeconds(10)
    private val LIST_TIMEOUT = Duration.ofSeconds(30)
    private val EXPORT_TIMEOUT = Duration.ofSeconds(120)

    data class Connection(val baseUrl: String, val username: String, val password: String)

    data class Workspace(val key: String, val name: String)

    data class App(val key: String, val name: String, val version: Int?, val lastUpdated: String?)

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

    /** Downloads the app's current export ZIP (what the Design UI's "Export app" produces). */
    fun exportApp(conn: Connection, workspaceKey: String, appKey: String): Result<ByteArray> {
        if (conn.baseUrl.isBlank()) return Result.Failed("Design base URL is required")
        return try {
            val request = requestBuilder(conn, exportEndpoint(conn.baseUrl, workspaceKey, appKey), EXPORT_TIMEOUT)
                .header("Accept", "application/zip")
                .GET()
                .build()
            val resp = newClient().send(request, HttpResponse.BodyHandlers.ofByteArray())
            val body = resp.body() ?: ByteArray(0)
            when {
                resp.statusCode() !in 200..299 -> failedForStatus(resp.statusCode(), String(body, StandardCharsets.UTF_8))
                !isZip(body) -> Result.Failed(
                    "Server did not return a ZIP (got ${resp.headers().firstValue("Content-Type").orElse("no content type")}) — " +
                        "is the base URL a Flowable Design server, or did a proxy/SSO login page answer instead?",
                )
                else -> Result.Success(body)
            }
        } catch (e: Exception) {
            LOG.warn("Design app export failed", e)
            Result.Failed(failureMessage(conn.baseUrl, e))
        }
    }

    private fun <T> fetchAllPages(
        conn: Connection,
        endpoint: (start: Int, size: Int) -> String,
        parse: (String) -> Page<T>,
    ): Result<List<T>> {
        if (conn.baseUrl.isBlank()) return Result.Failed("Design base URL is required")
        return try {
            val all = mutableListOf<T>()
            repeat(MAX_PAGES) {
                val request = requestBuilder(conn, endpoint(all.size, PAGE_SIZE), LIST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build()
                val resp = newClient().send(request, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() !in 200..299) return failedForStatus(resp.statusCode(), resp.body())
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

    private fun failedForStatus(code: Int, body: String): Result.Failed = when (code) {
        401 -> Result.Failed("Authentication failed — check username/password (HTTP 401)")
        403 -> Result.Failed("No read access to this workspace/app (HTTP 403)")
        404 -> Result.Failed("Not found — is the base URL a Flowable Design server, and do workspace/app still exist? (HTTP 404)")
        else -> Result.Failed("HTTP $code: ${body.take(200)}")
    }

    private fun failureMessage(baseUrl: String, e: Exception): String = when (e) {
        is IllegalArgumentException -> e.message ?: "Invalid request"
        else -> "Cannot reach ${normalizeBaseUrl(baseUrl)}: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun requestBuilder(conn: Connection, url: String, timeout: Duration): HttpRequest.Builder {
        val auth = "Basic " + Base64.getEncoder().encodeToString("${conn.username}:${conn.password}".toByteArray())
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(timeout)
            .header("Authorization", auth)
    }

    private fun newClient(): HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()

    private fun isZip(body: ByteArray): Boolean =
        body.size >= 2 && body[0] == 'P'.code.toByte() && body[1] == 'K'.code.toByte()
}
