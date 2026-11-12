package com.flowable.atlas.explorer

import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel

/**
 * Where an explorer tab was — its route, `process%3ADEMO-P001` or `/checks&f=…`, empty for the dashboard.
 * The platform writes it with the rest of the workspace ([AtlasFileEditorProvider.writeState]) and hands
 * it back when the tab is reopened, so a restart no longer drops the reader on the dashboard.
 */
data class AtlasExplorerState(val route: String) : FileEditorState {
    override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean =
        otherState is AtlasExplorerState
}
