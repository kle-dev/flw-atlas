package com.flowable.atlas.expr.inspect

import com.intellij.openapi.diagnostic.logger
import com.flowable.atlas.model.MiniJson
import com.intellij.openapi.progress.ProcessCanceledException
import java.net.ConnectException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * A thin client for Flowable Inspect's "evaluate a backend expression against a running instance"
 * endpoint: `POST {baseUrl}/inspect-api/evaluate-expression`.
 *
 * Auth is layered to match how apps are actually deployed: HTTP Basic (`username`/`password`) for a
 * local dev instance, and/or captured browser [session headers][Request.sessionHeaders] (a `Cookie`,
 * plus optionally `Authorization`/CSRF from a pasted cURL) for an app fronted by SSO/OAuth2. Both can
 * be sent at once — e.g. an OAuth2 gateway that grants
 * general access (satisfied by the session cookie) in front of a Flowable that still wants basic auth
 * (satisfied by the `Authorization` header) — the server's security chain uses whichever it needs.
 * A login redirect (a 3xx, or an HTML body) surfaces as [SSO_REDIRECT_HINT], distinct from a
 * genuine 401/403 from the basic-auth layer.
 *
 * The engine evaluates against a **live** process/case/task instance — there is no transient
 * variable-map mode — so [scopeId] is required (a processInstanceId / caseInstanceId / taskId) and
 * only backend `${…}` expressions are supported. On a parse/eval error the server returns HTTP 400
 * with `valid:false` and an `exception` message (see [ExpressionValueDTO] in flowable-platform).
 *
 * Request/response (de)serialisation is pure and unit-tested; [evaluate] performs the network call.
 */
object InspectClient {

    private val LOG = logger<InspectClient>()

    enum class ScopeType(val wire: String) { BPMN("bpmn"), CMMN("cmmn"), TASK("task") }

    data class Request(
        val baseUrl: String,
        val expression: String,
        val scopeType: ScopeType,
        val scopeId: String,
        val subScopeId: String? = null,
        val username: String,
        val password: String,
        /**
         * Auth headers captured from the user's browser session (`Cookie`, and — from a pasted
         * "Copy as cURL" — optionally `Authorization` and the CSRF token), replayed for apps behind
         * SSO/OAuth2 where basic auth can't pass the login redirect. Null/empty when the app uses plain
         * basic auth (a local dev instance). See [InspectSession] and [CurlAuthParser].
         */
        val sessionHeaders: Map<String, String>? = null,
    )

    /** Mirrors the server `ExpressionValueDTO`. */
    data class Response(val valid: Boolean, val value: Any?, val valueType: String?, val exception: String?)

    sealed interface Outcome {
        data class Evaluated(val response: Response) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** The JSON request body (`EvaluateExpressionDTO`). */
    fun buildBody(expression: String, scopeType: ScopeType, scopeId: String, subScopeId: String?): String {
        val fields = LinkedHashMap<String, Any?>()
        fields["expression"] = expression
        fields["scopeId"] = scopeId
        if (!subScopeId.isNullOrBlank()) fields["subScopeId"] = subScopeId
        fields["scopeType"] = scopeType.wire
        return MiniJson.stringify(fields)
    }

    /** Parse the `ExpressionValueDTO` response body. */
    fun parseResponse(json: String): Response {
        val map = MiniJson.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("Unexpected response: $json")
        return Response(
            valid = map["valid"] as? Boolean ?: false,
            value = map["value"],
            valueType = map["valueType"] as? String,
            exception = map["exception"] as? String,
        )
    }

    fun endpoint(baseUrl: String): String = baseUrl.trimEnd('/') + "/inspect-api/evaluate-expression"

    /**
     * Shown when the request is bounced to a login page instead of being answered — the app sits
     * behind SSO/OAuth2, which HTTP basic auth cannot satisfy. Covers both "never signed in" and an
     * expired session cookie.
     */
    const val SSO_REDIRECT_HINT: String =
        "The app redirected the request to a login page instead of answering — it's behind SSO/OAuth2, " +
            "which basic auth can't pass. Use \"Sign in to app\" to log in via a browser; if you already " +
            "did, the session may have expired — sign in again."

    /** A response body that is an HTML page (typical SSO login bounce) rather than the JSON DTO. */
    private fun looksLikeLoginPage(body: String): Boolean {
        val head = body.trimStart().take(200).lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }

    /**
     * Answers "can I reach this app, and would it let me in?" without evaluating anything.
     *
     * A `GET` on the **evaluate endpoint**, which only accepts `POST`. Whatever it answers — `405`, or
     * the `500` Flowable actually returns for a bodyless GET — proves the three things worth knowing:
     * the URL resolves, Inspect is enabled, and the security chain let the request through. A `401`
     * means it did not.
     *
     * Probing the app's *base* URL instead was tried and was worse in a way that mattered: a Flowable
     * app answers its root with the SPA's HTML, and an HTML body is how this client recognises a login
     * bounce — so a perfectly ordinary basic-auth app was reported as being behind SSO, which is a
     * confusing thing to tell someone whose credentials work fine. On an API path the heuristic means
     * what it says. (Also rejected: POSTing a throwaway expression, which needs a live instance id
     * nobody has while setting up a connection.)
     *
     * Runs on the caller's thread; call it off the EDT.
     */

    /**
     * The URI to try, followed by the same URI against each address the host resolves to.
     *
     * The JDK's `HttpClient` connects to the **first** address a name resolves to and does not fall
     * back to the others. On macOS `localhost` resolves to `127.0.0.1` and then `::1`, so an app bound
     * to IPv6 only — which is what a Vite/node dev server does by default — is unreachable from Java
     * while `curl` reaches it happily, because curl does fall back. That produced the most misleading
     * error this client can give: *nothing is listening on that host and port*, about an app the user
     * had open in a browser.
     *
     * Only names are expanded; an address literal is already unambiguous, and a single-address name has
     * nothing to fall back to.
     */
    internal fun addressCandidates(uri: URI): List<URI> {
        val host = uri.host ?: return listOf(uri)
        if (host.startsWith("[") || host.firstOrNull()?.isDigit() == true) return listOf(uri)
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        if (addresses.size <= 1) return listOf(uri)
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return listOf(uri) + addresses.map { address ->
            val literal = address.hostAddress.substringBefore('%')
            val bracketed = if (address is Inet6Address) "[$literal]" else literal
            URI.create("${uri.scheme}://$bracketed$port${uri.rawPath}$query")
        }
    }

    /**
     * Sends [build]'s request, retrying the host's other addresses when the connection itself fails.
     * Only a connect failure is retried — an HTTP answer of any status is the server's answer.
     */
    private fun sendTryingEveryAddress(client: HttpClient, uri: URI, build: (URI) -> HttpRequest): HttpResponse<String> {
        val candidates = addressCandidates(uri)
        candidates.forEachIndexed { index, candidate ->
            try {
                return client.send(build(candidate), HttpResponse.BodyHandlers.ofString())
            } catch (e: ConnectException) {
                if (index == candidates.lastIndex) throw e
            }
        }
        throw ConnectException("No address for ${uri.host} accepted a connection")
    }

    fun probe(baseUrl: String, username: String, password: String, sessionHeaders: Map<String, String>? = null): Outcome {
        if (baseUrl.isBlank()) return Outcome.Failed("Base URL is required")
        return try {
            val client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build()
            val captured = sessionHeaders ?: emptyMap()
            val hasCapturedAuth = captured.keys.any { it.equals("Authorization", ignoreCase = true) }
            val resp = sendTryingEveryAddress(client, URI.create(endpoint(baseUrl))) { uri ->
                val builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                if (username.isNotBlank() && !hasCapturedAuth) {
                    val auth = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())
                    builder.header("Authorization", auth)
                }
                captured.forEach { (name, value) -> if (value.isNotBlank()) builder.header(name, value) }
                builder.build()
            }
            probeOutcome(resp.statusCode(), resp.body())
        } catch (pce: ProcessCanceledException) {
            throw pce                      // a cancelled action is not a failure
        } catch (e: Exception) {
            LOG.warn("Inspect probe failed for $baseUrl", e)
            Outcome.Failed("Cannot reach the app — ${shortReason(e)}")
        }
    }

    /**
     * The probe's verdict, split out so the taxonomy is unit-tested without a socket.
     *
     * Every message here is written for the person configuring a connection, not for whoever reads the
     * server log: it says what happened and what to look at. A raw response body is never shown — a
     * truncated slab of JSON tells a reader nothing they can act on, and it is the app's own log that
     * holds the rest of it anyway.
     */
    internal fun probeOutcome(status: Int, body: String): Outcome = when {
        // Checked before the status: an IdP answers its login page with a plain 200. On an API path an
        // HTML body can only mean a bounce, which is what makes this heuristic honest here.
        status in 300..399 || looksLikeLoginPage(body) -> Outcome.Failed(SSO_REDIRECT_HINT)
        status == 401 || status == 403 ->
            Outcome.Failed("The app answered, but rejected these credentials (HTTP $status).")
        status == 404 ->
            Outcome.Failed(
                "Nothing is served at this URL — check the app's context path, and that Inspect is " +
                    "enabled (HTTP 404).",
            )
        // 405 is the textbook answer to a GET on a POST-only endpoint; Flowable happens to answer 500.
        // Either way the endpoint exists and the request got past security, which is the whole question.
        status in 200..299 || status == 405 || status in 500..599 ->
            Outcome.Evaluated(Response(true, null, null, null))
        else -> Outcome.Failed("The app answered HTTP $status" + serverMessage(body)?.let { ": $it" }.orEmpty())
    }

    /**
     * The `message` a Flowable error body carries, short enough for one status line — or null when the
     * body is not one Atlas can read. Never the raw body: half a JSON document in a red label is noise.
     */
    internal fun serverMessage(body: String): String? {
        val map = runCatching { MiniJson.parse(body) as? Map<*, *> }.getOrNull() ?: return null
        val message = (map["message"] as? String)?.takeUnless { it.isBlank() } ?: return null
        return if (message.length <= 120) message else message.take(117) + "…"
    }

    /** An exception as one readable clause — `java.net.ConnectException` helps nobody. */
    private fun shortReason(e: Exception): String = when (e) {
        is ConnectException -> "nothing is listening on that host and port"
        is java.net.UnknownHostException -> "the host name does not resolve"
        is java.net.http.HttpConnectTimeoutException -> "the connection timed out"
        is java.net.http.HttpTimeoutException -> "the request timed out"
        else -> e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
    }

    fun evaluate(req: Request): Outcome {
        if (req.baseUrl.isBlank()) return Outcome.Failed("Base URL is required")
        if (req.scopeId.isBlank()) return Outcome.Failed("A live scope id (process/case/task instance id) is required")
        val body = buildBody(req.expression, req.scopeType, req.scopeId, req.subScopeId)
        return try {
            // HTTP/1.1 on purpose: the JDK default (HTTP/2) sends an h2c upgrade over cleartext http://,
            // which some servers leave hanging until the timeout. Same reasoning as DesignClient.newClient.
            val client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build()
            val buildRequest: (URI) -> HttpRequest = { uri ->
                val builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
            // Basic auth for local dev apps; captured browser-session headers (Cookie / CSRF token /
            // bearer or basic Authorization) for IdP-fronted apps. Both may be present — the server uses
            // whichever its security chain honours. `header()` appends, so only add basic auth when the
            // capture didn't already bring an Authorization header (avoids two Authorization headers).
            val captured = req.sessionHeaders ?: emptyMap()
            val hasCapturedAuth = captured.keys.any { it.equals("Authorization", ignoreCase = true) }
                if (req.username.isNotBlank() && !hasCapturedAuth) {
                    val auth = "Basic " + Base64.getEncoder().encodeToString("${req.username}:${req.password}".toByteArray())
                    builder.header("Authorization", auth)
                }
                captured.forEach { (name, value) -> if (value.isNotBlank()) builder.header(name, value) }
                builder.build()
            }
            val resp = sendTryingEveryAddress(client, URI.create(endpoint(req.baseUrl)), buildRequest)
            val parsed = runCatching { parseResponse(resp.body()) }.getOrNull()
            when {
                parsed != null -> Outcome.Evaluated(parsed)
                // A 3xx (redirects aren't followed) or an HTML body means an SSO login bounce, not the DTO.
                resp.statusCode() in 300..399 || looksLikeLoginPage(resp.body()) -> Outcome.Failed(SSO_REDIRECT_HINT)
                resp.statusCode() in 200..299 -> Outcome.Failed("Unexpected empty response (HTTP ${resp.statusCode()})")
                resp.statusCode() == 401 || resp.statusCode() == 403 -> Outcome.Failed("Authentication failed (HTTP ${resp.statusCode()})")
                resp.statusCode() == 404 -> Outcome.Failed("Endpoint not found — is this a Flowable app with Inspect enabled? (HTTP 404)")
                // The server's own message when it sent one; never the raw body.
                else -> Outcome.Failed(
                    "The app answered HTTP ${resp.statusCode()}" +
                        serverMessage(resp.body())?.let { ": $it" }.orEmpty(),
                )
            }
        } catch (pce: ProcessCanceledException) {
            throw pce                      // a cancelled action is not a failure
        } catch (e: Exception) {
            LOG.warn("Inspect evaluation failed", e)
            Outcome.Failed("Cannot reach the app — ${shortReason(e)}")
        }
    }
}
