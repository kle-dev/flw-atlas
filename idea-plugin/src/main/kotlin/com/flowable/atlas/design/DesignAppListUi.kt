package com.flowable.atlas.design

import com.intellij.ui.CheckBoxList

/**
 * The Atlas Hub's Design workspace/app pickers — how a workspace and an app are labelled, and how the
 * checkbox list is filled and read. Kept out of the panel so the wording is a unit, not a screenshot.
 */
internal object DesignAppListUi {

    /** Label for a workspace in the settings combo and the Hub's picker: name (+ key when they differ). */
    fun workspaceLabel(ws: DesignClient.Workspace): String =
        if (ws.name == ws.key) ws.key else "${ws.name} (${ws.key})"

    /** Checkbox text for an app: the name and nothing else — a Hub row is read in a stripe a few hundred
     *  pixels wide, and the key and version are one hover away in [appTooltip]. */
    fun appLabel(app: DesignClient.App): String = app.name.ifBlank { app.key }

    /** What the row does not say: the key, and the version when known. */
    fun appTooltip(app: DesignClient.App): String =
        app.key + (app.version?.let { " · v$it" } ?: "")

    /** Rebuilds the app checkbox list, checking every app whose key is in [checkedKeys]. */
    fun populateApps(list: CheckBoxList<DesignClient.App>, items: List<DesignClient.App>, checkedKeys: Set<String>) {
        list.clear()
        items.forEach { app -> list.addItem(app, appLabel(app), app.key in checkedKeys) }
    }

    fun checkedApps(list: CheckBoxList<DesignClient.App>): List<DesignClient.App> =
        (0 until list.model.size).mapNotNull { i -> list.getItemAt(i)?.takeIf { list.isItemSelected(i) } }

    fun checkedAppKeys(list: CheckBoxList<DesignClient.App>): List<String> = checkedApps(list).map { it.key }

    /** Key-only placeholder apps — lets a list show the persisted selection without a network call. */
    fun placeholders(appKeys: Collection<String>): List<DesignClient.App> =
        appKeys.map { DesignClient.App(it, it, null, null) }
}
