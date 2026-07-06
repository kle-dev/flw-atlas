package com.flowable.keys.liquibase

import com.flowable.keys.index.ServiceTable
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext

/**
 * Completes Liquibase `<column name="…">` and `<… tableName="…">` inside a Flowable changelog with
 * the physical columns / table of the backing `database` `.service` model — so an `<insert>` /
 * `<createTable>` / `<update>` / `<addColumn>` offers exactly the columns Flowable expects.
 */
class LiquibaseColumnCompletionContributor : CompletionContributor() {

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().inside(XmlAttributeValue::class.java), Provider())
    }

    private val COLUMN_HOLDERS = setOf("insert", "update", "createTable", "addColumn")

    private inner class Provider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val position = parameters.position
            val attr = PsiTreeUtil.getParentOfType(position, XmlAttributeValue::class.java)?.parent as? XmlAttribute ?: return
            val tag = attr.parent as? XmlTag ?: return
            val file = position.containingFile as? XmlFile ?: return
            val text = file.text
            if (!text.contains("databaseChangeLog")) return

            when {
                tag.name == "column" && attr.name == "name" -> {
                    val holder = tag.parentTag ?: return
                    if (holder.name !in COLUMN_HOLDERS) return
                    addColumns(file, text, holder.getAttributeValue("tableName"), result)
                }
                tag.name == "column" && attr.name == "type" -> {
                    val holder = tag.parentTag ?: return
                    if (holder.name !in COLUMN_HOLDERS) return
                    addColumnTypes(file, text, holder.getAttributeValue("tableName"), tag.getAttributeValue("name"), result)
                }
                attr.name == "tableName" -> addTables(file, text, result)
            }
        }

        /** Database services backing this changelog, narrowed to [tableName] when it resolves. */
        private fun scoped(file: XmlFile, text: String, tableName: String?): List<ServiceTable> {
            val services = LiquibaseModelResolver.servicesFor(file.project, file.name, text)
            return tableName?.let { t -> services.filter { it.tableName.equals(t, ignoreCase = true) } }
                ?.takeIf { it.isNotEmpty() } ?: services
        }

        private fun addColumns(file: XmlFile, text: String, tableName: String?, result: CompletionResultSet) {
            val seen = HashSet<String>()
            for (st in scoped(file, text, tableName)) for (c in st.columns) {
                val physical = c.columnName ?: c.name ?: continue
                if (physical.isBlank() || !seen.add(physical)) continue
                var b = LookupElementBuilder.create(physical).withTypeText(st.tableName ?: st.key, true)
                if (c.type != null) b = b.withTailText("  [${c.type}]", true)
                result.addElement(b)
            }
        }

        /**
         * Completes a `<column type="…">` with the Liquibase type Flowable derives from the matching
         * `columnMappings[].type` (ranked first), falling back to the full type palette.
         */
        private fun addColumnTypes(file: XmlFile, text: String, tableName: String?, columnName: String?, result: CompletionResultSet) {
            val services = scoped(file, text, tableName)
            val added = HashSet<String>()
            if (!columnName.isNullOrBlank()) {
                val loose = LiquibaseChangelog.loose(columnName)
                val mapping = services.flatMap { it.columns }.firstOrNull {
                    LiquibaseChangelog.loose(it.columnName) == loose || LiquibaseChangelog.loose(it.name) == loose
                }
                LiquibaseChangelog.liquibaseType(mapping?.type)?.let { mapped ->
                    if (added.add(mapped)) {
                        val b = LookupElementBuilder.create(mapped).bold()
                            .withTypeText(mapping?.type ?: "", true)
                        result.addElement(PrioritizedLookupElement.withPriority(b, 100.0))
                    }
                }
            }
            for (t in LiquibaseChangelog.TYPE_PALETTE) if (added.add(t)) result.addElement(LookupElementBuilder.create(t))
        }

        private fun addTables(file: XmlFile, text: String, result: CompletionResultSet) {
            val services = LiquibaseModelResolver.servicesFor(file.project, file.name, text)
            val seen = HashSet<String>()
            for (st in services) {
                val table = st.tableName ?: continue
                if (seen.add(table)) result.addElement(LookupElementBuilder.create(table).withTypeText(st.key, true))
            }
        }
    }
}
