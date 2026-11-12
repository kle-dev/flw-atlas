package com.flowable.atlas.playground

import com.intellij.lang.Language
import com.intellij.openapi.command.undo.UndoUtil
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.LanguageTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension

/** The one way a playground builds its editor fields — the expression, the payload, the script. */
object PlaygroundEditors {

    /**
     * A multi-line [LanguageTextField] that behaves like the IDE's own editors: the editor colour
     * scheme's font (a `LanguageTextField` inherits the Swing label font unless told otherwise, which
     * is why the old squiggles had to be hand-painted thick to be seen at all), undo, scrollbars, a
     * height floor so a splitter cannot squeeze it to nothing, and [onEditor] on every (re)materialised
     * editor so markup can be re-attached after a language switch swaps the document.
     */
    fun create(
        project: Project,
        language: Language,
        text: String,
        lineNumbers: Boolean,
        minHeight: Int,
        onEditor: (EditorEx) -> Unit,
        onChange: () -> Unit,
    ): LanguageTextField = LanguageTextField(language, project, text, false).apply {
        setFontInheritedFromLAF(false)
        border = JBUI.Borders.customLine(JBColor.border(), 1)
        minimumSize = Dimension(JBUI.scale(120), JBUI.scale(minHeight))
        addSettingsProvider { editor ->
            // A LanguageTextField's document isn't file-backed, so the platform's global $Undo action
            // doesn't track it out of the box — enable it explicitly, for the new document too.
            UndoUtil.enableUndoFor(editor.document)
            editor.setVerticalScrollbarVisible(true)
            editor.setHorizontalScrollbarVisible(true)
            editor.setBorder(JBUI.Borders.empty(4))
            editor.settings.apply {
                isLineNumbersShown = lineNumbers
                isFoldingOutlineShown = false
                isLineMarkerAreaShown = false
                isUseSoftWraps = false            // long expressions scroll horizontally
                isCaretRowShown = false
                additionalLinesCount = 1
                additionalColumnsCount = 2
            }
            onEditor(editor as EditorEx)
        }
        addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = onChange()
        })
    }
}
