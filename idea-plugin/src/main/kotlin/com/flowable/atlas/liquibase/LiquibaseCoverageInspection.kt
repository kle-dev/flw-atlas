package com.flowable.atlas.liquibase

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.index.ProjectModelScope
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.navigation.ModelKeyTargets
import com.flowable.atlas.parsing.ServiceTable
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * Flags a Liquibase `<column>` that is not mapped in the Flowable `.service` model backing the
 * changelog — i.e. a column present in the migration but "not defined in the model".
 *
 * The changelog is generated from the service's `columnMappings`, so a column whose name matches
 * neither a mapping's physical `columnName` nor its logical `name` (compared loosely, so `CREW_ID_`
 * ≈ `crewId`) is schema drift. Reports only when the changelog resolves to a `database` service (via
 * its `serviceDefinitionReferences` property, the service's `referencedLiquibaseModelKey`, or a
 * `tableName` match) — otherwise stays silent to avoid false positives on unrelated changelogs.
 *
 * Note: rename/drop within the same file is honoured (a column renamed away later isn't flagged at
 * its declaration); cross-file include replay (v1→v2 directories) is not applied.
 */
class LiquibaseCoverageInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? XmlFile ?: return PsiElementVisitor.EMPTY_VISITOR
        val text = file.text
        if (!text.contains("databaseChangeLog")) return PsiElementVisitor.EMPTY_VISITOR
        // A highlighting pass reads the cached index only (see FlowableModelIndexService.index): on a
        // cold index there is no verdict, and the daemon re-runs once the build lands.
        if (file.project.service<FlowableModelIndexService>().cachedOrRequest() == null) return PsiElementVisitor.EMPTY_VISITOR

        val services = LiquibaseModelResolver.servicesFor(holder.project, file.name, text, cachedOnly = true)
        if (services.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR   // no backing service resolves → don't inspect
        val serviceColumns = LiquibaseModelResolver.looseColumns(services)
        val ops = LiquibaseChangelog.parseOps(text)
        val unmapped = LiquibaseChangelog.unmappedLooseNames(ops, serviceColumns)
        if (unmapped.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR

        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                when (tag.name) {
                    "column" -> {
                        val parent = tag.parentTag?.name
                        if (parent == "createTable" || parent == "addColumn") flag(tag, "name")
                    }
                    "renameColumn" -> flag(tag, "newColumnName")
                }
            }

            private fun flag(tag: XmlTag, attrName: String) {
                val value = tag.getAttributeValue(attrName) ?: return
                if (LiquibaseChangelog.loose(value) !in unmapped) return
                val valueElement = tag.getAttribute(attrName)?.valueElement ?: return
                // Names the model it compared against: "the backing model" sent the reader looking for it.
                val noun = if (services.size == 1) "model" else "models"
                val owner = services.joinToString(", ") { "'${it.key}'" }
                // In a monorepo the index is one sub-project's; a column unknown *here* may be mapped in another.
                val scope = ProjectModelScope.label(holder.project)?.let { " in $it" } ?: ""
                // The way out: the service model whose mappings decide, opened at its key.
                val fixes = services.map<ServiceTable, LocalQuickFix> { OpenServiceModelFix(it.key) }.toTypedArray()
                holder.registerProblem(
                    valueElement,
                    "Column '$value' is not mapped in Flowable service $noun $owner$scope",
                    ProblemHighlightType.WARNING,
                    *fixes,
                )
            }
        }
    }

    /** Opens the `.service` model the column was checked against, at its key — a jump, not an edit. */
    private class OpenServiceModelFix(private val serviceKey: String) : LocalQuickFix {
        override fun getName(): String = "Open the Flowable service model '$serviceKey'"
        override fun getFamilyName(): String = "Open the Flowable service model"
        override fun startInWriteAction(): Boolean = false
        override fun availableInBatchMode(): Boolean = false
        override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val entry = project.service<FlowableModelIndexService>().cachedOrNull()?.find(serviceKey, ModelType.SERVICE) ?: return
            ModelKeyTargets.openAt(project, entry.file) { ModelKeyTargets.lineColumn(entry) }
        }
    }
}
