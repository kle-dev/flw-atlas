package com.flowable.atlas.render

import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.model.ModelPaths
import com.flowable.atlas.parsing.FileWalk
import java.io.File

/**
 * The ER diagrams a project keeps beside its models — `*.atlas-erd.json`, exported from the designer and
 * committed — embedded into an explorer page that carries the designer ([ExplorerExtension.ERD]).
 *
 * This is how a diagram is shared: a browser keeps what one person draws only for that person, while a
 * file in the repository reaches everyone who generates the explorer, and the generated page opens on it
 * without an import. The page reads the file's content as a starting point; what someone changes there
 * stays in their browser until they export it again (explorer.md, "ER diagram designer").
 *
 * The files are passed through as parsed JSON, not validated here: the page's reader (`erdNormalize` in
 * `ext/erd.js`) is the one definition of what a diagram may contain, and it already has to read files from
 * other projects and older versions. A file that is not JSON travels as its error, so the page can say why
 * the diagram is missing instead of silently not listing it.
 */
internal object ErdDiagramFiles {

    const val SUFFIX = ".atlas-erd.json"

    /** A diagram is a few KB; anything this large is not one, and must not balloon the page. */
    const val MAX_BYTES = 1_000_000L

    /** More than this many is a generated or copied folder, not diagrams people drew. */
    const val MAX_FILES = 50

    /** `[{file, doc}]` or `[{file, error}]`, project-relative paths, in walk order; empty for a non-directory. */
    fun find(root: File): List<Map<String, Any?>> {
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<Map<String, Any?>>()
        // The model walk's own pruning: a diagram in build/ or node_modules/ is a copy, not the source.
        for (f in FileWalk.files(root) { dir -> dir == root || dir.name !in ModelPaths.EXCLUDE_DIRS }) {
            if (!f.name.lowercase().endsWith(SUFFIX)) continue
            val rel = f.relativeTo(root).invariantSeparatorsPath
            out.add(read(f, rel))
            if (out.size >= MAX_FILES) break
        }
        return out
    }

    private fun read(f: File, rel: String): Map<String, Any?> {
        if (f.length() > MAX_BYTES) return linkedMapOf("file" to rel, "error" to "larger than ${MAX_BYTES / 1000} KB — not embedded")
        val text = try { f.readText(Charsets.UTF_8) } catch (e: Exception) {
            return linkedMapOf("file" to rel, "error" to "could not be read: ${e.message ?: e.javaClass.simpleName}")
        }
        // Strict: a file cut off halfway is an error to show, not a diagram with half its tables.
        val doc = try { MiniJson.parse(text.removePrefix("\uFEFF")) } catch (e: Exception) {
            return linkedMapOf("file" to rel, "error" to "not valid JSON: ${e.message ?: e.javaClass.simpleName}")
        }
        return linkedMapOf("file" to rel, "doc" to doc)
    }
}
