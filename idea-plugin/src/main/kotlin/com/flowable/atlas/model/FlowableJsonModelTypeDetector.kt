package com.flowable.atlas.model

import com.intellij.json.JsonFileType
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.util.io.ByteSequence
import com.intellij.openapi.vfs.VirtualFile

/**
 * Opens a Flowable model whose extension also means something outside Flowable as JSON — but only when
 * the file holds a JSON object.
 *
 * A file-type mapping by extension is IDE-wide: mapping `.tpl`, `.service`, `.policy` or `.data` to JSON
 * by name turned a Helm chart's `_helpers.tpl`, a systemd unit or a Java security policy, in *any*
 * project, into a JSON file full of syntax errors that the JSON formatter would rewrite. Those extensions
 * are left unmapped in `plugin.xml`, so the platform asks the detectors, and this one claims the file
 * when its content starts like a model: optional BOM and whitespace, `{`, optional whitespace, then `"`
 * or `}`. A bare leading `{` is not enough — a Helm template opens with `{{`. The platform caches the
 * answer per file and asks again when the content changes.
 *
 * Every other model extension (`.form`, `.action`, …) has no common meaning elsewhere and stays mapped
 * by name, so it opens as JSON even while empty.
 */
class FlowableJsonModelTypeDetector : FileTypeRegistry.FileTypeDetector {

    override fun detect(file: VirtualFile, firstBytes: ByteSequence, firstCharsIfText: CharSequence?): FileType? {
        if (!isContentDetected(file.name)) return null
        return if (looksLikeJsonObject(firstBytes)) JsonFileType.INSTANCE else null
    }

    /** Enough for a pretty-printed model's leading whitespace; models never start with more. */
    override fun getDesiredContentPrefixLength(): Int = 256

    companion object {

        /** The model extensions mapped to JSON by name in `plugin.xml` — the unambiguous ones. */
        val NAME_MAPPED: Set<String> = setOf(
            ".form", ".action", ".dictionary", ".masterdata", ".knowledgebase",
            ".dashboardcomponent", ".extractor", ".palette", ".agent", ".channel",
        )

        /**
         * The JSON model extensions decided by content: every JSON one in [ModelType.EXTENSIONS] that is
         * not [NAME_MAPPED] — derived, so a model type added in `:core` cannot fall between the two.
         */
        val CONTENT_DETECTED: List<String> = ModelType.EXTENSIONS.map { it.first }
            .filterNot { ModelType.isXmlModel("x$it") || it in NAME_MAPPED }

        fun isContentDetected(fileName: String): Boolean {
            val name = fileName.lowercase()
            return CONTENT_DETECTED.any { name.endsWith(it) }
        }

        /** BOM and whitespace, `{`, whitespace, then `"` or `}` — the start of a JSON object. */
        fun looksLikeJsonObject(bytes: ByteSequence): Boolean {
            var i = 0
            val n = bytes.length()
            if (n >= 3 && bytes.byteAt(0) == 0xEF.toByte() && bytes.byteAt(1) == 0xBB.toByte() && bytes.byteAt(2) == 0xBF.toByte()) i = 3
            fun skipWhitespace() {
                while (i < n && bytes.byteAt(i).toInt().toChar() in " \t\r\n") i++
            }
            skipWhitespace()
            if (i >= n || bytes.byteAt(i) != '{'.code.toByte()) return false
            i++
            skipWhitespace()
            return i < n && (bytes.byteAt(i) == '"'.code.toByte() || bytes.byteAt(i) == '}'.code.toByte())
        }
    }
}
