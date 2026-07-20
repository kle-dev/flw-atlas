package com.flowable.atlas.usage

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Recognises a Flowable **bot** in Java PSI — the live counterpart to the `:core` text heuristic in
 * `JavaParser` (a class is a bot iff it implements an interface named `BotService` / `*Bot` /
 * `*BotService`; its key is the `getKey()` string-literal return). Deliberately kept in sync with that
 * heuristic so the IDE features and the generated Atlas explorer agree on what a bot is.
 *
 * Interface detection reads the written `implements` reference names (not resolved [PsiClass]es) so it
 * still works when the Flowable platform library is not on the module classpath — matching how the
 * text parser sees them.
 */
object BotPsi {

    /** True when [cls] implements a bot-service interface (by simple name, as `JavaParser` does). */
    fun isBot(cls: PsiClass): Boolean {
        val refs = cls.implementsList?.referenceElements ?: return false
        return refs.any { it.referenceName.isBotServiceInterfaceName() }
    }

    /** The bot key ([cls]'s `getKey()` string-literal return), or null when [cls] is not a bot / has none. */
    fun botKeyOf(cls: PsiClass): String? {
        if (!isBot(cls)) return null
        // Skip abstract declarations (e.g. the interface's getKey()); take the first implemented body
        // whose return is a string literal.
        return cls.findMethodsByName("getKey", true)
            .filter { it.parameterList.parametersCount == 0 }
            .firstNotNullOfOrNull { method ->
                val body = method.body ?: return@firstNotNullOfOrNull null
                PsiTreeUtil.findChildrenOfType(body, PsiReturnStatement::class.java)
                    .firstNotNullOfOrNull { (it.returnValue as? PsiLiteralExpression)?.value as? String }
            }
    }

    /** The enclosing bot class of [element] (e.g. the caret), or null when it is not inside a bot. */
    fun enclosingBotClass(element: PsiElement): PsiClass? =
        PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)?.takeIf { isBot(it) }

    private fun String?.isBotServiceInterfaceName(): Boolean {
        val n = this ?: return false
        return n == "BotService" || n.endsWith("Bot") || n.endsWith("BotService")
    }
}
