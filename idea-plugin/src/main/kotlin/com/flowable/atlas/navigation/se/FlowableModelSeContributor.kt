package com.flowable.atlas.navigation.se

import com.flowable.atlas.model.ModelKeyDeclaration
import com.flowable.atlas.completion.FlowableInfixMatcher
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.navigation.ModelElements
import com.flowable.atlas.navigation.ModelKeyTargets
import com.flowable.atlas.usage.ModelSearchUsages
import com.intellij.ide.actions.searcheverywhere.FoundItemDescriptor
import com.intellij.ide.actions.searcheverywhere.PossibleSlowContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
import com.intellij.ide.actions.searcheverywhere.WeightedSearchEverywhereContributor
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.util.Processor
import com.intellij.util.text.matching.MatchingMode
import java.awt.event.InputEvent
import java.util.function.BiConsumer
import javax.swing.ListCellRenderer

/**
 * A Search Everywhere tab of its own — **Flowable Model** — that searches only Flowable models,
 * including the ones packed inside a `.bar`/`.zip`. Those archive entries are invisible to the
 * platform's Files tab and to Find in Files (they live in no content or library root), which is
 * exactly the gap this closes.
 *
 * Three kinds of result, in one flat weight-sorted list (Search Everywhere has no section headers):
 *  * **models** — matched on the model key *and* on the archive-qualified file path, from the
 *    already-built index, so this half is instant;
 *  * **elements** — a user task, an activity, a variable, a message, a form field… matched on its id,
 *    from the same index, shown with the model it belongs to;
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
    private var highlight: SeHighlight? = null

    override fun getSearchProviderId(): String = ID

    override fun getGroupName(): String = "Flowable Model"

    override fun getSortWeight(): Int = SORT_WEIGHT

    /** Without this the contributor would only ever feed the "All" tab — no tab of its own. */
    override fun isShownInSeparateTab(): Boolean = true

    /**
     * Stays false, and the alternative is worth recording. The flag only *enables* the platform's
     * "Open in Find Tool Window" button; the action behind it fills the window from three item shapes
     * only — `UsageInfo2UsageAdapter`, its own `SearchEverywhereItem`, and whatever it can convert to
     * PSI — and [FlowableSeItem] is none of them, so turning this on would light a button that opens an
     * empty window. ⇧⏎ hands over to [com.flowable.atlas.usage.ModelSearchUsages] instead, which builds
     * the same list on the public usage-view API.
     */
    override fun showInFindResults(): Boolean = false

    /** Defaults to false, which would leave the tab blank until the first character is typed. */
    override fun isEmptyPatternSupported(): Boolean = true

    /** The grep walks the project and reads archives; let the platform show its "searching" state. */
    override fun isSlow(): Boolean = true

    /** Rendered as the search field's hint, so it has to stay short. */
    override fun getAdvertisement(): String =
        "Model keys, paths inside .bar/.zip, and model content · ⇧⏎ lists every hit"

    override fun fetchWeightedElements(
        pattern: String,
        progressIndicator: ProgressIndicator,
        consumer: Processor<in FoundItemDescriptor<FlowableSeItem>>,
    ) {
        highlight = if (pattern.isEmpty()) null else {
            SeHighlight(pattern, NameUtil.buildMatcher("*$pattern", MatchingMode.IGNORE_CASE))
        }
        val matcher = FlowableInfixMatcher(pattern)
        if (!fetchModels(matcher, progressIndicator, consumer)) return
        if (pattern.length >= MIN_ELEMENT_LENGTH && !fetchElements(matcher, progressIndicator, consumer)) return
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
        val index = service.cachedOrRequest() ?: return true
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

    /**
     * The named elements inside the indexed models — user tasks, activities, variables, messages, signals,
     * payload fields, form fields and outcomes — matched on their id. From the index, so instant; ranked
     * under every model and over every text hit.
     */
    private fun fetchElements(
        matcher: FlowableInfixMatcher,
        indicator: ProgressIndicator,
        consumer: Processor<in FoundItemDescriptor<FlowableSeItem>>,
    ): Boolean {
        val index = project.service<FlowableModelIndexService>().cachedOrNull() ?: return true
        var emitted = 0
        for (entry in index.allDistinct()) {
            indicator.checkCanceled()
            if (!entry.file.isValid) continue
            var path: String? = null
            for (element in ModelElements.of(entry)) {
                if (!matcher.prefixMatches(element.id)) continue
                val item = FlowableSeItem.Element(element, path ?: ArchivePaths.displayPath(entry.file).also { path = it })
                val weight = ELEMENT_WEIGHT_BASE + matcher.matchingDegree(element.id).coerceIn(0, 8_999)
                if (!consumer.process(FoundItemDescriptor(item, weight))) return false
                if (++emitted >= ELEMENT_LIMIT) return true
            }
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
        if (handsOverToList(modifiers, searchText)) {
            ModelSearchUsages.show(project, searchText)
            return true
        }
        if (!selected.file.isValid) return true
        when (selected) {
            // On the key's declaration, like a Ctrl+click — not line 1 of a minified model.
            is FlowableSeItem.Model ->
                ModelKeyTargets.openAt(project, selected.file) { ModelKeyTargets.lineColumn(selected.entry) }
            // On the element's declaration — its quoted id — when the text spells it that way.
            is FlowableSeItem.Element -> ModelKeyTargets.openAt(project, selected.file) {
                val text = runCatching { String(selected.file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()
                text?.let { t -> ModelElements.declarationOffset(t, selected.element.id)?.let { ModelKeyDeclaration.lineColumn(t, it) } }
            }
            // Line/column rather than a raw offset: we decode as UTF-8 while the Document uses the
            // file's detected charset, so offsets can drift on a non-UTF-8 model.
            is FlowableSeItem.TextHit ->
                OpenFileDescriptor(project, selected.file, selected.line, selected.column).navigate(true)
        }
        return true
    }

    /**
     * ⇧⏎ — the popup closes into a list that stays open, because opening one result is what closes this
     * popup, and a search that matched thirty places cannot be walked one query at a time.
     *
     * Split out so the routing can be asserted on its own: building the real usage view in a light test
     * trips an assertion inside the platform's own tree renderer, which says nothing about this branch.
     */
    internal fun handsOverToList(modifiers: Int, searchText: String): Boolean =
        modifiers and InputEvent.SHIFT_DOWN_MASK != 0 &&
            searchText.length >= ModelSearchUsages.MIN_PATTERN_LENGTH

    override fun getElementsRenderer(): ListCellRenderer<in FlowableSeItem> =
        FlowableModelSeRenderer { highlight }

    /** What the popup's own actions see as the selected item: the model file behind the row. Replaces
     *  the deprecated dataId-based `getDataForItem` — same data, pushed into a typed sink. */
    override fun getDataProviders(): List<BiConsumer<FlowableSeItem, DataSink>> =
        listOf(BiConsumer { item, sink -> sink[CommonDataKeys.VIRTUAL_FILE] = item.file })

    override fun getItemDescription(element: FlowableSeItem): String = element.description

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

        /** A one-letter pattern matches an element in every model; elements start at two, like the grep. */
        private const val MIN_ELEMENT_LENGTH = 2
        private const val ELEMENT_LIMIT = 200

        /** Weight bands: every model outranks every element, every element every text hit — three
         *  contiguous kinds. */
        private const val MODEL_WEIGHT_BASE = 100_000
        private const val ELEMENT_WEIGHT_BASE = 10_000
        private const val TEXT_WEIGHT_BASE = 1_000
    }
}
