package com.flowable.keys.liquibase

import com.intellij.codeInspection.LocalInspectionTool
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

        val serviceColumns = resolveServiceColumns(holder, file, text) ?: return PsiElementVisitor.EMPTY_VISITOR
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
                holder.registerProblem(
                    valueElement,
                    "Column '$value' is not mapped to any field of the backing Flowable data-object/service model",
                    ProblemHighlightType.WARNING,
                )
            }
        }
    }

    /**
     * The loose column names covered by the database service(s) this changelog belongs to, or null
     * if no such service resolves (→ don't inspect).
     */
    private fun resolveServiceColumns(holder: ProblemsHolder, file: XmlFile, text: String): Set<String>? {
        val services = LiquibaseModelResolver.servicesFor(holder.project, file.name, text)
        if (services.isEmpty()) return null
        return LiquibaseModelResolver.looseColumns(services)
    }
}
