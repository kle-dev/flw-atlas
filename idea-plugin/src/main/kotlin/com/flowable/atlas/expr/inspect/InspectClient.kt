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
 * endpoint: `POST {baseUrl}/inspect-api/evaluate-expression` (HTTP Basic auth).
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

    fun evaluate(req: Request): Outcome {
        if (req.baseUrl.isBlank()) return Outcome.Failed("Base URL is required")
        if (req.scopeId.isBlank()) return Outcome.Failed("A live scope id (process/case/task instance id) is required")
        val body = buildBody(req.expression, req.scopeType, req.scopeId, req.subScopeId)
        val auth = "Basic " + Base64.getEncoder().encodeToString("${req.username}:${req.password}".toByteArray())
        return try {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            val request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint(req.baseUrl)))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", auth)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
            val parsed = runCatching { parseResponse(resp.body()) }.getOrNull()
            when {
                parsed != null -> Outcome.Evaluated(parsed)
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
