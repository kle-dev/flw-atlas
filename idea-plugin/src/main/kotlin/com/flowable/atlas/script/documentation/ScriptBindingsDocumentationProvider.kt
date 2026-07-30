package com.flowable.atlas.script.documentation

import com.flowable.atlas.script.ScriptBindingsCatalog
import com.flowable.atlas.script.ScriptContext
import com.flowable.atlas.script.ScriptRoot
import com.flowable.atlas.script.completion.ScriptScope
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Quick documentation (hover / Ctrl-Q) for the Flowable script bindings: `execution`,
 * `flw.time.now`, … are dynamic engine bindings the language PSI cannot resolve, so the platform
 * falls back to "No candidates found for method call …" — this provider answers from the bindings
 * catalog instead, inside Flowable script bodies only. When the reference actually resolves (a
 * local `execution` variable, a real method) the language's own documentation wins.
 */
class ScriptBindingsDocumentationProvider : AbstractDocumentationProvider() {

    /** Claims the identifier leaf as the doc target when the catalog has something to say —
     *  without this, an unresolved reference has no target and the platform shows the fallback. */
    override fun getCustomDocumentationElement(
        editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int,
    ): PsiElement? = contextElement?.takeIf { docFor(it) != null }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? =
        originalElement?.let { docFor(it) } ?: element?.let { docFor(it) }

    private fun docFor(leaf: PsiElement): String? {
        val name = leaf.text
        if (!IDENT.matches(name)) return null
        val file = leaf.containingFile ?: return null
        val context = ScriptScope.contextOf(file) ?: return null
        // something the language actually resolves (a local variable, a real method) documents itself
        if (file.findReferenceAt(leaf.textRange.startOffset)?.resolve() != null) return null
        val roots = ScriptBindingsCatalog.rootsFor(context)
        val (receiver, outer) = receiversAt(file.text, leaf.textRange.startOffset)
        return when {
            receiver == null -> roots[name]?.let { rootHtml(it, context) }
            outer == null -> {
                val root = roots[receiver] ?: return null
                root.subObjects[name]?.let { subHtml(receiver, it) }
                    ?: root.members?.get(name)?.let { memberHtml(root, name, it) }
            }
            else -> {
                val sub = roots[outer]?.subObjects?.get(receiver) ?: return null
                sub.members?.get(name)?.let { memberHtml(sub, name, it, qualifier = "$outer.$receiver") }
            }
        }
    }

    private fun rootHtml(root: ScriptRoot, context: ScriptContext): String = buildString {
        append("<b>").append(root.name).append("</b> — ").append(root.doc)
        append("<p>Binding in a <i>").append(context.display).append("</i> script.")
        if (root.members == null && root.subObjects.isEmpty()) {
            append(" Its API is not catalogued — see the Flowable engine javadoc.")
        }
        append("</p><p>Process/case variables and Spring beans also resolve by their bare name.</p>")
    }

    private fun subHtml(rootName: String, sub: ScriptRoot): String =
        "<b>$rootName.${sub.name}</b> — ${sub.doc}"

    private fun memberHtml(obj: ScriptRoot, name: String, sig: String, qualifier: String = obj.name): String =
        "<b>$name($sig)</b><p>Member of <b>$qualifier</b> — ${obj.doc}</p>"

    private fun receiversAt(text: String, offset: Int): Pair<String?, String?> {
        var i = skipWs(text, minOf(offset, text.length))
        if (i == 0 || text[i - 1] != '.') return null to null
        i--
        if (i > 0 && text[i - 1] == '?') i--
        i = skipWs(text, i)
        val recStart = scanIdentLeft(text, i)
        if (recStart == i) return null to null
        val receiver = text.substring(recStart, i)
        var j = skipWs(text, recStart)
        if (j > 0 && text[j - 1] == '.') {
            j--
            if (j > 0 && text[j - 1] == '?') j--
            j = skipWs(text, j)
            val outerStart = scanIdentLeft(text, j)
            if (outerStart < j) return receiver to text.substring(outerStart, j)
        }
        return receiver to null
    }

    private fun scanIdentLeft(text: String, end: Int): Int {
        var s = end
        while (s > 0 && (text[s - 1].isLetterOrDigit() || text[s - 1] == '_')) s--
        return s
    }

    private fun skipWs(text: String, end: Int): Int {
        var s = end
        while (s > 0 && (text[s - 1] == ' ' || text[s - 1] == '\t')) s--
        return s
    }

    private companion object {
        val IDENT = Regex("[A-Za-z_]\\w*")
    }
}
