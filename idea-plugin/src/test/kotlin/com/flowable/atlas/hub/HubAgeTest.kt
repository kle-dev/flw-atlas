package com.flowable.atlas.hub

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

/** The few words a side panel has room for, at every boundary. */
class HubAgeTest {

    private val zone = ZoneOffset.UTC
    private val now = 1_700_000_000_000L   // 2023-11-14 22:13:20 UTC
    private fun age(millisAgo: Long) = HubAge.relative(now - millisAgo, now, zone, time = { "T" }, date = { "D" })

    @Test
    fun withinTheHourItCountsMinutes() {
        assertEquals("just now", age(0))
        assertEquals("just now", age(59_000))
        assertEquals("1 min ago", age(60_000))
        assertEquals("59 min ago", age(59 * 60_000L))
    }

    @Test
    fun withinTheDayItNamesTheDayAndTheTime() {
        assertEquals("today T", age(2 * 3_600_000L))          // 20:13 the same day
        assertEquals("yesterday T", age(23 * 3_600_000L))     // 23:13 the day before
    }

    @Test
    fun beyondThatItCountsDaysThenWeeksThenDates() {
        assertEquals("3d ago", age(3 * 86_400_000L))
        assertEquals("2w ago", age(15 * 86_400_000L))
        assertEquals("D", age(40 * 86_400_000L))
    }
}
