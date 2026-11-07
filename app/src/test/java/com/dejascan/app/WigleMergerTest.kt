package com.dejascan.app

import com.dejascan.app.data.WigleMerger
import com.dejascan.app.scan.SecuritySuite
import com.dejascan.app.scan.Sighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WigleMergerTest {

    @Test
    fun preparesNetworkRecordFromSightingWithNoSights() {
        val s = Sighting(
            mac = "aa:bb:cc:dd:ee:ff",
            name = "TestNetwork",
            type = "W",
            randomised = false,
            rssi = -65,
            time = 1700000000000L,
            firstSeen = 1700000000000L,
            best = -60,
            count = 5,
            history = emptyList(),
            freq = 2412,
            adv = listOf("Raw Caps: [WPA2-PSK-CCMP][ESS]"),
            wigleLastTime = 0L,
            lat = 37.7749,
            lon = -122.4194,
            watched = false,
            inDatabase = false,
            securitySuite = SecuritySuite.WPA2,
        )

        val rec = WigleMerger.prepareNetworkRecord(s, emptyList())
        assertEquals("aa:bb:cc:dd:ee:ff", rec.bssid)
        assertEquals("TestNetwork", rec.ssid)
        assertEquals(2412, rec.frequency)
        assertEquals("[WPA2-PSK-CCMP][ESS]", rec.capabilities)
        assertEquals(1700000000000L, rec.lastTime)
        assertEquals(37.7749, rec.lastLat, 0.0001)
        assertEquals(-122.4194, rec.lastLon, 0.0001)
        assertEquals("W", rec.type)
        assertEquals(-60, rec.bestLevel)
        assertEquals(37.7749, rec.bestLat, 0.0001)
        assertEquals(-122.4194, rec.bestLon, 0.0001)
    }

    @Test
    fun preparesNetworkRecordPicksBestAndLatestSights() {
        val s = Sighting(
            mac = "aa:bb:cc:dd:ee:ff",
            name = "AA:BB:CC:DD:EE:FF", // name equals MAC -> SSID should be empty
            type = "W",
            randomised = false,
            rssi = -75,
            time = 1700000000000L,
            firstSeen = 1700000000000L,
            best = -70,
            count = 1,
            history = emptyList(),
            freq = 5180,
            adv = emptyList(), // no raw caps -> defaults to [ESS] for Wi-Fi
            wigleLastTime = 0L,
            lat = 0.0,
            lon = 0.0,
            watched = false,
            inDatabase = false,
            securitySuite = SecuritySuite.OPEN,
        )

        val sights = listOf(
            WigleMerger.SightRecord("aa:bb:cc:dd:ee:ff", 1700000001000L, 37.7750, -122.4190, 5f, -80),
            WigleMerger.SightRecord("aa:bb:cc:dd:ee:ff", 1700000002000L, 37.7755, -122.4185, 3f, -50), // best RSSI
            WigleMerger.SightRecord("aa:bb:cc:dd:ee:ff", 1700000005000L, 37.7760, -122.4180, 4f, -65), // latest time
        )

        val rec = WigleMerger.prepareNetworkRecord(s, sights)
        assertEquals("aa:bb:cc:dd:ee:ff", rec.bssid)
        assertEquals("", rec.ssid) // empty when name is mac
        assertEquals("[ESS]", rec.capabilities)
        assertEquals(37.7760, rec.lastLat, 0.0001) // from latest sight
        assertEquals(-122.4180, rec.lastLon, 0.0001)
        assertEquals(-50, rec.bestLevel) // from best sight
        assertEquals(37.7755, rec.bestLat, 0.0001) // from best sight
        assertEquals(-122.4185, rec.bestLon, 0.0001)
    }

    @Test
    fun preparesCombinedSightsMergesAndDeduplicates() {
        val s1 = Sighting(
            mac = "11:22:33:44:55:66",
            name = "Net1",
            type = "W",
            randomised = false,
            rssi = -60,
            time = 1000L,
            firstSeen = 1000L,
            best = -60,
            count = 1,
            history = emptyList(),
            freq = 2412,
            adv = emptyList(),
            wigleLastTime = 0L,
            lat = 40.7128,
            lon = -74.0060,
        )
        // Sighting with 0,0 location shouldn't be added to combined sights
        val s2 = Sighting(
            mac = "22:33:44:55:66:77",
            name = "Net2",
            type = "W",
            randomised = false,
            rssi = -70,
            time = 2000L,
            firstSeen = 2000L,
            best = -70,
            count = 1,
            history = emptyList(),
            freq = 2412,
            adv = emptyList(),
            wigleLastTime = 0L,
            lat = 0.0,
            lon = 0.0,
        )

        val existingSights = listOf(
            WigleMerger.SightRecord("11:22:33:44:55:66", 1000L, 40.7128, -74.0060, 2f, -60),
            WigleMerger.SightRecord("33:44:55:66:77:88", 3000L, 40.7130, -74.0050, 4f, -55),
        )

        val combined = WigleMerger.prepareCombinedSights(listOf(s1, s2), existingSights)
        assertEquals(2, combined.size)

        // Now add a sighting with new time
        val s3 = s1.copy(time = 4000L, lat = 40.7135, lon = -74.0045)
        val combinedWithNew = WigleMerger.prepareCombinedSights(listOf(s3), existingSights)
        assertEquals(3, combinedWithNew.size)
        assertTrue(combinedWithNew.any { it.time == 4000L && it.lat == 40.7135 })
    }
}
