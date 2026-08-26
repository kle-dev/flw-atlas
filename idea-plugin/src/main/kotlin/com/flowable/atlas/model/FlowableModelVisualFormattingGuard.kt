package com.flowable.atlas.model

import com.intellij.codeInsight.actions.ReaderModeProvider
import com.intellij.formatting.visualLayer.VisualFormattingLayerService
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

/**
 * Keeps Reader Mode's **visual formatting layer** off Flowable model files, because on IntelliJ 2026.x
 * it throws on them.
 *
 * `VisualFormattingLayerServiceImpl` asks the document for `getLineStartOffset(startLine + 1)` without
 * checking that the line exists. For a document whose last line is the one the formatter wants to wrap —
 * a *minified single-line JSON* being the reliable case — that index is one past the end and the
 * highlighting pass dies with `IndexOutOfBoundsException: Wrong line: 1. Available lines count: 1`,
 * re-thrown on every daemon restart until the platform's own frequency limiter starts swallowing it.
 * Reproduced on IU-261.22158.277 and IU-262.8665.258 with one line of minified JSON; XML and Java do
 * not trigger it.
 *
 * Minified single-line JSON is exactly what Flowable Design exports, and Atlas is the reason such a file
 * is open at all: the *Flowable Model* search tab is the only thing in the IDE that opens model files
 * from inside a `.bar`/`.zip`, and archive entries are read-only — which is the one case the visual
 * formatting layer runs in (`collectVisualFormattingLayerElements` returns empty for a writable
 * document). So the platform bug is not ours, but the path to it is, and an IDE error balloon straight
 * after an Atlas action reads as an Atlas defect.
 *
 * What is given up is nothing: the layer virtually reformats a file you cannot edit anyway, and on a
 * minified model export it has never been able to do so without crashing.
 *
 * Registered with `order="last"` on purpose — the platform's own `VisualFormattingLayerReaderModeProvider`
 * *enables* the layer from the same extension point, so a guard that ran first would simply be undone.
 * `FlowableModelVisualFormattingGuardTest` pins that ordering, because nothing else would notice it
 * breaking. Writability is deliberately not checked: turning the layer off for a writable model file
 * changes nothing (Reader Mode does not apply there), and requiring it would make the guard untestable
 * in a fixture, where every file is writable.
 */
class FlowableModelVisualFormattingGuard : ReaderModeProvider {

    override fun applyModeChanged(project: Project, editor: Editor, readerMode: Boolean, fileIsOpenAlready: Boolean) {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (ModelFiles.typeOf(file) == null) return
        VisualFormattingLayerService.disableForEditor(editor)
    }
}
