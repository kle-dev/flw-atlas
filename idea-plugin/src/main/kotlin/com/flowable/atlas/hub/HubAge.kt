package com.flowable.atlas.hub

import com.intellij.util.text.DateFormatUtil
import java.time.Instant
import java.time.ZoneId

/**
 * How long ago something happened, in the few words a side panel has room for: "just now", "12 min ago",
 * "today 14:02", "yesterday 09:41", "3d ago", "2w ago", then the date. The header and the explorer rows
 * both use it, so "when was this?" reads the same everywhere in the panel.
 *
 * The clock, the zone and the two platform formatters are parameters so the boundaries are unit-tested
 * without an application.
 */
internal object HubAge {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY

    fun relative(
        millis: Long,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        time: (Long) -> String = DateFormatUtil::formatTime,
        date: (Long) -> String = DateFormatUtil::formatDate,
    ): String {
        val delta = now - millis
        return when {
            delta < MINUTE -> "just now"
            delta < HOUR -> "${delta / MINUTE} min ago"
            sameDay(millis, now, zone) -> "today ${time(millis)}"
            sameDay(millis + DAY, now, zone) -> "yesterday ${time(millis)}"
            delta < WEEK -> "${delta / DAY}d ago"
            delta < 5 * WEEK -> "${delta / WEEK}w ago"
            else -> date(millis)
        }
    }

    private fun sameDay(a: Long, b: Long, zone: ZoneId): Boolean =
        Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}
