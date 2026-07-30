package com.flowable.atlas.design

import com.flowable.atlas.project.AtlasProjectRootService
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

/**
 * The user's *personal* pull selection — which workspace/apps "Pull from Design" actually fetches.
 *
 * Two layers, on purpose: the settings hold the VCS-shared team default
 * ([com.flowable.atlas.settings.FlowableAtlasProjectSettings.designWorkspaceKey]/`designAppKeys` in
 * `.idea/flowable-atlas.xml`), and this override is workspace-local ([PropertiesComponent], the same
 * non-VCS category as the active sub-project and the last-pull timestamp) so ticking apps in the
 * Atlas Hub for one pull never dirties the shared settings file. No override means "pull the
 * configured default". Stored per active sub-project, like every other Design setting.
 */
object DesignPullSelection {

    data class Selection(val workspaceKey: String, val appKeys: List<String>)

    private const val PROPERTY = "flowable.atlas.designPullSelection"

    fun load(project: Project): Selection? =
        parse(PropertiesComponent.getInstance(project).getValue(propertyKey(project)))

    fun save(project: Project, selection: Selection) =
        PropertiesComponent.getInstance(project).setValue(propertyKey(project), serialize(selection), null)

    fun clear(project: Project) =
        PropertiesComponent.getInstance(project).unsetValue(propertyKey(project))

    private fun propertyKey(project: Project): String {
        val scope = AtlasProjectRootService.getInstance(project).activeSubProject()
        return if (scope.isEmpty()) PROPERTY else "$PROPERTY.$scope"
    }

    // ---- pure, testable core ----------------------------------------------------------------

    /** The selection a pull runs with: the personal override when one exists, else the default. */
    fun effective(default: Selection, override: Selection?): Selection = override ?: default

    /** Whether [override] actually deviates (app order is irrelevant) — an identical override is
     *  noise and should be cleared rather than stored. */
    fun differsFromDefault(default: Selection, override: Selection?): Boolean =
        override != null && (override.workspaceKey != default.workspaceKey ||
            override.appKeys.toSet() != default.appKeys.toSet())

    fun serialize(selection: Selection): String =
        selection.workspaceKey + "|" + selection.appKeys.joinToString(",")

    fun parse(raw: String?): Selection? {
        if (raw.isNullOrBlank()) return null
        val bar = raw.indexOf('|')
        if (bar < 0) return null
        val workspaceKey = raw.substring(0, bar)
        if (workspaceKey.isBlank()) return null
        val apps = raw.substring(bar + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return Selection(workspaceKey, apps)
    }
}
