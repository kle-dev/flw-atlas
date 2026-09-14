package com.flowable.atlas.usage

import com.flowable.atlas.completion.FlowableInfixMatcher
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.index.ModelEntry
import com.flowable.atlas.index.ProjectModelScope
import com.flowable.atlas.model.ModelKeyDeclaration
import com.flowable.atlas.navigation.ModelElements
import com.flowable.atlas.navigation.se.ArchivePaths
import com.flowable.atlas.navigation.se.FlowableModelTextScanner
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.usageView.UsageInfo
import com.intellij.usages.FindUsagesProcessPresentation
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageSearcher
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation
import com.intellij.util.Processor

/**
 * The same three kinds of hit the **Flowable Model** Search Everywhere tab shows — a model by key or
 * archive-qualified path, a named element by id, every occurrence in a model's text — as a result list
 * that stays open.
 *
 * ## Why this exists beside the Search Everywhere tab
 * Search Everywhere closes on the first result you open. A search that matched thirty places could only
 * be walked one query at a time, which is the opposite of what a search over a whole model repository is
 * for. The platform's own answer is *Open in Find Tool Window*, gated behind
 * [com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor.showInFindResults] — but that
 * flag only *enables the button*. The action fills the window from three item shapes only
 * (`UsageInfo2UsageAdapter`, the platform's own `SearchEverywhereItem`, and whatever it can convert to
 * PSI); our rows are none of them, so flipping the flag would light a button that opens an empty window.
 * Making our rows PSI-shaped would mean resolving PSI for hundreds of cached items on the EDT, and would
 * opt the tab into a contributor API the platform is superseding. So the list is built here instead, on
 * `UsageViewManager` — public, un-deprecated, and already how [FlowableModelUsageSearcher] reports
 * models as usages of a Java symbol.
 *
 * ## What a "hit" is
 * A `UsageInfo` needs an **offset**, and offsets are where the two halves of this could disagree: the
 * text scanner decodes UTF-8 while the `Document` uses the file's detected charset (which is why the
 * Search Everywhere tab navigates by line and column instead). So the scanner is used only to decide
 * *which files contain the pattern*; every offset is found again in `psiFile.text`, under a short read
 * action, and the drift cannot happen.
 */
internal object ModelSearchUsages {

    /**
     * Far above the popup's limits (200 models / 200 elements / 300 hits). Those exist because a popup
     * row costs a keystroke's worth of latency; this list is built once, in the background, and a cap
     * that truncates it would defeat the point. It is still a cap: a one-character pattern over a large
     * repository is not a question anyone means to ask.
     */
    private const val FIND_LIMIT = 10_000

    /** Below this a pattern matches most of every model's text. Same floor as the tab's grep. */
    const val MIN_PATTERN_LENGTH: Int = 2

    /** Opens the Find tool window and streams the hits into it as they are found. */
    fun show(project: Project, pattern: String) {
        val presentation = UsageViewPresentation().apply {
            searchString = pattern
            tabText = "Flowable models: '$pattern'"
            tabName = tabText
            toolwindowTitle = tabText
            codeUsagesString = "Occurrences in Flowable models"
            // The Hub's chosen sub-project is the scope of the index, the tab and this list alike, so
            // the window says which scope it searched rather than implying the whole repository.
            scopeText = ProjectModelScope.label(project) ?: "the whole project"
            isOpenInNewTab = true
        }
        val targets = arrayOf<UsageTarget>(PatternTarget(project, pattern))
        UsageViewManager.getInstance(project).searchAndShowUsages(
            targets,
            {
                UsageSearcher { processor ->
                    collect(project, pattern, ProgressManager.getGlobalProgressIndicator()
                        ?: EmptyProgressIndicator(), processor)
                }
            },
            FindUsagesProcessPresentation(presentation).apply {
                // A single hit would otherwise open its file and show no list at all. This exists
                // because the popup already does that, and does it better: what is asked for here is
                // the list, whether it holds one row or three hundred.
                isShowPanelIfOnlyOneUsage = true
            },
            presentation,
            null,
        )
    }

    /**
     * Every hit for [pattern], to [processor], until it says stop or [indicator] is cancelled. No UI:
     * this is the half the tests drive.
     *
     * Call off the EDT and **outside** a read action — the index build and the text scan each take their
     * own short read actions, and wrapping the whole walk in one would queue every write action in the
     * IDE behind this search.
     */
    fun collect(project: Project, pattern: String, indicator: ProgressIndicator, processor: Processor<in Usage>) {
        if (pattern.length < MIN_PATTERN_LENGTH || project.isDisposed) return
        val matcher = FlowableInfixMatcher(pattern)
        // index(), not cachedOrRequest(): a background task can afford to wait for a cold index, and its
        // scan is what mounts an archive the text scanner would otherwise have to skip.
        val index = project.service<FlowableModelIndexService>().index()
        val psiManager = PsiManager.getInstance(project)
        var emitted = 0
        // Where a model or element hit already stands, so the text pass does not report the key's own
        // declaration a second time.
        val reported = HashMap<String, MutableSet<Int>>()

        fun emit(usage: Usage): Boolean = processor.process(usage) && ++emitted < FIND_LIMIT

        fun report(file: com.intellij.openapi.vfs.VirtualFile, offset: Int) {
            reported.getOrPut(file.url) { HashSet() }.add(offset)
        }

        for (entry in index.allEntries()) {
            indicator.checkCanceled()
            if (!entry.file.isValid) continue
            if (!matcher.prefixMatches(entry.key) && !matcher.prefixMatches(ArchivePaths.displayPath(entry.file))) continue
            val usage = inReadAction(project) { keyUsage(psiManager, entry) } ?: continue
            report(entry.file, usage.navigationOffset)
            if (!emit(UsageInfo2UsageAdapter(usage))) return
        }

        for (entry in index.allDistinct()) {
            indicator.checkCanceled()
            if (!entry.file.isValid) continue
            for (element in ModelElements.of(entry)) {
                if (!matcher.prefixMatches(element.id)) continue
                val usage = inReadAction(project) { elementUsage(psiManager, entry, element.id) } ?: continue
                report(entry.file, usage.navigationOffset)
                if (!emit(UsageInfo2UsageAdapter(usage))) return
            }
        }

        project.service<FlowableModelTextScanner>().forEachText(indicator) { file, scanned ->
            if (!scanned.contains(pattern, ignoreCase = true)) return@forEachText true
            val already = reported[file.url].orEmpty()
            val usages = inReadAction(project) {
                val psiFile = psiManager.findFile(file) ?: return@inReadAction emptyList()
                textUsages(psiFile, pattern, already)
            }.orEmpty()
            for (usage in usages) {
                indicator.checkCanceled()
                if (!emit(UsageInfo2UsageAdapter(usage))) return@forEachText false
            }
            true
        }
    }

    /** A model's hit sits on its key declaration — `<process id="…">`, `"key": "…"` — else on the file. */
    private fun keyUsage(psiManager: PsiManager, entry: ModelEntry): UsageInfo? {
        val psiFile = psiManager.findFile(entry.file) ?: return null
        val offset = ModelKeyDeclaration.offsetOf(psiFile.text, entry.type, entry.key) ?: return UsageInfo(psiFile)
        return UsageInfo(psiFile, offset, offset + entry.key.length, false)
    }

    /** An element's hit sits on its declared id, the same place the tab's row navigates to. */
    private fun elementUsage(psiManager: PsiManager, entry: ModelEntry, id: String): UsageInfo? {
        val psiFile = psiManager.findFile(entry.file) ?: return null
        val offset = ModelElements.declarationOffset(psiFile.text, id) ?: return null
        return UsageInfo(psiFile, offset, offset + id.length, false)
    }

    /**
     * Every occurrence of [pattern] in [psiFile], except the offsets [already] reported for this file.
     *
     * Visible for the test: an archive entry cannot be reached through [collect] there, because the
     * light fixture's project lives in memory and `jar://` can only mount a file that is really on disk.
     * This is the part that matters for such an entry anyway — that its `PsiFile` yields ranges over the
     * packed text — so the test drives it directly.
     */
    internal fun textUsages(psiFile: PsiFile, pattern: String, already: Set<Int>): List<UsageInfo> {
        val text = psiFile.text
        val out = ArrayList<UsageInfo>()
        var at = text.indexOf(pattern, 0, ignoreCase = true)
        while (at >= 0) {
            if (at !in already) out.add(UsageInfo(psiFile, at, at + pattern.length, false))
            at = text.indexOf(pattern, at + pattern.length, ignoreCase = true)
        }
        return out
    }

    /** One short read action per file — never one around the whole walk. */
    private fun <T> inReadAction(project: Project, body: () -> T?): T? =
        ReadAction.compute<T?, RuntimeException> { if (project.isDisposed) null else body() }

    /**
     * What the Find window reruns when you press its refresh button, and the caption over the results.
     * Deliberately not the platform's own `StringUsageTarget` — that lives in an `impl` package.
     */
    private class PatternTarget(private val project: Project, private val pattern: String) : UsageTarget {
        override fun findUsages() = show(project, pattern)
        override fun isValid(): Boolean = !project.isDisposed
        override fun getName(): String = pattern
        override fun getPresentation(): com.intellij.navigation.ItemPresentation? = null
        override fun navigate(requestFocus: Boolean) = Unit
        override fun canNavigate(): Boolean = false
        override fun canNavigateToSource(): Boolean = false
    }
}
