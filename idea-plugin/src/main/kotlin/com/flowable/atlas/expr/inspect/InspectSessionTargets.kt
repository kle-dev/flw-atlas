package com.flowable.atlas.expr.inspect

import com.flowable.atlas.environment.BaseUrls
import com.flowable.atlas.environment.ConnectionKind

/**
 * The Work apps this IDE session has been pointed at by pasting a link, but which are **not**
 * environments — the playground's temporary targets.
 *
 * A list rather than the single field this used to be. One slot meant the second pasted link silently
 * evicted the first, which is exactly backwards: pasting two links is what someone does when they are
 * comparing two apps, and having to re-paste the one they just looked at is the cost of a decision the
 * user never made. Every paste keeps its own entry until it is explicitly forgotten, or saved as a
 * real environment.
 *
 * Deliberately **not persisted** and application-level:
 *  - not persisted, for the same reason [InspectSession] is not — a target you looked at once should
 *    leave nothing behind, and an environment is something you decide to have, in *Settings →
 *    Environments*;
 *  - application-level, because the playground exists both as a tool window and as a tab on every
 *    `*.explorer.html` editor, and a target pasted in one of them that the next one cannot see would
 *    read as the paste having been lost.
 *
 * Entries are deduped by [BaseUrls.comparisonKey], so pasting two links into the same app adds one
 * entry — a trailing slash or a `#/…` route is not a different app.
 *
 * Order is insertion order: the picker reads as a history, and a target does not move because it was
 * pasted again.
 */
object InspectSessionTargets {

    /** Keyed by comparison key so `…/work` and `…/work/` are one target; the value is what is shown. */
    private val targets = LinkedHashMap<String, String>()

    private fun key(baseUrl: String): String = BaseUrls.comparisonKey(ConnectionKind.WORK, baseUrl)

    /** Every target, in the order they were first pasted. */
    fun all(): List<String> = synchronized(targets) { targets.values.toList() }

    /**
     * Registers [baseUrl] and returns the spelling now held for it — the **existing** one when this app
     * is already a target, so the caller selects the entry the picker is showing rather than adding a
     * second row for the same app.
     */
    fun add(baseUrl: String): String = synchronized(targets) {
        val normalized = BaseUrls.normalize(ConnectionKind.WORK, baseUrl)
        if (normalized.isBlank()) return normalized
        targets.getOrPut(key(normalized)) { normalized }
    }

    fun contains(baseUrl: String): Boolean =
        baseUrl.isNotBlank() && synchronized(targets) { targets.containsKey(key(baseUrl)) }

    /**
     * Drops the entry. Says nothing about [InspectSession]: forgetting a target should drop the
     * credentials captured for it, while *saving* it as an environment must keep them — an SSO cookie
     * is the only thing that can reach that app, and the environment has no place to store it. Both
     * callers are in the playground, where that difference is the point.
     */
    fun remove(baseUrl: String) {
        if (baseUrl.isBlank()) return
        synchronized(targets) { targets.remove(key(baseUrl)) }
    }

    fun clear() = synchronized(targets) { targets.clear() }
}
