package com.flowable.atlas.expr.inspect

import com.intellij.openapi.diagnostic.logger
import com.flowable.atlas.model.MiniJson
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
 * local dev instance, and/or a browser session [cookie][Request.cookie] captured by "Sign in to app"
 * for an app fronted by SSO/OAuth2. Both can be sent at once — e.g. an OAuth2 gateway that grants
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
         * Browser session cookie(s) (`name=value; name2=value2`) captured by the "Sign in to app"
         * login, replayed for apps behind SSO/OAuth2 where basic auth can't pass the login redirect.
         * Null/blank when the app uses plain basic auth (a local dev instance). See [InspectSession].
         */
        val cookie: String? = null,
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

    fun evaluate(req: Request): Outcome {
        if (req.baseUrl.isBlank()) return Outcome.Failed("Base URL is required")
        if (req.scopeId.isBlank()) return Outcome.Failed("A live scope id (process/case/task instance id) is required")
        val body = buildBody(req.expression, req.scopeType, req.scopeId, req.subScopeId)
        return try {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint(req.baseUrl)))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
            // Basic auth for local dev apps; a captured SSO session cookie for IdP-fronted apps.
            // Both may be present — the server uses whichever its security chain honours.
            if (req.username.isNotBlank()) {
                val auth = "Basic " + Base64.getEncoder().encodeToString("${req.username}:${req.password}".toByteArray())
                builder.header("Authorization", auth)
            }
            if (!req.cookie.isNullOrBlank()) builder.header("Cookie", req.cookie)
            val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            val parsed = runCatching { parseResponse(resp.body()) }.getOrNull()
            when {
                parsed != null -> Outcome.Evaluated(parsed)
                // A 3xx (redirects aren't followed) or an HTML body means an SSO login bounce, not the DTO.
                resp.statusCode() in 300..399 || looksLikeLoginPage(resp.body()) -> Outcome.Failed(SSO_REDIRECT_HINT)
                resp.statusCode() in 200..299 -> Outcome.Failed("Unexpected empty response (HTTP ${resp.statusCode()})")
                resp.statusCode() == 401 || resp.statusCode() == 403 -> Outcome.Failed("Authentication failed (HTTP ${resp.statusCode()})")
                resp.statusCode() == 404 -> Outcome.Failed("Endpoint not found — is this a Flowable app with Inspect enabled? (HTTP 404)")
                else -> Outcome.Failed("HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
            }
        } catch (e: Exception) {
            LOG.warn("Inspect evaluation failed", e)
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
