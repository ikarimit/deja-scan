package com.dejascan.app

import com.dejascan.app.data.Device
import com.dejascan.app.scan.ChannelWidthHelper
import com.dejascan.app.scan.DetectionCategory
import com.dejascan.app.scan.DetectionClassifier
import com.dejascan.app.scan.SecuritySuite
import com.dejascan.app.scan.WifiStandardHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionClassifierTest {

    @Test
    fun testHeuristicAuditToolsDetected() {
        assertEquals("Possible audit tool (heuristic)", DetectionCategory.AUDIT_TOOL.label)
        assertEquals("AUDIT", DetectionCategory.AUDIT_TOOL.shortTag)

        val pwnagotchi = DetectionClassifier.classifyRaw(
            mac = "DE:AD:BE:EF:DE:AD",
            name = "unit-01",
            type = "W",
            randomised = true,
        )
        assertEquals(DetectionCategory.AUDIT_TOOL, pwnagotchi.category)
        assertTrue(pwnagotchi.reason.contains("heuristic"))

        val pwnName = DetectionClassifier.classifyRaw(
            mac = "B8:27:EB:11:22:33",
            name = "pwnagotchi-alpha",
            type = "W",
            randomised = false,
        )
        assertEquals(DetectionCategory.AUDIT_TOOL, pwnName.category)

        val pineapple = DetectionClassifier.classifyRaw(
            mac = "00:13:37:A1:B2:C3",
            name = "FreeAirportWiFi",
            type = "W",
            randomised = false,
        )
        assertEquals(DetectionCategory.AUDIT_TOOL, pineapple.category)
        assertTrue(pineapple.reason.contains("assigned OUI"))

        val flipperName = DetectionClassifier.classifyRaw(
            mac = "80:E1:26:01:02:03",
            name = "Flipper Dolphin",
            type = "E",
            randomised = false,
        )
        assertEquals(DetectionCategory.AUDIT_TOOL, flipperName.category)

        // Ordinary Wi-Fi SSID starting with "Flipper" is not flagged as a BLE Flipper Zero
        val ordinaryWifiFlipper = DetectionClassifier.classifyRaw(
            mac = "00:22:33:44:55:99",
            name = "Flipper's Diner WiFi",
            type = "W",
            randomised = false,
            securitySuite = SecuritySuite.WPA2,
            inDatabase = true,
            hasDatabase = true,
        )
        assertEquals(DetectionCategory.NORMAL, ordinaryWifiFlipper.category)

        val flipperUuid = DetectionClassifier.classifyRaw(
            mac = "80:E1:26:04:05:06",
            name = "BLE-Dev",
            type = "E",
            randomised = false,
            adv = listOf("Services: 0x3081"),
        )
        assertEquals(DetectionCategory.AUDIT_TOOL, flipperUuid.category)

        val marauder = DetectionClassifier.classifyRaw(
            mac = "24:6F:28:AA:BB:CC",
            name = "ESP32_Marauder",
            type = "W",
            randomised = false,
        )
        assertEquals(DetectionCategory.AUDIT_TOOL, marauder.category)
    }

    @Test
    fun testOutdatedWepDetected() {
        val liveWep = DetectionClassifier.classifyRaw(
            mac = "00:11:22:33:44:55",
            name = "LegacyShop",
            type = "W",
            randomised = false,
            securitySuite = SecuritySuite.WEP,
        )
        assertEquals(DetectionCategory.WEP, liveWep.category)

        val wigleWep = DetectionClassifier.classifyDevice(
            d = Device(
                mac = "00:11:22:33:44:66",
                name = "WarehouseWEP",
                type = "W",
                lastTime = 1000L,
                lat = 40.0,
                lon = -74.0,
                randomised = false,
                watched = false,
                freq = 2437,
                caps = "[WEP][ESS]",
            ),
            vendor = null,
        )
        assertEquals(DetectionCategory.WEP, wigleWep.category)
    }

    @Test
    fun testRareWifiAndBluetoothDetections() {
        val wifi6e = DetectionClassifier.classifyRaw(
            mac = "00:1A:2B:3C:4D:5E",
            name = "TriBand-6E",
            type = "W",
            randomised = false,
            freq = 5955,
            channelWidth = ChannelWidthHelper.WIDTH_160MHZ,
            securitySuite = SecuritySuite.WPA3,
        )
        assertEquals(DetectionCategory.RARE, wifi6e.category)

        val wifi7 = DetectionClassifier.classifyRaw(
            mac = "00:1A:2B:3C:4D:5F",
            name = "BE-Router",
            type = "W",
            randomised = false,
            freq = 5180,
            channelWidth = ChannelWidthHelper.WIDTH_320MHZ,
            wifiStandard = WifiStandardHelper.STANDARD_11BE,
            securitySuite = SecuritySuite.WPA3,
        )
        assertEquals(DetectionCategory.RARE, wifi7.category)

        val ch14 = DetectionClassifier.classifyRaw(
            mac = "00:1A:2B:3C:4D:60",
            name = "JP-2484",
            type = "W",
            randomised = false,
            freq = 2484,
            securitySuite = SecuritySuite.WPA2,
        )
        assertEquals(DetectionCategory.RARE, ch14.category)

        val adhoc = DetectionClassifier.classifyRaw(
            mac = "00:1A:2B:3C:4D:61",
            name = "FieldMesh",
            type = "W",
            randomised = false,
            caps = "[WPA2-PSK-CCMP][IBSS]",
        )
        assertEquals(DetectionCategory.RARE, adhoc.category)

        val medicalBt = DetectionClassifier.classifyRaw(
            mac = "00:1A:2B:3C:4D:62",
            name = "PulseOx",
            type = "B",
            randomised = false,
            adv = listOf("Class: 0x0904"),
        )
        assertEquals(DetectionCategory.RARE, medicalBt.category)

        val rareVendor = DetectionClassifier.classifyRaw(
            mac = "00:90:B8:11:22:33",
            name = "Instrument-AP",
            type = "W",
            randomised = false,
            freq = 5180,
            securitySuite = SecuritySuite.WPA2,
            vendor = "Rohde & Schwarz GmbH",
            vendorPeerCount = 1,
            totalFixedPeers = 15,
        )
        assertEquals(DetectionCategory.RARE, rareVendor.category)
    }

    @Test
    fun testRandomizedMacDetection() {
        val randBle = DetectionClassifier.classifyRaw(
            mac = "5A:12:34:56:78:9A",
            name = "",
            type = "E",
            randomised = true,
        )
        assertEquals(DetectionCategory.RANDOMIZED, randBle.category)

        val randHotspot = DetectionClassifier.classifyRaw(
            mac = "02:AA:BB:CC:DD:EE",
            name = "Pixel Hotspot",
            type = "W",
            randomised = true,
            freq = 5745,
            securitySuite = SecuritySuite.WPA3,
        )
        assertEquals(DetectionCategory.RANDOMIZED, randHotspot.category)
    }

    @Test
    fun testUncommonDetections() {
        val owe = DetectionClassifier.classifyRaw(
            mac = "00:22:33:44:55:66",
            name = "Cafe-OWE",
            type = "W",
            randomised = false,
            freq = 5180,
            securitySuite = SecuritySuite.OWE,
        )
        assertEquals(DetectionCategory.UNCOMMON, owe.category)

        val wide160 = DetectionClassifier.classifyRaw(
            mac = "00:22:33:44:55:67",
            name = "Fast5G",
            type = "W",
            randomised = false,
            freq = 5180,
            channelWidth = ChannelWidthHelper.WIDTH_160MHZ,
            securitySuite = SecuritySuite.WPA2,
        )
        assertEquals(DetectionCategory.UNCOMMON, wide160.category)

        val newToDb = DetectionClassifier.classifyRaw(
            mac = "00:22:33:44:55:68",
            name = "BrandNewAP",
            type = "W",
            randomised = false,
            freq = 2412,
            securitySuite = SecuritySuite.WPA2,
            inDatabase = false,
            hasDatabase = true,
        )
        assertEquals(DetectionCategory.UNCOMMON, newToDb.category)
        assertTrue(newToDb.reason.contains("unseen in WiGLE DB"))
    }

    @Test
    fun testNormalNetworksAndDevices() {
        val normalWifi = DetectionClassifier.classifyRaw(
            mac = "00:22:33:44:55:69",
            name = "HomeWPA2",
            type = "W",
            randomised = false,
            freq = 5180,
            channelWidth = ChannelWidthHelper.WIDTH_80MHZ,
            securitySuite = SecuritySuite.WPA2,
            inDatabase = true,
            hasDatabase = true,
        )
        assertEquals(DetectionCategory.NORMAL, normalWifi.category)

        val normalBle = DetectionClassifier.classifyRaw(
            mac = "00:22:33:44:55:70",
            name = "Headphones",
            type = "E",
            randomised = false,
            inDatabase = true,
            hasDatabase = true,
        )
        assertEquals(DetectionCategory.NORMAL, normalBle.category)
    }
}
