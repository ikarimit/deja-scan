package com.dejascan.app

import com.dejascan.app.scan.ApproachTracker
import com.dejascan.app.scan.Reading
import com.dejascan.app.scan.Trend
import com.dejascan.app.scan.channelFor
import com.dejascan.app.scan.radialFrac
import com.dejascan.app.scan.trendOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApproachTrackerTest {
    private val mac = "aa:bb:cc:dd:ee:ff"

    @Test fun firstSightingAlertsThenSteadySignalDoesNot() {
        val t = ApproachTracker()
        assertTrue(t.onReading(mac, -70, 0))
        repeat(20) { assertFalse(t.onReading(mac, -70, 1000L * (it + 1))) }
    }

    @Test fun smallRiseDoesNotAlertButTenDbDoes() {
        val t = ApproachTracker()
        t.onReading(mac, -70, 0)
        var now = 0L
        repeat(10) { assertFalse(t.onReading(mac, -65, ++now * 1000)) }
        var alerted = false
        repeat(20) { if (t.onReading(mac, -55, ++now * 1000)) alerted = true }
        assertTrue(alerted)
    }

    @Test fun alertsAgainAfterMovingAwayAndBack() {
        val t = ApproachTracker()
        t.onReading(mac, -50, 0)
        var now = 0L
        repeat(20) { t.onReading(mac, -85, ++now * 1000) } // baseline follows it down
        var alerted = false
        repeat(20) { if (t.onReading(mac, -50, ++now * 1000)) alerted = true }
        assertTrue(alerted)
    }

    @Test fun forgottenDeviceCountsAsNewSighting() {
        val t = ApproachTracker(forgetMs = 60_000)
        t.onReading(mac, -60, 0)
        assertFalse(t.onReading(mac, -60, 30_000))
        assertTrue(t.onReading(mac, -60, 200_000))
    }

    @Test fun devicesAreIndependent() {
        val t = ApproachTracker()
        assertTrue(t.onReading("00:00:00:00:00:01", -60, 0))
        assertTrue(t.onReading("00:00:00:00:00:02", -60, 0))
    }

    @Test fun trendDetectsDirection() {
        fun h(vararg r: Int) = r.mapIndexed { i, v -> Reading(i * 1000L, v) }
        assertEquals(Trend.APPROACHING, trendOf(h(-80, -80, -80, -70, -68, -66)))
        assertEquals(Trend.LEAVING, trendOf(h(-60, -60, -60, -72, -74, -76)))
        assertEquals(Trend.STEADY, trendOf(h(-60, -61, -60, -60, -61, -60)))
        assertEquals(Trend.STEADY, trendOf(h(-60, -50)))
    }

    @Test fun channelMapping() {
        assertEquals(1, channelFor(2412))
        assertEquals(6, channelFor(2437))
        assertEquals(13, channelFor(2472))
        assertEquals(14, channelFor(2484))
        assertEquals(36, channelFor(5180))
        assertEquals(149, channelFor(5745))
        assertEquals(1, channelFor(5955))
        assertEquals(0, channelFor(0))
    }

    @Test fun radialFracClamps() {
        assertEquals(0f, radialFrac(-20), 0.0001f)
        assertEquals(1f, radialFrac(-120), 0.0001f)
        assertEquals(0.5f, radialFrac(-65), 0.0001f)
    }
}
