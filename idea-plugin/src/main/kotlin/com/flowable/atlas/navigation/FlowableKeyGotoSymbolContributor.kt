package com.flowable.atlas.navigation

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.usage.BotPsi
import com.intellij.icons.AllIcons
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import javax.swing.Icon

/**
 * Makes every Flowable model key — and every **bot key** — findable in Search Everywhere / Go to
 * Symbol. Model keys navigate to their model file; a bot key navigates to the Java `BotService`
 * class(es) that declare it **and** to the `.action` models that invoke it (both, by design).
 *
 * The bulk (model keys) comes from the already-built model index; bot keys additionally come from the
 * project's `BotService` implementors (found via PSI). The index is only read via `cachedOrNull()` —
 * if it hasn't been built yet a background build is kicked off so the next search is populated.
 */
class FlowableKeyGotoSymbolContributor : ChooseByNameContributorEx {

    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        val project = scope.project ?: return
        val service = project.service<FlowableModelIndexService>()
        val index = service.cachedOrNull() ?: run {
            ApplicationManager.getApplication().executeOnPooledThread { runCatching { service.index() } }
            return
        }
        // Model keys (actions, processes, cases, forms, agents, services, data objects, …).
        for (entry in index.allDistinct()) if (entry.key.isNotBlank()) processor.process(entry.key)
        // Bot keys referenced by actions (covers platform bots with no project class too).
        for (entry in index.keysOfType(ModelType.ACTION)) entry.members.botKey
            ?.takeIf { it.isNotBlank() }?.let { processor.process(it) }
        // Bot keys declared by Java BotService implementors.
        runReadAction { botClasses(project, scope).keys.forEach { processor.process(it) } }
    }

    override fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem>,
        parameters: FindSymbolParameters,
    ) {
        val scope = parameters.searchScope
        val project = scope.project ?: return
        val service = project.service<FlowableModelIndexService>()
        val index = service.cachedOrNull() ?: return
        runReadAction {
            val psiManager = PsiManager.getInstance(project)
            val seenFiles = HashSet<String>()

            // A model key → its model file(s). One symbol per distinct file.
            for (entry in index.find(name)) {
                if (!seenFiles.add(entry.file.url)) continue
                val file = psiManager.findFile(entry.file) ?: continue
                processor.process(KeySymbol(name, entry.type.display, KEY_ICON, file))
            }

            // A bot key → the Java BotService class(es) that declare it.
            for (cls in botClasses(project, scope)[name].orEmpty()) {
                processor.process(KeySymbol(name, "Bot · " + (cls.name ?: ""), AllIcons.Nodes.Class, cls))
            }

            // A bot key → the .action models that invoke it (searching the bot finds its callers).
            for (entry in index.actionsUsingBot(name)) {
                val file = psiManager.findFile(entry.file) ?: continue
                processor.process(KeySymbol(name, "Action · uses bot", KEY_ICON, file))
            }
        }
    }

    /** botKey → the project's `BotService` implementors declaring it. Call inside a read action. */
    private fun botClasses(project: Project, scope: GlobalSearchScope): Map<String, List<PsiClass>> {
        val cache = PsiShortNamesCache.getInstance(project) ?: return emptyMap()
        val result = LinkedHashMap<String, MutableList<PsiClass>>()
        for (iface in cache.getClassesByName("BotService", GlobalSearchScope.allScope(project))) {
            if (!iface.isInterface) continue
            for (impl in ClassInheritorsSearch.search(iface, scope, true).findAll()) {
                val key = BotPsi.botKeyOf(impl) ?: continue
                result.getOrPut(key) { mutableListOf() }.add(impl)
            }
        }
        return result
    }

    /** A search result whose displayed/matched name is the model or bot **key**, delegating navigation. */
    private class KeySymbol(
        private val symbolName: String,
        private val location: String,
        private val icon: Icon?,
        private val target: PsiElement,
    ) : NavigationItem, ItemPresentation {
        override fun getName(): String = symbolName
        override fun getPresentation(): ItemPresentation = this
        override fun navigate(requestFocus: Boolean) { (target as? Navigatable)?.navigate(requestFocus) }
        override fun canNavigate(): Boolean = (target as? Navigatable)?.canNavigate() ?: false
        override fun canNavigateToSource(): Boolean = (target as? Navigatable)?.canNavigateToSource() ?: false
        override fun getPresentableText(): String = symbolName
        override fun getLocationString(): String = location
        override fun getIcon(unused: Boolean): Icon? = icon
    }

    private companion object {
        val KEY_ICON: Icon = IconLoader.getIcon("/META-INF/atlas-hub.svg", FlowableKeyGotoSymbolContributor::class.java)
    }
}
