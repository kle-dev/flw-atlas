package com.flowable.atlas.expr.inspect

import com.intellij.execution.RunManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

/**
 * Reads the project's IntelliJ run configurations to find how a Flowable app is actually launched —
 * the active Spring profile(s) and any `server.port` / `server.servlet.context-path` overrides passed
 * as VM options (`-Dserver.port=…`), program args (`--server.port=…`), env vars (`SERVER_PORT`), or the
 * Spring Boot run config's dedicated "Active profiles" field. These hints let [InspectConnectionDetector]
 * pick the right `application-<profile>.properties` and honour a port/context-path chosen at run time.
 *
 * Parameter extraction is pure and unit-tested; the run-config lookup uses only the platform
 * `RunManager` + `CommonProgramRunConfigurationParameters` (no Spring-plugin dependency — the dedicated
 * "Active profiles" field is read reflectively and best-effort).
 */
object SpringRunConfigDetector {

    private val LOG = logger<SpringRunConfigDetector>()

    data class RunHints(val profiles: List<String>, val port: String?, val contextPath: String?) {
        val hasAny: Boolean get() = profiles.isNotEmpty() || port != null || contextPath != null
    }

    private const val PROFILES = "spring.profiles.active"
    private const val PORT = "server.port"
    private const val CONTEXT = "server.servlet.context-path"

    /** Pure extraction from launch parameters + the (reflectively read) "Active profiles" field. */
    fun fromParameters(vmParameters: String?, programParameters: String?, env: Map<String, String>, activeProfilesField: String?): RunHints {
        val tokens = tokenize(vmParameters) + tokenize(programParameters)
        val profiles = LinkedHashSet<String>()
        activeProfilesField?.let { profiles += splitProfiles(it) }
        paramValue(tokens, PROFILES)?.let { profiles += splitProfiles(it) }
        env["SPRING_PROFILES_ACTIVE"]?.let { profiles += splitProfiles(it) }

        val port = paramValue(tokens, PORT) ?: env["SERVER_PORT"]
        val contextPath = paramValue(tokens, CONTEXT) ?: env["SERVER_SERVLET_CONTEXT_PATH"] ?: env["SERVER_SERVLET_CONTEXTPATH"]
        return RunHints(profiles.toList(), port?.takeIf { it.isNotBlank() }, contextPath?.takeIf { it.isNotBlank() })
    }

    /**
     * Inspect the project's run configurations; prefer a Spring Boot one, else the first with any hint.
     * Read reflectively so there is no compile-time dependency on the Spring / execution-parameters API
     * (Application, Spring Boot, Maven/Gradle run configs all expose these getters).
     */
    fun detect(project: Project): RunHints = ReadAction.computeBlocking<RunHints, RuntimeException> {
        val settings = RunManager.getInstance(project).allSettings
            .sortedByDescending { it.type.id.contains("SpringBoot", ignoreCase = true) }
        for (s in settings) {
            val cfg = s.configuration
            val hints = fromParameters(
                reflectString(cfg, "getVMParameters"),
                reflectString(cfg, "getProgramParameters"),
                reflectEnvs(cfg),
                reflectString(cfg, "getActiveProfiles"),
            )
            if (hints.hasAny) return@computeBlocking hints
        }
        RunHints(emptyList(), null, null)
    }

    // These two are the only reflection in the plugin: the Spring Boot run configuration is not on our
    // compile classpath (the Spring plugin is paid-tier and we declare no dependency on it), so its
    // getters can only be reached by name. That makes them the most fragile code we ship — a JetBrains
    // rename degrades detection to "nothing found" with no other symptom. Hence the logging: DEBUG for
    // the expected NoSuchMethodException on an IDE without the getter, WARN when the method exists but
    // the call itself fails, which is the signal that the duck-typing assumption broke.
    private fun reflectString(cfg: Any, method: String): String? = runCatching {
        cfg.javaClass.getMethod(method).invoke(cfg) as? String
    }.onFailure { logReflectionFailure(cfg, method, it) }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun reflectEnvs(cfg: Any): Map<String, String> = runCatching {
        cfg.javaClass.getMethod("getEnvs").invoke(cfg) as? Map<String, String>
    }.onFailure { logReflectionFailure(cfg, "getEnvs", it) }.getOrNull() ?: emptyMap()

    private fun logReflectionFailure(cfg: Any, method: String, t: Throwable) {
        val where = "${cfg.javaClass.name}.$method()"
        if (t is NoSuchMethodException) LOG.debug("Run configuration has no $where — skipping that hint")
        else LOG.warn("Reading $where reflectively failed — Inspect connection hints may be incomplete", t)
    }

    private fun tokenize(s: String?): List<String> = s?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+")) ?: emptyList()

    private fun splitProfiles(s: String): List<String> = s.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }

    /** Value of `-D<key>=…` / `--<key>=…` among [tokens], or null. */
    private fun paramValue(tokens: List<String>, key: String): String? {
        for (t in tokens) {
            for (prefix in listOf("-D$key=", "--$key=")) {
                if (t.startsWith(prefix)) return t.removePrefix(prefix).trim('"', '\'')
            }
        }
        return null
    }
}
