package com.flowable.atlas

import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * The plugin's notification groups. The ids must match the `<notificationGroup id="…">` in plugin.xml;
 * they used to be spelled out as literals in a dozen classes, which is a dozen places for a rename to miss.
 *
 * Two groups, so Settings → Notifications can silence one without the other, and one rule for which is
 * which: [results] is for a job that **finished as asked** — the index was rebuilt, artifacts were
 * generated, waivers were saved — and [group] is for everything the user should read: a warning, a
 * failure, or a sentence saying why nothing happened. The rule used to be two rules (the helper for
 * plain information pointed at one group, the one for a job's outcome at the other), and half the call
 * sites fetched a group by its id and picked neither.
 */
object AtlasNotifications {
    const val GROUP_ID = "Flowable Atlas"
    const val RESULTS_ID = "Flowable Atlas Results"

    /** Needs attention: warnings, failures, and why nothing happened. */
    fun group(): NotificationGroup = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)

    /** A job finished as asked. */
    fun results(): NotificationGroup = NotificationGroupManager.getInstance().getNotificationGroup(RESULTS_ID)

    /** The group for the outcome of a job: [results] when it went as asked, [group] when it warns or fails. */
    fun groupFor(type: NotificationType): NotificationGroup = if (type == NotificationType.INFORMATION) results() else group()

    /** A plain balloon where a modal dialog used to stop the user for a sentence — about something that
     *  did not happen, so it needs reading: [group]. */
    fun info(project: Project, text: String) = group().createNotification(text, NotificationType.INFORMATION).notify(project)
}
