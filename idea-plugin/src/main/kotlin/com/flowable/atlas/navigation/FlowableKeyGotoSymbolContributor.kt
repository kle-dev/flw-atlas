package com.flowable.atlas.navigation

import com.flowable.atlas.icons.AtlasIcons
import com.flowable.atlas.index.FlowableIndex
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.usage.BotPsi
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

/**
 * Makes every Flowable model key — every **bot key**, and every named **element** inside a model (a
 * user task, a variable, a message) — findable in Search Everywhere / Go to Symbol. Model keys navigate
 * to their model file, an element to its declaration in the model; a bot key navigates to the Java
 * `BotService` class(es) that declare it **and** to the `.action` models that invoke it (both, by design).
 *
 * The bulk (model keys) comes from the already-built model index; bot keys additionally come from the
 * project's `BotService` implementors (found via PSI). The index is only read via `cachedOrNull()` —
 * if it hasn't been built yet a background build is kicked off so the next search is populated.
 */
class FlowableKeyGotoSymbolContributor : ChooseByNameContributorEx {

    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        val project = scope.project ?: return
        val service = project.service<FlowableModelIndexService>()
        val index = service.cachedOrRequest() ?: return
        // Model keys (actions, processes, cases, forms, agents, services, data objects, …).
        for (entry in index.allDistinct()) if (entry.key.isNotBlank()) processor.process(entry.key)
        // The elements inside them — a user task id, a variable, a message — searchable like the model.
        for (entry in index.allDistinct()) for (element in ModelElements.of(entry)) processor.process(element.id)
        // Bot keys referenced by actions (covers platform bots with no project class too).
        for (entry in index.keysOfType(ModelType.ACTION)) entry.members.botKey
            ?.takeIf { it.isNotBlank() }?.let { processor.process(it) }
        // Bot keys declared by Java BotService implementors.
        runReadActionBlocking { botClasses(project, scope).keys.forEach { processor.process(it) } }
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
        runReadActionBlocking {
            val psiManager = PsiManager.getInstance(project)
            val seenFiles = HashSet<String>()

            // A model key → its model file(s). One symbol per distinct file.
            for (entry in index.find(name)) {
                if (!seenFiles.add(entry.file.url)) continue
                val file = psiManager.findFile(entry.file) ?: continue
                processor.process(KeySymbol(name, entry.type.display, AtlasIcons.forType(entry.type), file))
            }

            // An element id → its model, at the element's declaration.
            for (element in elementsById(index)[name].orEmpty()) {
                val entry = element.owner
                val psiFile = psiManager.findFile(entry.file) ?: continue
                // a leaf token is not navigable by itself; a descriptor at the declaration's offset is
                val target: Navigatable = ModelElements.declarationOffset(psiFile.text, name)
                    ?.let { OpenFileDescriptor(project, entry.file, it) } ?: psiFile
                processor.process(KeySymbol(name, element.location, element.kind.icon, target))
            }

            // A bot key → the Java BotService class(es) that declare it.
            for (cls in botClasses(project, scope)[name].orEmpty()) {
                processor.process(KeySymbol(name, "Bot · " + (cls.name ?: ""), AtlasIcons.Bot, cls))
            }

            // A bot key → the .action models that invoke it (searching the bot finds its callers).
            for (entry in index.actionsUsingBot(name)) {
                val file = psiManager.findFile(entry.file) ?: continue
                processor.process(KeySymbol(name, "Action · uses bot", AtlasIcons.forType(ModelType.ACTION), file))
            }
        }
    }

    /**
     * Every element of every model, by id — once per index snapshot. Go to Symbol asks for each name that
     * matched the typed prefix, and each ask used to walk every element of every model again.
     */
    private fun elementsById(index: FlowableIndex): Map<String, List<ModelElements.Element>> {
        elementsMemo?.let { (of, byId) -> if (of === index) return byId }
        val byId = index.allDistinct().flatMap { ModelElements.of(it) }.groupBy { it.id }
        elementsMemo = index to byId
        return byId
    }

    @Volatile private var elementsMemo: Pair<FlowableIndex, Map<String, List<ModelElements.Element>>>? = null

    /**
     * botKey → the project's `BotService` implementors declaring it, kept until PSI changes. An
     * inheritor search ran on every keystroke of Go to Symbol and again for every matched name.
     * Call inside a read action.
     */
    private fun botClasses(project: Project, scope: GlobalSearchScope): Map<String, List<PsiClass>> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(ConcurrentHashMap<GlobalSearchScope, Map<String, List<PsiClass>>>(), PsiModificationTracker.MODIFICATION_COUNT)
        }.getOrPut(scope) { findBotClasses(project, scope) }

    private fun findBotClasses(project: Project, scope: GlobalSearchScope): Map<String, List<PsiClass>> {
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
        private val target: Navigatable,
    ) : NavigationItem, ItemPresentation {
        override fun getName(): String = symbolName
        override fun getPresentation(): ItemPresentation = this
        override fun navigate(requestFocus: Boolean) { target.navigate(requestFocus) }
        override fun canNavigate(): Boolean = target.canNavigate()
        override fun canNavigateToSource(): Boolean = target.canNavigateToSource()
        override fun getPresentableText(): String = symbolName
        override fun getLocationString(): String = location
        override fun getIcon(unused: Boolean): Icon? = icon
    }

}
