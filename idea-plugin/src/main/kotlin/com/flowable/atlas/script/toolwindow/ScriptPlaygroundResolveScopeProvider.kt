package com.flowable.atlas.script.toolwindow

import com.flowable.atlas.script.completion.ScriptScope
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ResolveScopeProvider
import com.intellij.psi.search.GlobalSearchScope

/**
 * Gives the Script Playground's scratch file the whole project as resolve scope. The
 * LanguageTextField document is not backed by a module, so without this an
 * `import org.slf4j.LoggerFactory` never resolves and the class offers no completion — with it,
 * imports resolve against the project and its libraries and the language's own completion takes
 * over. Applies only to files the playground marked; every other file keeps its normal scope.
 */
class ScriptPlaygroundResolveScopeProvider : ResolveScopeProvider() {

    override fun getResolveScope(file: VirtualFile, project: Project): GlobalSearchScope? =
        if (file.getUserData(ScriptScope.PLAYGROUND_FILE) == true) GlobalSearchScope.allScope(project)
        else null
}
