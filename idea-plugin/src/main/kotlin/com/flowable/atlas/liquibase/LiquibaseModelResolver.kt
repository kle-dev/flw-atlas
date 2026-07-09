package com.flowable.atlas.liquibase

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.parsing.ServiceTable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Resolves the Flowable `database` `.service` model(s) that back a Liquibase changelog, shared by
 * the coverage inspection and the column completion. The link is (strongest first): the changelog's
 * `serviceDefinitionReferences` property, a service's `referencedLiquibaseModelKey` matching the
 * changelog filename key, or a service `tableName` occurring in the changelog.
 */
object LiquibaseModelResolver {

    fun servicesFor(project: Project, fileName: String, text: String): List<ServiceTable> {
        val service = project.service<FlowableModelIndexService>()
        val refs = LiquibaseChangelog.serviceReferences(text)
        val changelogKey = LiquibaseChangelog.changelogKey(fileName)
        val tables = LiquibaseChangelog.tableNames(text).map { it.uppercase() }.toSet()

        val resolved = LinkedHashSet<ServiceTable>()
        refs.forEach { key -> service.serviceTableOf(key)?.let { resolved.add(it) } }
        if (resolved.isEmpty()) {
            for (st in service.allServiceTables()) {
                if (st.referencedLiquibaseModelKey == changelogKey || st.tableName?.uppercase() in tables) {
                    resolved.add(st)
                }
            }
        }
        return resolved.filter { it.isDatabase }
    }

    /** Loose column names (physical `columnName` and logical `name`) covered by [services]. */
    fun looseColumns(services: List<ServiceTable>): Set<String> {
        val loose = HashSet<String>()
        for (st in services) for (c in st.columns) {
            c.columnName?.let { loose.add(LiquibaseChangelog.loose(it)) }
            c.name?.let { loose.add(LiquibaseChangelog.loose(it)) }
        }
        return loose
    }
}
