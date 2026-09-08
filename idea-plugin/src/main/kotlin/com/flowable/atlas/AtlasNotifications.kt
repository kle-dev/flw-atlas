package com.flowable.atlas

import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager

/**
 * The plugin's one notification group. The id must match the `<notificationGroup id="…">` in plugin.xml;
 * it used to be spelled out as a literal in a dozen classes, which is a dozen places for a rename to miss.
 */
object AtlasNotifications {
    const val GROUP_ID = "Flowable Atlas"

    fun group(): NotificationGroup = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
}
