package com.flowable.atlas.design

import com.intellij.ui.CheckBoxList

/**
 * Shared bits of the Design app checkbox list — used by both the Connections settings panel (the
 * team default) and the Atlas Hub (the personal pull selection), so the two render and read the
 * list identically.
 */
internal object DesignAppListUi {

    /** Checkbox text for an app: display name (+ key when they differ) and version when known. */
    fun appLabel(app: DesignClient.App): String {
        val label = if (app.name == app.key) app.key else "${app.name} (${app.key})"
        return label + (app.version?.let { " v$it" } ?: "")
    }

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
