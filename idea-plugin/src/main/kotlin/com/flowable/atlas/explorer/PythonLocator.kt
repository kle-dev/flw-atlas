package com.flowable.atlas.explorer

import com.intellij.openapi.diagnostic.logger
import com.flowable.atlas.settings.FlowableAtlasSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.util.ExecUtil

/**
 * Locates a usable Python 3 interpreter (>= 3.8, which the Atlas generator requires). Prefers an
 * explicit path from settings, otherwise probes `python3` then `python` on the PATH.
 */
object PythonLocator {

    private val LOG = logger<PythonLocator>()

    /** Either a usable interpreter ([exe] + [version]) or an [error] explaining what's missing. */
    data class Result(val exe: String?, val version: String?, val error: String?) {
        val isUsable: Boolean get() = exe != null
    }

    fun locate(): Result {
        val configured = FlowableAtlasSettings.getInstance().pythonInterpreterPath.trim()
        val candidates: List<String> = if (configured.isNotEmpty()) {
            listOf(configured)
        } else {
            listOfNotNull(
                PathEnvironmentVariableUtil.findInPath("python3")?.absolutePath,
                PathEnvironmentVariableUtil.findInPath("python")?.absolutePath,
            )
        }

        if (candidates.isEmpty()) {
            return Result(null, null, "No Python 3 interpreter found on PATH. Set one in Settings → Tools → Flowable Atlas.")
        }

        var sawOld = false
        for (exe in candidates) {
            val v = probeVersion(exe) ?: continue
            if (isAtLeast38(v)) return Result(exe, v, null)
            sawOld = true
        }
        return Result(
            null, null,
            if (sawOld) "Python found but older than 3.8 — the Atlas generator requires Python 3.8+."
            else "Could not run the configured Python interpreter. Check the path in Settings → Tools → Flowable Atlas.",
        )
    }

    /** Runs `<exe> --version` and parses the reported version (e.g. "3.11.6"), or null on failure. */
    private fun probeVersion(exe: String): String? = try {
        val out = ExecUtil.execAndGetOutput(GeneralCommandLine(exe, "--version"))
        val text = (out.stdout + " " + out.stderr).trim()   // some pythons print --version to stderr
        Regex("""(\d+)\.(\d+)(?:\.\d+)?""").find(text)?.value
    } catch (e: Exception) {
        LOG.debug("python probe failed", e)
        null
    }

    private fun isAtLeast38(version: String): Boolean {
        val parts = version.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return false
        val major = parts[0]
        val minor = parts.getOrElse(1) { 0 }
        return major > 3 || (major == 3 && minor >= 8)
    }
}
