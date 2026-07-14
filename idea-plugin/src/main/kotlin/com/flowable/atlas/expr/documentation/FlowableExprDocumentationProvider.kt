package com.flowable.atlas.expr.documentation

import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.catalog.ExprFunction
import com.flowable.atlas.expr.catalog.FlowableExpressionCatalog
import com.flowable.atlas.expr.lang.FlowableExprFile
import com.flowable.atlas.expr.lang.dialectOf
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement

/**
 * Ctrl+Q / hover docs for known catalog functions and root objects inside expression fragments
 * (injected and in the playground): call head + the catalog's one-line description + aliases.
 * The catalog stores no parameter signatures, so this deliberately stops at the call head.
 *
 * The expression PSI is a single leaf, so the interesting token is located by scanning the fragment
 * text around the hover offset; the result is carried to [generateDoc] in a [FakePsiElement]
 * (same registration style as `FlowableKeyDocumentationProvider`, which is known to work with
 * injected PSI).
 */
class FlowableExprDocumentationProvider : AbstractDocumentationProvider() {

    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int,
    ): PsiElement? {
        if (file !is FlowableExprFile) return null
        val dialect = dialectOf(file.language) ?: return null
        val html = docAt(file.text, targetOffset, dialect) ?: return null
        val name = wordRangeAt(file.text, targetOffset)?.let { file.text.substring(it.first, it.second) } ?: "expression"
        return ExprDocElement(file, html, name)
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? =
        (element as? ExprDocElement)?.html

    private class ExprDocElement(
        private val file: PsiFile,
        val html: String,
        private val name: String,
    ) : FakePsiElement() {
        override fun getParent(): PsiElement = file

        // The 2026.1 documentation backend wraps this element in a PsiElementDocumentationTarget and
        // calls targetPresentation(), which needs a non-null presentable text (via getPresentableText()
        // → getName()). Without this the platform throws "… cannot be presented".
        override fun getName(): String = name
    }

    // ---- catalog lookup ------------------------------------------------------------------------

    private fun docAt(text: String, offset: Int, dialect: ExpressionDialect): String? {
        val range = wordRangeAt(text, offset) ?: return null
        val word = text.substring(range.first, range.second)

        if (dialect == ExpressionDialect.BACKEND) {
            // caret on the name of `prefix:name`
            prevWord(text, range.first, ':')?.let { prefix ->
                FlowableExpressionCatalog.backendFunctionsForPrefix(prefix)
                    .firstOrNull { word in it.allNames }
                    ?.let { return renderFunction(it, dialect) }
            }
            // caret on the prefix of `prefix:name`
            if (nextChar(text, range.second) == ':' && FlowableExpressionCatalog.resolvePrefix(word) != null) {
                val canonical = FlowableExpressionCatalog.resolvePrefix(word)!!
                return render(
                    definition = "$canonical:…",
                    content = "Flowable backend function namespace" +
                        (if (canonical != word) " (alias of '$canonical')" else "") +
                        " — ${FlowableExpressionCatalog.backendFunctionsForPrefix(canonical).size} functions.",
                )
            }
            FlowableExpressionCatalog.backendNoPrefixFunctions()
                .firstOrNull { word in it.allNames }
                ?.let { return renderFunction(it, dialect) }
        }

        if (dialect == ExpressionDialect.FRONTEND) {
            // `flw.member` or `flw.parent.sub` — caret on the member
            prevWord(text, range.first, '.')?.let { receiver ->
                if (receiver == FlowableExpressionCatalog.FRONTEND_NS) {
                    FlowableExpressionCatalog.frontendMembers()
                        .firstOrNull { word in it.allNames }
                        ?.let { return renderFunction(it, dialect) }
                } else if (receiver in FlowableExpressionCatalog.frontendNestingMembers) {
                    FlowableExpressionCatalog.frontendSubMembers(receiver)
                        .firstOrNull { word in it.allNames }
                        ?.let { return renderFunction(it, dialect, qualifier = "${FlowableExpressionCatalog.FRONTEND_NS}.$receiver.") }
                }
            }
        }

        return FlowableExpressionCatalog.roots(dialect)
            .firstOrNull { it.name == word }
            ?.let { render(it.name, it.doc ?: "Implicit expression root object.") }
    }

    private fun renderFunction(f: ExprFunction, dialect: ExpressionDialect, qualifier: String? = null): String =
        render(
            definition = (qualifier?.let { it + f.name } ?: f.label(dialect)) + "(…)",
            content = f.doc?.replaceFirstChar { it.uppercase() } ?: "Flowable expression function.",
            aliases = f.aliases,
        )

    private fun render(definition: String, content: String, aliases: List<String> = emptyList()): String = buildString {
        append(DocumentationMarkup.DEFINITION_START)
        append("<code>").append(StringUtil.escapeXmlEntities(definition)).append("</code>")
        append(DocumentationMarkup.DEFINITION_END)
        append(DocumentationMarkup.CONTENT_START)
        append(StringUtil.escapeXmlEntities(content))
        append(DocumentationMarkup.CONTENT_END)
        if (aliases.isNotEmpty()) {
            append(DocumentationMarkup.SECTIONS_START)
            append(DocumentationMarkup.SECTION_HEADER_START).append("Aliases:")
            append(DocumentationMarkup.SECTION_SEPARATOR)
            append(StringUtil.escapeXmlEntities(aliases.joinToString(", ")))
            append(DocumentationMarkup.SECTION_END)
            append(DocumentationMarkup.SECTIONS_END)
        }
    }

    // ---- text scanning ---------------------------------------------------------------------------

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    /** [start, end) of the identifier word at [offset], or null when not on a word. */
    private fun wordRangeAt(text: String, offset: Int): Pair<Int, Int>? {
        if (text.isEmpty()) return null
        var at = offset.coerceIn(0, text.length)
        if (at == text.length || !isWordChar(text[at])) at--   // allow hovering just behind the word
        if (at < 0 || !isWordChar(text[at])) return null
        var start = at
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = at + 1
        while (end < text.length && isWordChar(text[end])) end++
        return start to end
    }

    /** The word directly before [start], separated by exactly [separator] (whitespace allowed around it). */
    private fun prevWord(text: String, start: Int, separator: Char): String? {
        var i = start - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 0 || text[i] != separator) return null
        i--
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 0 || !isWordChar(text[i])) return null
        val end = i + 1
        var wordStart = i
        while (wordStart > 0 && isWordChar(text[wordStart - 1])) wordStart--
        return text.substring(wordStart, end)
    }

    private fun nextChar(text: String, from: Int): Char? {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return if (i < text.length) text[i] else null
    }
}
