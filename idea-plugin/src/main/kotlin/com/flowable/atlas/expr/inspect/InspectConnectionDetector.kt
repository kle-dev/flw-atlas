package com.flowable.atlas.expr.inspect

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Best-effort discovery of the Flowable Inspect connection from the open project's Spring config, so
 * the playground's "Evaluate against app" form can pre-fill the base URL and admin user instead of
 * making the user type them. Reads the app defaults Flowable ships / a project overrides:
 *
 *  - base URL  ← `http://localhost:{server.port}{server.servlet.context-path}` (port defaults to 8080)
 *  - username  ← `flowable.common.app.idm-admin.user` (or `flowable.rest.app.admin.user-id`)
 *  - password  ← the matching `…password` — only when it is a literal dev value (not a `${…}` placeholder)
 *
 * `.properties` files are parsed fully; passwords sourced from secrets/env stay blank (the user types
 * them). The parsing half is pure and unit-tested; project file discovery is a separate PSI query.
 */
object InspectConnectionDetector {

    data class Connection(val baseUrl: String?, val username: String?, val password: String?) {
        val hasAny: Boolean get() = baseUrl != null || username != null || password != null
    }

    /**
     * Parse & fold `.properties` texts in ascending precedence (later overrides earlier). [portOverride]
     * / [contextPathOverride] (from a run configuration) win over the config files.
     */
    fun detect(propertyTexts: List<String>, portOverride: String? = null, contextPathOverride: String? = null): Connection {
        val props = LinkedHashMap<String, String>()
        for (text in propertyTexts) props.putAll(parseProperties(text))

        fun literal(key: String): String? = props[key]?.takeIf { it.isNotBlank() && !it.contains("\${") }

        val portFromProps = literal("server.port")
        val port = portOverride ?: portFromProps ?: "8080"
        val rawContext = contextPathOverride ?: literal("server.servlet.context-path") ?: literal("server.context-path")
        val contextPath = rawContext?.let { if (it.startsWith("/")) it else "/$it" }?.trimEnd('/') ?: ""
        // Only surface a base URL when we actually have a signal (a port or context path), not a bare guess.
        val haveSignal = portOverride != null || portFromProps != null || contextPathOverride != null || contextPath.isNotEmpty()
        val baseUrl = if (haveSignal) "http://localhost:$port$contextPath" else null

        val username = literal("flowable.common.app.idm-admin.user") ?: literal("flowable.rest.app.admin.user-id")
        val password = literal("flowable.common.app.idm-admin.password") ?: literal("flowable.rest.app.admin.password")
        return Connection(baseUrl, username, password)
    }

    /**
     * Discover config files in the project and detect a connection. Queries the filename index, so it
     * MUST be called off the EDT (it is a slow operation) — callers run it on a pooled thread and apply
     * the result back on the UI thread. Waits for indexes via [DumbService.runReadActionInSmartMode].
     * Uses the active Spring profile(s) from the run configuration to pick `application-<profile>.properties`,
     * and applies any run-time port / context-path override.
     */
    fun detect(project: Project): Connection {
        val hints = SpringRunConfigDetector.detect(project)
        return DumbService.getInstance(project).runReadActionInSmartMode<Connection> {
            val scope = GlobalSearchScope.projectScope(project)
            val texts = FilenameIndex.getAllFilesByExt(project, "properties", scope)
                .filter { isRelevant(it.name, hints.profiles) }
                .sortedBy { orderKey(it.name, hints.profiles) }
                .mapNotNull { runCatching { String(it.contentsToByteArray(), Charsets.UTF_8) }.getOrNull() }
            detect(texts, hints.port, hints.contextPath)
        }
    }

    /** flowable defaults + base application config always apply; profile files only for active profiles. */
    private fun isRelevant(name: String, activeProfiles: List<String>): Boolean = when {
        name.startsWith("flowable") -> true
        name == "application.properties" -> true
        name.startsWith("application-") -> activeProfiles.any { name == "application-$it.properties" }
        else -> false
    }

    private fun orderKey(name: String, activeProfiles: List<String>): Int = when {
        name.startsWith("flowable-default") || name.startsWith("flowable") -> 0
        name == "application.properties" -> 1
        name.startsWith("application-") -> 2 + activeProfiles.indexOfFirst { name == "application-$it.properties" }.coerceAtLeast(0)
        else -> 1
    }

    private fun parseProperties(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
            val sep = line.indexOfFirst { it == '=' || it == ':' }
            if (sep <= 0) continue
            val key = line.substring(0, sep).trim()
            val value = line.substring(sep + 1).trim()
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }
}
