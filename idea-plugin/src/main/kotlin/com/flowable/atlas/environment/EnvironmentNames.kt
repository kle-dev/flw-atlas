package com.flowable.atlas.environment

/**
 * Suggests what to call an environment, given the URL that is about to go into it.
 *
 * Used where a name is asked for right after a URL is known: the editor's "add environment" flow, and
 * the playground's *Save as connection…* dialog after a Work URL was pasted. A prefilled `QA` that the
 * user confirms beats an empty field they have to invent something for, and it beats the raw host,
 * which is what they would otherwise type.
 *
 * Pure, so the guesses are testable rather than discovered in a running IDE. Only ever a *suggestion* —
 * every caller shows it in an editable field.
 */
object EnvironmentNames {

    /**
     * A name for the environment [baseUrl] belongs in, not colliding with [taken].
     *
     * A stage token *is* used, because a recognisable `QA` beats `qa-design.example.com` by a mile —
     * but only when it appears as a whole dash-separated part of the host's **first label**. That
     * restriction is the whole safety story: `design.acme-prod-services.example.com` has first label
     * `design`, so it is never suggested as *PROD*. A substring search anywhere in the host would have
     * suggested exactly that, and a wrongly authoritative `PROD` on a dev server is worse than a
     * neutral host name.
     */
    fun suggest(baseUrl: String, taken: Set<String> = emptySet()): String {
        val host = BaseUrls.host(baseUrl)
        val base = when {
            host.isBlank() -> "New Environment"
            BaseUrls.isLoopback(host) -> "Local"
            else -> stageToken(host) ?: host
        }
        if (base !in taken) return base
        var suffix = 2
        while ("$base ($suffix)" in taken) suffix++
        return "$base ($suffix)"
    }

    /** The stage a host names, if its first label says so outright. */
    private fun stageToken(host: String): String? {
        val firstLabel = host.substringBefore('.')
        val part = firstLabel.split('-', '_').firstOrNull { it in STAGE_TOKENS } ?: return null
        return CANONICAL[part] ?: part.uppercase()
    }

    private val STAGE_TOKENS = setOf(
        "dev", "dev1", "dev2", "dev3", "qa", "qa1", "qa2", "uat", "test", "sit",
        "stage", "staging", "preprod", "prod", "production", "sandbox", "int", "integration",
    )

    /** Long spellings that read better short — nobody labels a tab "PRODUCTION". */
    private val CANONICAL = mapOf(
        "production" to "PROD",
        "staging" to "STAGE",
        "integration" to "INT",
    )
}
