package com.flowable.atlas.navigation.se

import com.flowable.atlas.completion.FlowableInfixMatcher
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.ide.actions.searcheverywhere.FoundItemDescriptor
import com.intellij.ide.actions.searcheverywhere.PossibleSlowContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
import com.intellij.ide.actions.searcheverywhere.WeightedSearchEverywhereContributor
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.util.Processor
import com.intellij.util.text.Matcher
import com.intellij.util.text.matching.MatchingMode
import javax.swing.ListCellRenderer

/**
 * A Search Everywhere tab of its own — **Flowable Model** — that searches only Flowable models,
 * including the ones packed inside a `.bar`/`.zip`. Those archive entries are invisible to the
 * platform's Files tab and to Find in Files (they live in no content or library root), which is
 * exactly the gap this closes.
 *
 * Two kinds of result, in one flat weight-sorted list (Search Everywhere has no section headers):
 *  * **models** — matched on the model key *and* on the archive-qualified file path, from the
 *    already-built index, so this half is instant;
 *  * **full-text hits** — a live grep over model content, every occurrence its own row.
 *
 * The grep only runs while this tab is the selected one. The contributor is also part of the "All"
 * tab (the platform offers no opt-out), and greping every archive on each keystroke there would be
 * both slow and exactly the mixing this tab exists to avoid.
 *
 * Model keys stay searchable in the platform's Symbols tab too — that is
 * [com.flowable.atlas.navigation.FlowableKeyGotoSymbolContributor], which is unaffected by this.
 */
class FlowableModelSeContributor(private val project: Project) :
    WeightedSearchEverywhereContributor<FlowableSeItem>,
    PossibleSlowContributor,
    DumbAware {

    /**
     * The live pattern as a highlight matcher for the renderer. [FlowableInfixMatcher] decides what
     * *matches*, but it is a `PrefixMatcher` and so cannot report match ranges; the `*` prefix makes
     * this one infix too, so what is highlighted is what was matched.
     */
    @Volatile
    private var highlightMatcher: Matcher? = null

    override fun getSearchProviderId(): String = ID

    override fun getGroupName(): String = "Flowable Model"

    override fun getSortWeight(): Int = SORT_WEIGHT

    /** Without this the contributor would only ever feed the "All" tab — no tab of its own. */
    override fun isShownInSeparateTab(): Boolean = true

    /** Results are files and offsets, not usages; "Open in Find Tool Window" would show nothing useful. */
    override fun showInFindResults(): Boolean = false

    /** Defaults to false, which would leave the tab blank until the first character is typed. */
    override fun isEmptyPatternSupported(): Boolean = true

    /** The grep walks the project and reads archives; let the platform show its "searching" state. */
    override fun isSlow(): Boolean = true

    /** Rendered as the search field's hint, so it has to stay short. */
    override fun getAdvertisement(): String = "Model keys, paths inside .bar/.zip, and model content"

    override fun fetchWeightedElements(
        pattern: String,
        progressIndicator: ProgressIndicator,
        consumer: Processor<in FoundItemDescriptor<FlowableSeItem>>,
    ) {
        highlightMatcher = if (pattern.isEmpty()) null else {
            NameUtil.buildMatcher("*$pattern", MatchingMode.IGNORE_CASE)
        }
        if (!fetchModels(FlowableInfixMatcher(pattern), progressIndicator, consumer)) return
        if (pattern.length < MIN_GREP_LENGTH || !isOwnTabSelected()) return
        fetchTextHits(pattern, progressIndicator, consumer)
    }

    /**
     * Indexed models, matched on key or archive-qualified path. Returns false when the consumer
     * asked to stop. Reads the index snapshot only — a cold index is built in the background so the
     * next keystroke is populated, the same way the Go to Symbol contributor does it.
     */
    private fun fetchModels(
        matcher: FlowableInfixMatcher,
        indicator: ProgressIndicator,
        consumer: Processor<in FoundItemDescriptor<FlowableSeItem>>,
    ): Boolean {
        val service = project.service<FlowableModelIndexService>()
        val index = service.cachedOrNull() ?: run {
            ApplicationManager.getApplication().executeOnPooledThread { runCatching { service.index() } }
            return true
        }
        var emitted = 0
        for (entry in index.allEntries()) {
            indicator.checkCanceled()
            if (!entry.file.isValid) continue
            val path = ArchivePaths.displayPath(entry.file)
            if (!matcher.prefixMatches(entry.key) && !matcher.prefixMatches(path)) continue
            val item = FlowableSeItem.Model(entry, path)
            // Clamped: a pure-infix hit can score negative, which would sink a model below the
            // text-hit band and undo the grouping.
            val weight = MODEL_WEIGHT_BASE + matcher.matchingDegree(entry.key).coerceIn(0, 9_999)
            if (!consumer.process(FoundItemDescriptor(item, weight))) return false
            if (++emitted >= MODEL_LIMIT) break
        }
        return true
    }

    /** Every occurrence of [pattern] in every model's text, each its own row, newest-first by file order. */
    private fun fetchTextHits(
        pattern: String,
        indicator: ProgressIndicator,
        consumer: Processor<in FoundItemDescriptor<FlowableSeItem>>,
    ) {
        var emitted = 0
        project.service<FlowableModelTextScanner>().forEachText(indicator) { file, text ->
            var offset = text.indexOf(pattern, 0, ignoreCase = true)
            if (offset < 0) return@forEachText true
            val path = ArchivePaths.displayPath(file)
            while (offset >= 0) {
                indicator.checkCanceled()
                val at = StringUtil.offsetToLineColumn(text, offset)
                if (at != null) {
                    val snippet = snippetAt(text, offset, pattern.length)
                    val item = FlowableSeItem.TextHit(
                        file, path, at.line, at.column, snippet.text, snippet.matchStart, pattern.length,
                    )
                    if (!consumer.process(FoundItemDescriptor(item, TEXT_WEIGHT_BASE - emitted))) return@forEachText false
                    if (++emitted >= TEXT_LIMIT) return@forEachText false
                }
                offset = text.indexOf(pattern, offset + pattern.length, ignoreCase = true)
            }
            true
        }
    }

    override fun processSelectedItem(selected: FlowableSeItem, modifiers: Int, searchText: String): Boolean {
        if (!selected.file.isValid) return true
        when (selected) {
            is FlowableSeItem.Model -> FileEditorManager.getInstance(project).openFile(selected.file, true)
            // Line/column rather than a raw offset: we decode as UTF-8 while the Document uses the
            // file's detected charset, so offsets can drift on a non-UTF-8 model.
            is FlowableSeItem.TextHit ->
                OpenFileDescriptor(project, selected.file, selected.line, selected.column).navigate(true)
        }
        return true
    }

    override fun getElementsRenderer(): ListCellRenderer<in FlowableSeItem> =
        FlowableModelSeRenderer { highlightMatcher }

    override fun getDataForItem(element: FlowableSeItem, dataId: String): Any? =
        if (CommonDataKeys.VIRTUAL_FILE.`is`(dataId)) element.file else null

    override fun getItemDescription(element: FlowableSeItem): String = element.displayPath

    /**
     * True while our own tab is the selected one — or while there is no popup to ask (tests, and any
     * platform state we cannot read from this pooled thread), where running the grep is the useful
     * default.
     */
    private fun isOwnTabSelected(): Boolean {
        val manager = runCatching { SearchEverywhereManager.getInstance(project) }.getOrNull() ?: return true
        return runCatching { !manager.isShown || manager.selectedTabID == ID }.getOrDefault(true)
    }

    /** A row's text: the matched line, and where the match sits inside it. */
    private class Snippet(val text: String, val matchStart: Int)

    /**
     * The line holding [offset], trimmed and — when it is longer than a popup row can show — windowed
     * around the match with `…` markers. Model files run to single lines of hundreds of characters, so
     * showing the raw line would push the match out of view and swamp the file name on the right.
     */
    private fun snippetAt(text: String, offset: Int, patternLength: Int): Snippet {
        val lineStart = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
        val lineEnd = text.indexOf('\n', offset).takeIf { it >= 0 } ?: text.length
        val raw = text.substring(lineStart, lineEnd)
        val indent = raw.length - raw.trimStart().length
        val line = raw.trim()
        val matchInLine = offset - lineStart - indent
        if (line.length <= MAX_LINE_LENGTH) return Snippet(line, matchInLine)

        // Keep a little context before the match so the row reads as code, not as a fragment.
        val from = (matchInLine - CONTEXT_BEFORE).coerceIn(0, (line.length - MAX_LINE_LENGTH).coerceAtLeast(0))
        val to = (from + MAX_LINE_LENGTH).coerceAtMost(line.length)
        val head = if (from > 0) ELLIPSIS else ""
        val tail = if (to < line.length) ELLIPSIS else ""
        val shift = head.length - from
        return Snippet(head + line.substring(from, to) + tail, (matchInLine + shift).takeIf { it >= 0 } ?: -1)
    }

    class Factory : SearchEverywhereContributorFactory<FlowableSeItem> {
        /** The platform only creates contributors for a project, so the event always carries one. */
        override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<FlowableSeItem> =
            FlowableModelSeContributor(requireNotNull(initEvent.project) { "Search Everywhere without a project" })
    }

    companion object {
        /** Stable, never localized: it is the persisted tab id and the key in the All-tab preferences. */
        const val ID: String = "FlowableModelSearchEverywhereContributor"

        /** Between Actions (400) and Text (1500) — after the platform's structural tabs, before text search. */
        private const val SORT_WEIGHT = 1200

        /** Below this the grep is pure noise: a single character matches almost every model's text. */
        private const val MIN_GREP_LENGTH = 2
        private const val MODEL_LIMIT = 200
        private const val TEXT_LIMIT = 300

        /** A row is one line of a popup — beyond this the text would only collide with the file name. */
        private const val MAX_LINE_LENGTH = 110
        private const val CONTEXT_BEFORE = 24
        private const val ELLIPSIS = "…"

        /** Weight bands: every model outranks every text hit, so the two kinds stay contiguous. */
        private const val MODEL_WEIGHT_BASE = 100_000
        private const val TEXT_WEIGHT_BASE = 1_000
    }
}
