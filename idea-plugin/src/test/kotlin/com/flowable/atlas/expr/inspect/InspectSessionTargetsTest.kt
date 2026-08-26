package com.flowable.atlas.expr.inspect

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectSessionTargetsTest {

    @After fun tearDown() = InspectSessionTargets.clear()

    @Test fun keepsEveryPastedTargetInTheOrderTheyArrived() {
        InspectSessionTargets.add("http://localhost:8080/flowable-work")
        InspectSessionTargets.add("https://work-qa.example.com")
        assertEquals(
            listOf("http://localhost:8080/flowable-work", "https://work-qa.example.com"),
            InspectSessionTargets.all(),
        )
    }

    @Test fun theSameAppIsOneTarget() {
        val first = InspectSessionTargets.add("https://work.example.com/flowable-work")
        // A trailing slash, a differently cased host, the default port — same app, same entry, and the
        // spelling that is already on screen wins so the picker does not shuffle under the user.
        val again = InspectSessionTargets.add("https://WORK.example.com:443/flowable-work/")
        assertEquals(first, again)
        assertEquals(1, InspectSessionTargets.all().size)
        assertEquals("https://work.example.com/flowable-work", InspectSessionTargets.all().single())
    }

    @Test fun differentContextPathsOnOneHostAreDifferentTargets() {
        InspectSessionTargets.add("https://host/app")
        InspectSessionTargets.add("https://host/app2")
        assertEquals(2, InspectSessionTargets.all().size)
    }

    @Test fun removeTakesTheEntryHoweverItIsSpelled() {
        InspectSessionTargets.add("https://host/app")
        assertTrue(InspectSessionTargets.contains("https://host/app/"))
        InspectSessionTargets.remove("https://host/app/")
        assertFalse(InspectSessionTargets.contains("https://host/app"))
        assertTrue(InspectSessionTargets.all().isEmpty())
    }

    @Test fun blankIsNeverATarget() {
        InspectSessionTargets.add("   ")
        assertTrue(InspectSessionTargets.all().isEmpty())
        assertFalse(InspectSessionTargets.contains(""))
    }
}
