package com.flowable.atlas.parsing

/**
 * The keys a Spring Boot configuration file defines, with the line of each — `application.properties`,
 * `application-<profile>.yml` and the like. Only the keys: a model that reads
 * `environment.getProperty('crm.endpoint')` needs to know where `crm.endpoint` is set, not to what (the
 * value may be a secret, and it is the environment's business).
 *
 * Not a full YAML parser: block mappings by indentation, which is how configuration is written. A list
 * item, a flow collection or a block scalar's content defines no further key, and a `---` starts a new
 * document with an empty path.
 */
object SpringProperties {

    /** A `.properties`, `.yml` or `.yaml` Spring Boot configuration file, by its name. */
    fun isConfigFile(name: String): Boolean {
        val low = name.lowercase()
        val base = low.substringBeforeLast('.')
        val ext = low.substringAfterLast('.', "")
        return ext in EXTENSIONS && (base == "application" || base.startsWith("application-") ||
            base == "bootstrap" || base.startsWith("bootstrap-"))
    }

    /** `(key, 1-based line)` for every key [text] defines, in file order. */
    fun keys(text: String, fileName: String): List<Pair<String, Int>> =
        if (fileName.lowercase().endsWith(".properties")) propertiesKeys(text) else yamlKeys(text)

    private val EXTENSIONS = setOf("properties", "yml", "yaml")

    private fun propertiesKeys(text: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        var continued = false
        for ((i, raw) in text.lines().withIndex()) {
            val line = raw.trimStart()
            // A value ending in an odd number of backslashes runs on to the next line, which is no key.
            val wasContinued = continued
            continued = raw.takeLastWhile { it == '\\' }.length % 2 == 1
            if (wasContinued || line.isEmpty() || line[0] == '#' || line[0] == '!') continue
            val end = line.indexOfFirst { it == '=' || it == ':' || it.isWhitespace() }.let { if (it < 0) line.length else it }
            val key = line.substring(0, end).replace("\\", "")
            if (key.isNotEmpty()) out.add(key to i + 1)
        }
        return out
    }

    private val YAML_KEY_RE = Regex("""^( *)(?:"([^"]+)"|'([^']+)'|([^\s#'"\-{\[][^:#]*?))\s*:(?:\s+(.*))?$""")

    private fun yamlKeys(text: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        // (indent, key) of every open mapping above the current line
        val path = ArrayList<Pair<Int, String>>()
        var scalarIndent = -1           // inside a `|` / `>` block scalar indented deeper than this
        for ((i, raw) in text.lines().withIndex()) {
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            if (raw.startsWith("---") || raw.startsWith("...")) { path.clear(); scalarIndent = -1; continue }
            val indent = raw.length - raw.trimStart().length
            if (scalarIndent >= 0) { if (indent > scalarIndent) continue else scalarIndent = -1 }
            val m = YAML_KEY_RE.find(raw.trimEnd()) ?: continue
            val key = (m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }).trim()
            if (key.isEmpty()) continue
            while (path.isNotEmpty() && path.last().first >= indent) path.removeAt(path.size - 1)
            val full = (path.map { it.second } + key).joinToString(".")
            val value = m.groupValues[5].substringBefore(" #").trim()
            // A key with nothing after the colon opens a mapping — or holds a list, which is a value
            // too, so it is recorded either way.
            out.add(full to i + 1)
            when {
                value.isEmpty() -> path.add(indent to key)
                value.startsWith("|") || value.startsWith(">") -> scalarIndent = indent
            }
        }
        return out
    }
}
