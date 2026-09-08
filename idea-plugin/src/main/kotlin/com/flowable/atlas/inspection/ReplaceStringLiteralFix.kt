package com.flowable.atlas.inspection

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiLiteralExpression

/**
 * The "did you mean" fix for a Java string literal: [getName] carries the suggestion, [getFamilyName] does
 * not. Three inspections used to return the suggestion *as* the family, so every distinct replacement was
 * its own family — *Fix all* and the Alt+Enter list grouped by value instead of by what the fix does.
 */
open class ReplaceStringLiteralFix(private val replacement: String, private val family: String) : LocalQuickFix {

    override fun getName(): String = "Replace with '$replacement'"
    override fun getFamilyName(): String = family

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val literal = descriptor.psiElement as? PsiLiteralExpression ?: return
        val factory = JavaPsiFacade.getElementFactory(project)
        literal.replace(factory.createExpressionFromText("\"$replacement\"", literal))
    }

    companion object {
        const val KNOWN_MODEL_KEY = "Replace with a known model key"
        const val KNOWN_INPUT_VALUE = "Replace with a known input value"
    }
}
