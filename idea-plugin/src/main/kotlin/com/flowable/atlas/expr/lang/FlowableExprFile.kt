package com.flowable.atlas.expr.lang

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider

/**
 * The PSI file for an expression fragment. Its content is a single leaf (see
 * [FlowableExprParserDefinition]) — validation and completion work off the text, not a typed tree.
 */
class FlowableExprFile(
    viewProvider: FileViewProvider,
    language: FlowableExprLanguage,
    private val fileType: FileType,
) : PsiFileBase(viewProvider, language) {

    override fun getFileType(): FileType = fileType

    override fun toString(): String = "Flowable Expression File"
}
