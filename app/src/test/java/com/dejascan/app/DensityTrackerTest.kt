package com.dejascan.app

import com.dejascan.app.scan.DensityTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DensityTrackerTest {

    @Test
    fun recordWifiApIncrementsApCount() {
        val tracker = DensityTracker()
        val t = 60_000L * 100 // 100th minute

        tracker.record("11:22:33:44:55:66", "W", inDatabase = false, now = t)
        tracker.record("aa:bb:cc:dd:ee:ff", "W", inDatabase = true, now = t)

        val points = tracker.rollingPoints(now = t, windowMinutes = 1)
        assertEquals(1, points.size)
        val p = points[0]
        assertEquals(2, p.apCount)
        assertEquals(1, p.newApCount) // only 11:22:33:44:55:66 is new
    }

    @Test
    fun multipleSightingsInSameMinuteCountOnce() {
        val tracker = DensityTracker()
        val t = 60_000L * 50

        // Hear the same AP multiple times during the same minute
        repeat(5) {
            tracker.record("11:22:33:44:55:66", "W", inDatabase = false, now = t + it * 5000L)
        }

        val points = tracker.rollingPoints(now = t + 25_000L, windowMinutes = 1)
        assertEquals(1, points.size)
        assertEquals(1, points[0].apCount)
        assertEquals(1, points[0].newApCount)
    }

    @Test
    fun bluetoothDevicesAreIgnoredForApDensity() {
        val tracker = DensityTracker()
        val t = 60_000L * 10

        tracker.record("00:11:22:33:44:55", "B", inDatabase = false, now = t)
        tracker.record("00:11:22:33:44:56", "E", inDatabase = false, now = t)

        val points = tracker.rollingPoints(now = t, windowMinutes = 1)
        assertEquals(1, points.size)
        assertEquals(0, points[0].apCount)
        assertEquals(0, points[0].newApCount)
    }

    @Test
    fun rollingPointsProducesContinuousWindow() {
        val tracker = DensityTracker()
        val t = 60_000L * 100 // Minute 100

        // Put readings at minute 95 and minute 100
        tracker.record("11:11:11:11:11:11", "W", inDatabase = false, now = 60_000L * 95)
        tracker.record("22:22:22:22:22:22", "W", inDatabase = true, now = 60_000L * 100)

        val window = tracker.rollingPoints(now = t, windowMinutes = 10)
        assertEquals(10, window.size) // Minutes 91 through 100

        assertEquals(91L, window[0].minute)
        assertEquals(0, window[0].apCount)

        val m95 = window.first { it.minute == 95L }
        assertEquals(1, m95.apCount)
        assertEquals(1, m95.newApCount)

        val m100 = window.first { it.minute == 100L }
        assertEquals(1, m100.apCount)
        assertEquals(0, m100.newApCount)
    }

    @Test
    fun markKnownUpdatesNewCountAcrossBuckets() {
        val tracker = DensityTracker()
        val mac = "11:22:33:44:55:66"
        val t = 60_000L * 20

        tracker.record(mac, "W", inDatabase = false, now = t)
        var points = tracker.rollingPoints(now = t, windowMinutes = 1)
        assertEquals(1, points[0].newApCount)

        tracker.markKnown(setOf(mac))
        points = tracker.rollingPoints(now = t, windowMinutes = 1)
        assertEquals(1, points[0].apCount)
        assertEquals(0, points[0].newApCount)
    }

    @Test
    fun clearResetsAllBuckets() {
        val tracker = DensityTracker()
        val t = 60_000L * 10
        tracker.record("ap1", "W", false, t)
        tracker.clear()
        val points = tracker.rollingPoints(now = t, windowMinutes = 5)
        assertTrue(points.all { it.apCount == 0 })
    }
}
