package com.flowable.atlas.compare

import com.flowable.atlas.model.ModelType
import com.flowable.atlas.parsing.ModelJsonReader
import com.flowable.atlas.parsing.ModelMemberExtractor

/**
 * Which of a set of files is "the same model" as the one in hand, and in what order to offer them.
 *
 * The names never line up on their own. A form generated into the project folder is `DEMO-F001.json`;
 * the Design export calls it `form-models/DEMO-F001.json`, the deployment `.bar` calls it
 * `form-DEMO-F001.form`, and an LLM asked for "a form" may have named the file after nothing at all
 * while putting `"key": "DEMO-F001"` inside it. So the match is a ladder, and every rung that fires is
 * offered — the last word belongs to whoever is looking at the two files.
 *
 * Pure: names in, ranked candidates out, no VFS and no IntelliJ. [rank] is generic and its sort is
 * stable, so the caller's own order (archive name, then entry path) breaks ties inside a rung.
 */
internal object ModelArchiveMatch {

    /** How a candidate met the file in hand — and, by its ordinal, how strong that is. */
    enum class Rank { SAME_NAME, SAME_STEM, SAME_KEY }

    data class Ranked<T>(val value: T, val rank: Rank, val sameExtension: Boolean)

    /**
     * The candidates that match [fileName] (or, failing that, the model [modelKey] read out of the file
     * itself), strongest first. [nameOf] yields a *name*, never a path: `form-models/DEMO-F001.json`
     * would compare as `form-models/DEMO-F001`, and the `/` is not a segment boundary the prefix rule
     * below knows. Comparison is case-insensitive — one side is an archive entry, the other a file on a
     * file system that may or may not care.
     */
    fun <T> rank(
        fileName: String,
        modelKey: String?,
        candidates: List<T>,
        nameOf: (T) -> String,
    ): List<Ranked<T>> {
        val stem = stemOf(fileName)
        val extension = extensionOf(fileName)
        return candidates.mapNotNull { candidate ->
            val name = nameOf(candidate)
            val rank = rankOf(fileName, stem, modelKey, name) ?: return@mapNotNull null
            Ranked(candidate, rank, sameExtension = extensionOf(name) == extension)
        }.sortedWith(compareBy<Ranked<T>>({ it.rank.ordinal }, { !it.sameExtension }))
    }

    private fun rankOf(fileName: String, stem: String, modelKey: String?, candidateName: String): Rank? {
        val candidateStem = stemOf(candidateName)
        return when {
            candidateName.equals(fileName, ignoreCase = true) -> Rank.SAME_NAME
            stem.isNotEmpty() && stemsMeet(stem, candidateStem) -> Rank.SAME_STEM
            // A `.bar` prefixes its flat entries with the model kind, so the key is the tail of the stem.
            !modelKey.isNullOrEmpty() && stemsMeet(modelKey, candidateStem) -> Rank.SAME_KEY
            else -> null
        }
    }

    /** Equal, or one is the other behind a `<kind>-` prefix (`form-DEMO-F001` ↔ `DEMO-F001`). */
    private fun stemsMeet(a: String, b: String): Boolean =
        a.equals(b, ignoreCase = true) ||
            a.endsWith("-$b", ignoreCase = true) ||
            b.endsWith("-$a", ignoreCase = true)

    /**
     * The file name without its model extension — `ModelType.EXTENSIONS` (compound suffixes first, so
     * `.bpmn20.xml` beats `.bpmn`) plus the `.json` of a Design-workspace wrapper. A name carrying no
     * known model extension loses its last dot-segment instead, which is what makes this usable on the
     * arbitrary name a generator picked.
     */
    fun stemOf(name: String): String {
        val extension = extensionOf(name)
        return if (extension.isEmpty()) name else name.dropLast(extension.length)
    }

    /** The model extension [name] ends with (lowercase, leading dot), else its last dot-segment, else "". */
    fun extensionOf(name: String): String {
        val lower = name.lowercase()
        KNOWN_EXTENSIONS.firstOrNull { lower.endsWith(it) }?.let { return it }
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "" else lower.substring(dot)
    }

    /**
     * The model's own key, as the index reads it: the `id` of the top-level element for the XML kinds,
     * `key` or `metadata.key` for the JSON ones (a deployment `.form` only has the latter). Null for a
     * file that declares none — including a half-written one, which must not throw here.
     */
    fun keyOf(fileName: String, bytes: ByteArray): String? = runCatching {
        if (ModelType.isXmlModel(fileName)) {
            ModelType.byExtension(fileName)
                ?.let { ModelMemberExtractor.extract(fileName, bytes, it) }
                ?.firstOrNull()?.key
        } else {
            ModelJsonReader.extractKeyName(bytes)?.key
        }
    }.getOrNull()?.ifBlank { null }

    /** Longest first, so `.bpmn20.xml` is not read as the `.xml` of something else. */
    private val KNOWN_EXTENSIONS: List<String> =
        (ModelType.EXTENSIONS.map { it.first } + ".json").sortedByDescending { it.length }
}
