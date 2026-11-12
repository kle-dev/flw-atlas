package com.flowable.atlas.expr.lang

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry

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

    /**
     * A file, like a leaf, exposes no provider references unless it asks for them — and the fragment
     * has no other element to hang a reference on. Asked here, so a `${bean.…}` root can be a reference
     * (see `FlowableExprBeanReferenceContributor`) with fragment-relative ranges.
     */
    override fun getReferences(): Array<PsiReference> = ReferenceProvidersRegistry.getReferencesFromProviders(this)

    override fun toString(): String = "Flowable Expression File"
}
