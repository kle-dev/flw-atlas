package com.flowable.atlas.script.toolwindow

import com.flowable.atlas.script.completion.ScriptScope
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.LanguageTextField

/** A marked playground scratch file resolves imports against the whole project. */
class ScriptPlaygroundResolveScopeTest : BasePlatformTestCase() {

    fun testMarkedPlaygroundFileGetsTheWholeProjectScope() {
        val document = LanguageTextField.createDocument(
            "import java.util.ArrayList\nnew ArrayList()",
            PlaygroundScriptLanguage.GROOVY.ideLanguage(), project,
            LanguageTextField.SimpleDocumentCreator(),
        )
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)!!
        psiFile.virtualFile?.putUserData(ScriptScope.PLAYGROUND_FILE, true)
        // the everything-scope is the provider's whole job; actual class resolution needs a real
        // JDK/library setup (none in this fixture) and is covered by the manual runIde checklist
        assertEquals(GlobalSearchScope.allScope(project), psiFile.resolveScope)
    }
}
