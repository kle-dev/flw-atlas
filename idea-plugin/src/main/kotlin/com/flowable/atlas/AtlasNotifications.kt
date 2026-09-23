package com.flowable.atlas

import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * The plugin's notification groups. The ids must match the `<notificationGroup id="…">` in plugin.xml;
 * they used to be spelled out as literals in a dozen classes, which is a dozen places for a rename to miss.
 *
 * Two groups, so Settings → Notifications can silence one without the other: [RESULTS_ID] for "it
 * worked" (the index was rebuilt, artifacts were generated, waivers were saved), [GROUP_ID] for what
 * asks for attention (a failed pull, a rename not applied to the models).
 */
object AtlasNotifications {
    const val GROUP_ID = "Flowable Atlas"
    const val RESULTS_ID = "Flowable Atlas Results"

    fun group(): NotificationGroup = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)

    /** The group for a notification that reports a finished job. */
    fun results(): NotificationGroup = NotificationGroupManager.getInstance().getNotificationGroup(RESULTS_ID)

    /** [results] for plain information, [group] for anything that warns. */
    fun groupFor(type: NotificationType): NotificationGroup = if (type == NotificationType.INFORMATION) results() else group()

    /** A plain balloon where a modal dialog used to stop the user for a sentence. */
    fun info(project: Project, text: String) = group().createNotification(text, NotificationType.INFORMATION).notify(project)
}
