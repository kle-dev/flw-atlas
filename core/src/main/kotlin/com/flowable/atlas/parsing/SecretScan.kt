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

    // ---- redaction -------------------------------------------------------------------------------------
    //
    // The finding names a path; the artifacts used to print the value at that path anyway — a field
    // injection's map, a service's base URL, a REST call's node id — so the same run that warned about a
    // password published it in graph.json, the explorer and summary.md. Values are masked where the parsers
    // capture them, once, so every renderer inherits it. The finding inputs (SecretScan.scan on the raw
    // document, XmlHelpers.readLiteralFields) are read before any of this and stay exactly as they were.

    /** What stands in for a masked value — short, unmistakable, and never a key anything resolves. */
    const val REDACTED = "***"

    /** `scheme://userinfo@` — group 1 the scheme, group 2 the userinfo. */
    private val URL_USERINFO = Regex("([a-zA-Z][a-zA-Z0-9+.-]*://)([^/\\s:@]+:[^/\\s@]+)@")

    /**
     * One query parameter: group 1 the separator, 2 the name, 3 the value — everything up to the next
     * parameter, so a templated value (`$skiptoken=<cookie pagenumber='${…}' />`, from a real service) is
     * judged whole: cut at its first space it looked like a literal, and masking it broke the URL.
     */
    private val QUERY_PARAM = Regex("([?&])([^=&#\\s\"']+)=([^&#]*)")

    /** One `Name: value` line of a header blob: group 1 the name with its colon, 2 the value. */
    private val HEADER_LINE = Regex("(?m)^(\\s*[A-Za-z0-9_-]+\\s*:\\s*)(\\S.*?)\\s*$")

    private val AUTH_HEADERS = setOf("authorization", "proxy-authorization")

    /** [value] with the literal userinfo of every URL in it masked; the same instance when there is none. */
    fun redactUserInfo(value: String): String {
        if (!value.contains("://")) return value
        val out = URL_USERINFO.replace(value) { m ->
            // `https://${user}:${pw}@host` is the fix, not the leak — left as written
            if (isLiteral(m.groupValues[2])) m.groupValues[1] + REDACTED + "@" else m.value
        }
        return if (out == value) value else out
    }

    /**
     * A URL as an artifact may show it: literal credentials in the userinfo (the whole `user:password`
     * becomes [REDACTED]) and the value of a query parameter named like a secret (`?access_token=***`)
     * masked. Everything a reader or the REST matcher uses — scheme, host, path, the other parameters — is
     * untouched, and a URL with nothing to mask comes back as the same instance, so the goldens of a project
     * without credentials do not move.
     */
    fun redactUrl(url: String): String {
        val noUser = redactUserInfo(url)
        if (!noUser.contains('?')) return noUser
        val out = QUERY_PARAM.replace(noUser) { m ->
            // a credential is one token: a value with a space in it is a template or prose, not a key
            val v = m.groupValues[3]
            if (isSecretKey(m.groupValues[2]) && isLiteral(v) && v.none { it.isWhitespace() }) m.groupValues[1] + m.groupValues[2] + "=" + REDACTED
            else m.value
        }
        return if (out == url) url else out
    }

    /** [url] through [redactUrl] when it is a string; anything else unchanged. */
    fun redactAnyUrl(url: Any?): Any? = if (url is String) redactUrl(url) else url

    /**
     * A field injection's value as the artifacts may carry it: the whole value when the field names a secret
     * and holds a literal; a URL field (`requestUrl`) through [redactUrl]; the credential of an
     * `Authorization` (or secret-named) line in a header field; the userinfo of any URL anywhere else; and a
     * secret-named `"key": "value"` pair in any of them (see [redactJsonPairs]). Query parameters are masked
     * only in a URL field — in a script body `?name=` is not a query string.
     */
    fun redactFieldValue(name: String, value: Any?): Any? {
        if (value !is String) return value
        if (isSecretKey(name) && isLiteral(value)) return REDACTED
        val low = name.lowercase()
        var out = if (low.endsWith("url") || low.endsWith("uri")) redactUrl(value) else redactUserInfo(value)
        if (low.contains("header")) out = redactHeaders(out)
        return redactJsonPairs(out)
    }

    /** `"name": "value"` — group 1 everything up to the value, 2 the name, 3 the value. */
    private val JSON_PAIR = Regex("(\"([^\"\\\\\\s]+)\"\\s*:\\s*\")([^\"\\\\]*)\"")

    /**
     * [text] with the literal value of every secret-named `"key": "value"` pair masked — an HTTP task's
     * `requestBody` of `{"password": "…"}` is the same credential as a `password` field, one level down. The
     * key, the quotes and everything around them stay, so a body still reads as the JSON it is.
     */
    fun redactJsonPairs(text: String): String {
        if (!text.contains(':')) return text
        val out = JSON_PAIR.replace(text) { m ->
            if (isSecretKey(m.groupValues[2]) && isLiteral(m.groupValues[3])) m.groupValues[1] + REDACTED + "\"" else m.value
        }
        return if (out == text) text else out
    }

    /** A `Name: value` header blob with the value of every credential-carrying literal header masked. */
    fun redactHeaders(blob: String): String {
        if (!blob.contains(':')) return blob
        val out = HEADER_LINE.replace(blob) { m ->
            val header = m.groupValues[1].substringBefore(':').trim()
            if (isCredentialHeader(header) && isLiteral(m.groupValues[2])) m.groupValues[1] + REDACTED else m.value
        }
        return if (out == blob) blob else out
    }

    /** A header that carries a credential: `Authorization`, `Proxy-Authorization`, or one named like a secret. */
    fun isCredentialHeader(name: String): Boolean = name.lowercase() in AUTH_HEADERS || isSecretKey(name)

    /**
     * The default a REST handler declares for a parameter, as the artifacts may carry it: a literal default of
     * a credential header (`@RequestHeader(value = "Authorization", defaultValue = "Bearer …")`) or of a
     * parameter named like a secret (`@RequestParam(name = "api_key", defaultValue = "…")`) is [REDACTED] —
     * the endpoint page shows that the handler falls back to a value, never the value.
     */
    fun redactParamDefault(name: String, source: String, value: String): String =
        if (isLiteral(value) && (isSecretKey(name) || source == "header" && isCredentialHeader(name))) REDACTED else value
}
