package com.flowable.atlas.expr.catalog

import com.flowable.atlas.model.MiniJson
import java.io.File

/**
 * Custom frontend functions a project registers via `flowable.externals.additionalData`.
 *
 * The Work runtime spreads `externals.additionalData` into the frontend expression scope (its
 * top-level keys) and merges `additionalData.flw` onto `flw` (see `hookEvalExpression`). Projects
 * expose their own helpers there — e.g. the KYC app registers a `flowkyc` namespace called as
 * `{{ flowkyc.foo(x) }}`. When that source is in the project we read the real names so those calls
 * validate *precisely* (known member → valid, close typo → suspect) instead of staying lenient.
 *
 * This is the JVM twin of `extract_custom_functions` in `flowable_atlas.py` — kept in parity.
 */
data class CustomFunctionCatalog(
    val namespaces: Map<String, Set<String>>,
    val flw: Set<String>,
    val topLevel: Set<String>,
    val sources: List<String>,
    val diagnostics: List<String>,
    /** Best-effort parameter text per display name (`ns.member`, `flw.member`, or top-level name),
     *  e.g. `"customer, docs"` — for completion tail text / insertion. Absent when not a function. */
    val signatures: Map<String, String> = emptyMap(),
) {
    fun isEmpty(): Boolean = namespaces.isEmpty() && flw.isEmpty() && topLevel.isEmpty()

    /** Parameter list of a callable by display name (`ns.member` / `flw.member` / top-level), or null. */
    fun signatureOf(display: String): String? = signatures[display]

    fun summary(): String {
        val parts = ArrayList<String>()
        namespaces.toSortedMap().forEach { (ns, m) -> parts += "$ns.* (${m.size})" }
        if (flw.isNotEmpty()) parts += "flw.* (+${flw.size})"
        if (topLevel.isNotEmpty()) parts += "${topLevel.size} top-level"
        val src = sources.firstOrNull()?.let { " from $it" } ?: ""
        return parts.joinToString(", ") + src
    }

    companion object {
        val EMPTY = CustomFunctionCatalog(emptyMap(), emptySet(), emptySet(), emptyList(), emptyList(), emptyMap())
    }
}

/**
 * Best-effort STATIC extraction of a project's `externals.additionalData` custom functions from
 * readable source (`ext/custom.js` is usually a compiled bundle we can't read). We anchor on
 * `export default { … additionalData … }` (following one import hop) or a direct
 * `externals.additionalData = { … }`. Constructs we can't resolve (spreads, computed keys, dynamic
 * assembly) are recorded in `diagnostics`, never guessed.
 */
object CustomFunctionExtractor {

    private val SKIP_DIRS = setOf(
        "node_modules", "dist", "build", "target", ".git", ".idea",
        ".gradle", "coverage", "storybook-static", "__pycache__", ".next",
    )
    private val SRC_EXT = listOf(".ts", ".tsx", ".js", ".jsx", ".mjs")
    private const val MAX_SIZE = 2_000_000L
    private const val MAX_FILES = 20_000

    // Anchored at the start of the supplied substring (see keyAt). `$` is a valid JS identifier char.
    private val IDENT_KEY = Regex("""^\s*(?:['"]([A-Za-z_$][\w$]*)['"]|([A-Za-z_$][\w$]*))""")

    private sealed interface Kind {
        data class Obj(val open: Int, val close: Int) : Kind
        object Plain : Kind
        object Spread : Kind
        object Computed : Kind
        object None : Kind
    }

    private class Cat {
        val namespaces = HashMap<String, MutableSet<String>>()
        val flw = HashSet<String>()
        val topLevel = HashSet<String>()
        val sources = ArrayList<String>()
        val diagnostics = ArrayList<String>()
        val signatures = HashMap<String, String>()
        fun build() = CustomFunctionCatalog(
            namespaces.mapValues { it.value.toSet() }, flw.toSet(), topLevel.toSet(),
            sources.toList(), diagnostics.toList(), signatures.toMap(),
        )
    }

    /** Scan [root] (or an [explicit] dir/file) for custom functions. Returns null when none are found. */
    fun extract(root: File, explicit: File? = null): CustomFunctionCatalog? {
        val cat = Cat()
        val seen = HashSet<String>()
        for (f in entryCandidates(root, explicit)) {
            try {
                absorbFromFile(f, cat, seen, root)
            } catch (e: Exception) {
                cat.diagnostics += "custom-extract error in ${f.path}: ${e.message}"
            }
        }
        val built = cat.build()
        return if (built.isEmpty()) null else built
    }

    // ---- masking (blank comment & string contents so structure scanning isn't fooled) ----
    private fun mask(text: String): String {
        val out = text.toCharArray()
        var i = 0
        val n = text.length
        var state = 0  // 0 none, 1 line, 2 block, 3 ', 4 ", 5 `
        while (i < n) {
            val c = text[i]
            when (state) {
                0 -> when {
                    c == '/' && i + 1 < n && text[i + 1] == '/' -> { out[i] = ' '; out[i + 1] = ' '; i += 2; state = 1; continue }
                    c == '/' && i + 1 < n && text[i + 1] == '*' -> { out[i] = ' '; out[i + 1] = ' '; i += 2; state = 2; continue }
                    c == '\'' -> state = 3
                    c == '"' -> state = 4
                    c == '`' -> state = 5
                }
                1 -> { if (c == '\n') state = 0 else out[i] = ' ' }
                2 -> { if (c == '*' && i + 1 < n && text[i + 1] == '/') { out[i] = ' '; out[i + 1] = ' '; i += 2; state = 0; continue } else if (c != '\n') out[i] = ' ' }
                else -> {  // inside a string/template
                    if (c == '\\' && i + 1 < n) { out[i] = ' '; if (text[i + 1] != '\n') out[i + 1] = ' '; i += 2; continue }
                    val delim = when (state) { 3 -> '\''; 4 -> '"'; else -> '`' }
                    if (c == delim) state = 0 else if (c != '\n') out[i] = ' '
                }
            }
            i++
        }
        return String(out)
    }

    /** `masked[open]` is an opening bracket; return the index of its matching close, or -1. */
    private fun matchBrace(masked: String, open: Int): Int {
        var depth = 0
        for (i in open until masked.length) {
            when (masked[i]) {
                '{', '(', '[' -> depth++
                '}', ')', ']' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }

    /** Yield (start, end) spans of the top-level, comma-separated entries of an object literal. */
    private fun objectEntries(masked: String, braceOpen: Int, braceClose: Int): List<IntRange> {
        val spans = ArrayList<IntRange>()
        var depth = 0
        var s = braceOpen + 1
        var i = braceOpen + 1
        while (i < braceClose) {
            when (masked[i]) {
                '{', '(', '[' -> depth++
                '}', ')', ']' -> depth--
                ',' -> if (depth == 0) { spans += s until i; s = i + 1 }
            }
            i++
        }
        if (masked.substring(s, braceClose).isNotBlank()) spans += s until braceClose
        return spans
    }

    private fun keyAt(orig: String, lead: Int, end: Int): Pair<String, Int>? {
        val m = IDENT_KEY.find(orig.substring(lead, end)) ?: return null
        val key = m.groupValues[1].ifEmpty { m.groupValues[2] }
        if (key.isEmpty()) return null
        return key to (lead + m.value.length)  // key name + absolute index just past it
    }

    private fun entryKeyAndKind(masked: String, orig: String, s: Int, e: Int): Pair<String?, Kind> {
        val seg = masked.substring(s, e)
        val lead = s + (seg.length - seg.trimStart().length)
        val stripped = seg.trimStart()
        if (stripped.startsWith("...")) return null to Kind.Spread
        if (stripped.startsWith("[")) return null to Kind.Computed
        val (key, keyEnd) = keyAt(orig, lead, e) ?: return (null to Kind.None)
        var j = keyEnd
        while (j < e && masked[j].isWhitespace()) j++
        if (j >= e) return key to Kind.Plain                 // shorthand property
        when (masked[j]) {
            '(' -> return key to Kind.Plain                  // method shorthand
            ':' -> {
                j++
                while (j < e && masked[j].isWhitespace()) j++
                if (j < e && masked[j] == '{') {
                    val close = matchBrace(masked, j)
                    if (close != -1) return key to Kind.Obj(j, close)
                }
                return key to Kind.Plain
            }
        }
        return key to Kind.Plain
    }

    private fun memberNames(
        masked: String, orig: String, open: Int, close: Int, diag: MutableList<String>, ctx: String,
        sigs: MutableMap<String, String>? = null, prefix: String = "",
    ): Set<String> {
        val names = HashSet<String>()
        for (span in objectEntries(masked, open, close)) {
            val (key, kind) = entryKeyAndKind(masked, orig, span.first, span.last + 1)
            if (key != null) {
                names += key
                if (sigs != null) memberSignature(masked, orig, span.first, span.last + 1)?.let { sigs[prefix + key] = it }
            } else if (kind is Kind.Spread || kind is Kind.Computed)
                diag += "$ctx: unresolved ${if (kind is Kind.Spread) "spread" else "computed"} entry (members may be incomplete)"
        }
        return names
    }

    // ---- signature (parameter list) extraction — parity with flowable_atlas.py ----

    /** Text between `masked[openParen]=='('` and its match, whitespace-collapsed and comma-spaced
     *  (read from orig); null if unbalanced. Minified sources have no spaces, so `e,t,r` → `e, t, r`. */
    private fun parenText(masked: String, orig: String, openParen: Int): String? {
        val close = matchBrace(masked, openParen)
        if (close == -1) return null
        return orig.substring(openParen + 1, close).trim()
            .replace(Regex("""\s+"""), " ").replace(Regex("""\s*,\s*"""), ", ")
    }

    /** Params of a same-file `function name(…)`, `… name = function(…)`, `… name = (…) =>`, or
     *  `… name = x =>` declaration — for compiled bundles where a member is an identifier ref. */
    private fun resolveFnSignature(masked: String, orig: String, name: String): String? {
        val n = Regex.escape(name)
        val pats = listOf(
            Regex("""\bfunction\s+$n\s*\("""),
            Regex("""\b(?:var|let|const)\s+$n\s*=\s*function\b\s*(?:[A-Za-z_$][\w$]*\s*)?\("""),
            Regex("""\b(?:var|let|const)\s+$n\s*=\s*\("""),
        )
        for (p in pats) p.find(masked)?.let { return parenText(masked, orig, it.range.last) }  // '(' is the last matched char
        return Regex("""\b(?:var|let|const)\s+$n\s*=\s*([A-Za-z_$][\w$]*)\s*=>""").find(masked)?.groupValues?.get(1)
    }

    /** Params of a value expression starting at [j]: a function/arrow literal, or a resolvable identifier ref. */
    private fun valueSignature(masked: String, orig: String, j: Int, end: Int): String? {
        if (j >= end) return null
        val tail = masked.substring(j, end)
        Regex("""^function\b\s*(?:[A-Za-z_$][\w$]*\s*)?""").find(tail)?.let { fm ->
            val k = j + fm.value.length
            return if (k < end && masked[k] == '(') parenText(masked, orig, k) else null
        }
        if (masked[j] == '(') return parenText(masked, orig, j)
        Regex("""^([A-Za-z_$][\w$]*)\s*=>""").find(tail)?.let { return it.groupValues[1] }
        return Regex("""^([A-Za-z_$][\w$]*)\s*$""").find(tail)?.let { resolveFnSignature(masked, orig, it.groupValues[1]) }
    }

    /** Best-effort parameter text of an object entry whose value is a function; null when it isn't. */
    private fun memberSignature(masked: String, orig: String, s: Int, e: Int): String? {
        val (key, kind) = entryKeyAndKind(masked, orig, s, e)
        if (key == null || kind is Kind.Obj) return null
        val seg = masked.substring(s, e)
        val lead = s + (seg.length - seg.trimStart().length)
        val m = IDENT_KEY.find(orig.substring(lead, e)) ?: return null
        var j = lead + m.value.length
        while (j < e && masked[j].isWhitespace()) j++
        if (j < e && masked[j] == '(') return parenText(masked, orig, j)   // method shorthand
        if (j < e && masked[j] == ':') {
            j++
            while (j < e && masked[j].isWhitespace()) j++
            return valueSignature(masked, orig, j, e)
        }
        return null
    }

    private fun findExportDefaultObject(masked: String): Pair<Int, Int>? {
        for (m in Regex("""export\s+default\s*""").findAll(masked)) {
            var i = m.range.last + 1
            while (i < masked.length && masked[i].isWhitespace()) i++
            if (i < masked.length && masked[i] == '{') {
                val close = matchBrace(masked, i)
                if (close != -1) return i to close
            }
        }
        return null
    }

    private sealed interface Value {
        data class Obj(val open: Int, val close: Int) : Value
        data class Ident(val name: String) : Value
        object Plain : Value
    }

    private fun valueForKey(masked: String, orig: String, open: Int, close: Int, key: String): Value? {
        for (span in objectEntries(masked, open, close)) {
            val (k, kind) = entryKeyAndKind(masked, orig, span.first, span.last + 1)
            if (k != key) continue
            if (kind is Kind.Obj) return Value.Obj(kind.open, kind.close)
            val seg = orig.substring(span.first, span.last + 1)
            val after = if (":" in seg) seg.substringAfter(":") else key  // shorthand → binding named `key` ?: ""
            val m = Regex("""^\s*([A-Za-z_$][\w$]*)\s*$""").find(after)
            return if (m != null) Value.Ident(m.groupValues[1]) else Value.Plain
        }
        return null
    }

    private fun absorbAdditionalDataObject(masked: String, orig: String, open: Int, close: Int, cat: Cat, ctx: String) {
        for (span in objectEntries(masked, open, close)) {
            val (key, kind) = entryKeyAndKind(masked, orig, span.first, span.last + 1)
            if (key == null) {
                if (kind is Kind.Spread || kind is Kind.Computed)
                    cat.diagnostics += "$ctx: unresolved ${if (kind is Kind.Spread) "spread" else "computed"} in additionalData (custom names may be incomplete)"
                continue
            }
            if (kind is Kind.Obj) {
                val members = memberNames(masked, orig, kind.open, kind.close, cat.diagnostics, "$ctx.$key", cat.signatures, "$key.")
                if (key == "flw") cat.flw += members
                else cat.namespaces.getOrPut(key) { HashSet() } += members
            } else {
                cat.topLevel += key
                memberSignature(masked, orig, span.first, span.last + 1)?.let { cat.signatures[key] = it }
            }
        }
    }

    /**
     * True when an object entry's VALUE is an object literal or a function (arrow / `function` /
     * method shorthand) — distinguishes a real `additionalData` registration (namespaces of
     * functions) from an incidental one such as a React `<Form additionalData={{…}}>` prop, whose
     * values are data/identifiers (`currentUser: props.currentUser`).
     */
    private fun entryValueIsFnOrObj(masked: String, orig: String, s: Int, e: Int): Boolean {
        val (key, kind) = entryKeyAndKind(masked, orig, s, e)
        if (key == null) return false
        if (kind is Kind.Obj) return true
        val seg = masked.substring(s, e)
        if ("=>" in seg || Regex("""\bfunction\b""").containsMatchIn(seg)) return true
        val lead = s + (seg.length - seg.trimStart().length)
        val m = IDENT_KEY.find(orig.substring(lead, e))
        if (m != null) {
            var j = lead + m.value.length
            while (j < e && masked[j].isWhitespace()) j++
            if (j < e && masked[j] == '(') return true  // method shorthand
        }
        return false
    }

    /** A plausible `additionalData` registration has ≥1 member whose value is an object or function.
     *  Guards the low-confidence `additionalData: { … }` property case (empty/data objects rejected). */
    private fun objectIsRegistrationShaped(masked: String, orig: String, open: Int, close: Int): Boolean {
        for (span in objectEntries(masked, open, close))
            if (entryValueIsFnOrObj(masked, orig, span.first, span.last + 1)) return true
        return false
    }

    /** (open, close) of a registration-shaped `name = { … }` assignment — resolves the minified-bundle
     *  shape where the config is a local var: `var …,a={flowkyc:{…}}` referenced by
     *  `return {…, additionalData:a}`. Skips incidental `name={…}` (e.g. a catch-block `a={error:e}`). */
    private fun resolveIdentObject(masked: String, orig: String, name: String): Pair<Int, Int>? {
        for (m in Regex("""\b${Regex.escape(name)}\s*=\s*""").findAll(masked)) {
            val j = m.range.last + 1
            if (j < masked.length && masked[j] == '{') {
                val close = matchBrace(masked, j)
                if (close != -1 && objectIsRegistrationShaped(masked, orig, j, close)) return j to close
            }
        }
        return null
    }

    /**
     * (open, close, highConfidence) of `additionalData` object literals the primary anchors miss —
     * chiefly the compiled Rollup bundle (`var additionalData = { … }`, from
     * `export default { …, additionalData }`) and nested config (`externals: { additionalData: {…} }`).
     * A leading-dot `.additionalData = {…}` is left to the primary pass. High confidence = a
     * `var/let/const additionalData =` declaration (never a JSX prop, which is always `additionalData:`).
     */
    private fun fallbackAdditionalDataSpans(masked: String): List<Triple<Int, Int, Boolean>> {
        val out = ArrayList<Triple<Int, Int, Boolean>>()
        for (m in Regex("""\badditionalData\b""").findAll(masked)) {
            val p = m.range.first
            var b = p - 1
            while (b >= 0 && masked[b].isWhitespace()) b--
            if (b >= 0 && masked[b] == '.') continue
            var j = m.range.last + 1
            while (j < masked.length && masked[j].isWhitespace()) j++
            if (j >= masked.length || (masked[j] != '=' && masked[j] != ':')) continue
            j++
            while (j < masked.length && masked[j].isWhitespace()) j++
            if (j >= masked.length || masked[j] != '{') continue
            val close = matchBrace(masked, j)
            if (close == -1) continue
            val high = Regex("""\b(?:var|let|const)\s+$""").containsMatchIn(masked.substring(0, p))
            out += Triple(j, close, high)
        }
        return out
    }

    // ---- real parameter names from a bundle's sourcemap (minification renames them to e,t,r) ----

    /** Split on commas not nested inside () [] {} <> (so `Record<string, number>` stays one param). */
    private fun splitTopLevelCommas(s: String): List<String> {
        val out = ArrayList<String>(); var depth = 0; var start = 0
        for (i in s.indices) {
            when (s[i]) {
                '(', '[', '{', '<' -> depth++
                ')', ']', '}', '>' -> if (depth > 0) depth--
                ',' -> if (depth == 0) { out += s.substring(start, i); start = i + 1 }
            }
        }
        out += s.substring(start)
        return out
    }

    /** Raw TS/JS param list → comma-separated bare names (drop types/defaults; keep `...` and `?`). */
    private fun cleanParamList(raw: String): String {
        val re = Regex("""^(\.\.\.)?\s*([A-Za-z_$][\w$]*)\s*(\??)""")
        return splitTopLevelCommas(raw).mapNotNull { seg ->
            val t = seg.trim()
            if (t.isEmpty()) null else re.find(t)?.let { it.groupValues[1] + it.groupValues[2] + it.groupValues[3] } ?: t
        }.joinToString(", ")
    }

    /** Record {funcName: cleaned params} for top-level function / const-arrow / const-function defs. */
    private fun scanSourceSignatures(src: String, sigs: MutableMap<String, String>) {
        val masked = mask(src)
        fun rec(name: String, openParen: Int) {
            if (name !in sigs) parenText(masked, src, openParen)?.let { sigs[name] = cleanParamList(it) }
        }
        for (m in Regex("""\bfunction\s+([A-Za-z_$][\w$]*)\s*\(""").findAll(masked)) rec(m.groupValues[1], m.range.last)
        for (m in Regex("""\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*""").findAll(masked)) {
            val name = m.groupValues[1]; val j = m.range.last + 1
            val fm = Regex("""^function\b\s*(?:[A-Za-z_$][\w$]*\s*)?""").find(masked.substring(j))
            when {
                fm != null && j + fm.value.length < masked.length && masked[j + fm.value.length] == '(' -> rec(name, j + fm.value.length)
                j < masked.length && masked[j] == '(' -> rec(name, j)
                else -> Regex("""^([A-Za-z_$][\w$]*)\s*=>""").find(masked.substring(j))?.let { if (name !in sigs) sigs[name] = it.groupValues[1] }
            }
        }
    }

    private fun findSourcemap(bundle: File, text: String): File? {
        var url: String? = null
        for (m in Regex("""sourceMappingURL=([^\s'"]+)""").findAll(text)) url = m.groupValues[1]
        if (url != null && !url!!.startsWith("data:")) {
            val cand = File(bundle.parentFile, url!!).normalize()
            if (cand.isFile) return cand
        }
        val sibling = File(bundle.path + ".map")
        return if (sibling.isFile) sibling else null
    }

    private fun signaturesFromSourceMap(bundle: File, text: String): Map<String, String> {
        val mp = findSourcemap(bundle, text) ?: return emptyMap()
        if (mp.length() > 20_000_000L) return emptyMap()
        val data = runCatching { MiniJson.parseOrNull(mp.readText()) }.getOrNull() as? Map<*, *> ?: return emptyMap()
        val contents = data["sourcesContent"] as? List<*> ?: return emptyMap()
        val sigs = HashMap<String, String>()
        for (c in contents) if (c is String && c.length < 2_000_000) scanSourceSignatures(c, sigs)
        return sigs
    }

    private fun applySourceSignatures(cat: Cat, smap: Map<String, String>) {
        if (smap.isEmpty()) return
        fun upd(display: String, name: String) { smap[name]?.let { cat.signatures[display] = it } }
        cat.namespaces.forEach { (ns, members) -> members.forEach { upd("$ns.$it", it) } }
        cat.flw.forEach { upd("flw.$it", it) }
        cat.topLevel.forEach { upd(it, it) }
    }

    private fun resolveImport(fromFile: File, binding: String, orig: String): File? {
        val b = Regex.escape(binding)
        val pats = listOf(
            Regex("""import\s+$b\s+from\s*['"]([^'"]+)['"]"""),
            Regex("""import\s*\{[^}]*\b$b\b[^}]*}\s*from\s*['"]([^'"]+)['"]"""),
        )
        for (p in pats) {
            val m = p.find(orig) ?: continue
            val spec = m.groupValues[1]
            if (!spec.startsWith(".")) return null
            val base = File(fromFile.parentFile, spec).normalize()
            val cands = SRC_EXT.map { File(base.path + it) } + SRC_EXT.map { File(base, "index$it") }
            return cands.firstOrNull { it.isFile }
        }
        return null
    }

    private fun readIfSmall(f: File): String? =
        if (f.isFile && f.length() <= MAX_SIZE) runCatching { f.readText() }.getOrNull() else null

    private fun relOf(f: File, root: File): String =
        if (root.isDirectory) f.relativeToOrSelf(root).path else f.name

    private fun absorbFromFile(path: File, cat: Cat, seen: MutableSet<String>, root: File): Boolean {
        if (!seen.add(path.path)) return false
        val orig = readIfSmall(path) ?: return false
        val masked = mask(orig)
        val rel = relOf(path, root)
        var handled = false
        // (1) `export default { … additionalData … }` — the externals config object.
        findExportDefaultObject(masked)?.let { (o, c) ->
            when (val v = valueForKey(masked, orig, o, c, "additionalData")) {
                is Value.Obj -> { absorbAdditionalDataObject(masked, orig, v.open, v.close, cat, rel); cat.sources += rel; handled = true }
                is Value.Ident -> {
                    val target = resolveImport(path, v.name, orig)
                    if (target != null && seen.add(target.path)) {
                        val tOrig = readIfSmall(target)
                        if (tOrig != null) {
                            val tMasked = mask(tOrig)
                            findExportDefaultObject(tMasked)?.let { (to, tc) ->
                                val tRel = relOf(target, root)
                                absorbAdditionalDataObject(tMasked, tOrig, to, tc, cat, tRel)
                                cat.sources += tRel
                                handled = true
                            }
                        }
                    }
                }
                else -> {}
            }
        }
        // (2) Direct assignment: `externals(.default)?.additionalData = { … }` (the dot guards JSX props).
        if (!handled) {
            for (m in Regex("""\.additionalData\s*=\s*""").findAll(masked)) {
                val j = m.range.last + 1
                if (j < masked.length && masked[j] == '{') {
                    val close = matchBrace(masked, j)
                    if (close != -1) { absorbAdditionalDataObject(masked, orig, j, close, cat, rel); cat.sources += rel; handled = true; break }
                }
            }
        }
        // (3) Fallback for compiled bundles / nested config: `var additionalData = { … }` (Rollup output
        //     of `export default { …, additionalData }`) or a bare `additionalData: { … }` property. The
        //     property case is guarded so a React `<Form additionalData={{…}}>` prop isn't mistaken for a
        //     registration. Absorbs every distinct span (a bundle may inline more than one).
        if (!handled) {
            val absorbed = HashSet<Int>()
            for ((o, c, high) in fallbackAdditionalDataSpans(masked)) {
                if (o in absorbed) continue
                if (!high && !objectIsRegistrationShaped(masked, orig, o, c)) continue
                absorbAdditionalDataObject(masked, orig, o, c, cat, rel)
                absorbed += o
                handled = true
            }
            // (3b) `additionalData: <identifier>` → resolve to its object assignment (minified bundle:
            //      `var …,a={flowkyc:{…}}` … `return {…, additionalData:a}`).
            if (!handled) {
                for (m in Regex("""\badditionalData\b\s*[:=]\s*([A-Za-z_$][\w$]*)""").findAll(masked)) {
                    val span = resolveIdentObject(masked, orig, m.groupValues[1]) ?: continue
                    absorbAdditionalDataObject(masked, orig, span.first, span.second, cat, rel)
                    handled = true
                    break
                }
            }
            if (handled) cat.sources += rel
        }
        // Recover real parameter names from a bundle's sourcemap (minification renamed them to e,t,r).
        if (handled) applySourceSignatures(cat, signaturesFromSourceMap(path, orig))
        return handled
    }

    private fun entryCandidates(root: File, explicit: File?): List<File> {
        if (explicit != null) {
            if (explicit.isFile) return listOf(explicit)
            if (explicit.isDirectory) {
                for (ext in SRC_EXT) {
                    val idx = File(explicit, "index$ext")
                    if (idx.isFile) return listOf(idx)
                }
                return walkCandidates(explicit)
            }
            return emptyList()
        }
        if (!root.isDirectory) return emptyList()
        return walkCandidates(root)
    }

    private fun walkCandidates(base: File): List<File> {
        val hits = ArrayList<File>()
        var count = 0
        base.walkTopDown()
            .onEnter { it.name !in SKIP_DIRS }
            .forEach { f ->
                if (!f.isFile || SRC_EXT.none { f.name.endsWith(it) }) return@forEach
                if (++count > MAX_FILES || f.length() > MAX_SIZE) return@forEach
                val head = runCatching { f.readText() }.getOrNull() ?: return@forEach
                if ("additionalData" in head && ("export default" in head || ".additionalData" in head ||
                        "externals" in head || Regex("""additionalData\s*[:=]\s*\{""").containsMatchIn(head)))
                    hits += f
            }
        return hits
    }
}
