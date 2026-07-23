package com.flowable.atlas.usage

import com.intellij.openapi.vfs.VirtualFile

/**
 * Locates the rendered diagram (`.svg`) that a Flowable Design *export* ships next to each model
 * file (`bpmn-models/onboarding.bpmn` → `bpmn-models/onboarding.svg`; likewise form / decision-table
 * models). A deployment BAR carries no `.svg`, so this returns null there — which is what keeps the
 * diagram gutter marker self-limiting (no diagram → no marker).
 */
object FlowableDiagram {

    /**
     * The sibling `.svg` of [modelFile] — a file in the same directory whose name is the model file's
     * base name plus `.svg`, matched case-insensitively — or null when none exists (or the model file
     * has no parent directory). Works for a model file inside a jar/zip VFS too (it only walks the VFS
     * parent/children).
     */
    fun siblingSvg(modelFile: VirtualFile): VirtualFile? {
        val parent = modelFile.parent ?: return null
        val expected = modelFile.name.substringBeforeLast('.') + ".svg"
        return parent.children?.firstOrNull { !it.isDirectory && it.name.equals(expected, ignoreCase = true) }
    }
}
