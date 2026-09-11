package com.flowable.atlas.parsing

/**
 * Literal secrets in a model: a value that looks like a password, a token or an API key and is written
 * as plain text rather than as an expression. Only **paths** are recorded, never values — a report that
 * carried the secret it warns about would be the leak it describes.
 *
 * Two rules keep this quiet where it should be. A key that merely *talks about* a secret — where it
 * lives, what kind it is, how long it lasts (`tokenUrl`, `passwordField`, `credentialsType`) — is not
 * one; and a value that is an expression (`${…}`, `#{…}`, `{{…}}`) is resolved elsewhere and is exactly
 * what the finding asks for. A placeholder such as `changeme` *is* reported: a committed literal is a
 * literal, whatever it says.
 */
object SecretScan {

    private val KEY = Regex("(?i)(password|passwd|secret|token|api[-_]?key|credential|client[-_]?secret|private[-_]?key)")

    /** Suffixes that make a secret-ish key a description of the secret rather than the secret itself. */
    private val ABOUT = Regex(
        "(?i)(ref|name|field|type|header|location|url|uri|expiry|expires|ttl|length|policy|prefix|source|" +
            "scheme|mode|provider|id|kind|format|encoding|algorithm|version|enabled|required|flag|path|param|parameter|" +
            "alias|description|label|title|text|hint|help|question|validity|count|limit|model|izer|izers|rule|rules|" +
            "pattern|regex|mask|masked|strength|placeholder|hidden|visible)$",
    )

    /** Prefixes that make it a question or a quantity about the secret — `maxTokens`, `useTokenAuth`. */
    private val ABOUT_PREFIX = Regex("(?i)^(max|min|num|count|total|is|has|show|use|enable|allow|require|with)[A-Z_-]")

    /** `scheme://user:password@host` — credentials carried inside a URL. */
    private val URL_CREDENTIAL = Regex("[a-zA-Z][a-zA-Z0-9+.-]*://[^/\\s:@]+:[^/\\s@]+@")

    fun isSecretKey(name: String): Boolean =
        KEY.containsMatchIn(name) && !ABOUT.containsMatchIn(name) && !ABOUT_PREFIX.containsMatchIn(name)

    fun isLiteral(value: String): Boolean =
        value.isNotBlank() && !value.contains("\${") && !value.contains("#{") && !value.contains("{{")

    fun hasUrlCredential(value: String): Boolean = URL_CREDENTIAL.containsMatchIn(value)

    /**
     * Paths of every literal secret in a JSON model — `config.authentication.password`,
     * `operations[1].config.apiKey`, `config.baseUrl (credentials in URL)` — in document order.
     */
    fun scan(doc: Map<*, *>): List<String> {
        val out = ArrayList<String>()
        fun walk(node: Any?, path: String) {
            when (node) {
                is Map<*, *> -> for ((k, v) in node) {
                    val key = k.toString()
                    val p = if (path.isEmpty()) key else "$path.$key"
                    if (v is String) {
                        // both arms need a literal: `https://${user}:${pass}@host` is exactly the shape the
                        // finding asks for, and used to be reported as the leak it avoids
                        if (isSecretKey(key) && isLiteral(v)) out.add(p)
                        else if (isLiteral(v) && hasUrlCredential(v)) out.add("$p (credentials in URL)")
                    } else walk(v, p)
                }
                is List<*> -> node.forEachIndexed { i, v -> walk(v, "$path[$i]") }
            }
        }
        walk(doc, "")
        return out
    }

    /** The field-injection names on an element whose literal value is a secret, or carries one in a URL. */
    fun secretFields(literalFields: Map<String, String>): List<String> =
        literalFields.entries.filter { (name, value) ->
            isLiteral(value) && (isSecretKey(name) || hasUrlCredential(value))
        }.map { it.key }
}
