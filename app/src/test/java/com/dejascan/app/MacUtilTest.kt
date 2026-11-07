package com.dejascan.app

import com.dejascan.app.scan.MacUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacUtilTest {
    @Test fun normalizeLowercasesAndAcceptsDashes() {
        assertEquals("aa:bb:cc:dd:ee:ff", MacUtil.normalize("AA:BB:CC:DD:EE:FF"))
        assertEquals("aa:bb:cc:dd:ee:ff", MacUtil.normalize(" aa-bb-cc-dd-ee-ff "))
    }

    @Test fun normalizeRejectsGarbage() {
        assertNull(MacUtil.normalize(null))
        assertNull(MacUtil.normalize(""))
        assertNull(MacUtil.normalize("aa:bb:cc"))
        assertNull(MacUtil.normalize("zz:bb:cc:dd:ee:ff"))
        assertNull(MacUtil.normalize("310_260_1234_5678")) // WiGLE cell-style id
    }

    @Test fun randomisedBit() {
        assertTrue(MacUtil.isRandomised("02:00:00:00:00:01"))
        assertTrue(MacUtil.isRandomised("fa:bb:cc:dd:ee:ff"))
        assertFalse(MacUtil.isRandomised("00:11:22:33:44:55"))
        assertFalse(MacUtil.isRandomised("f8:bb:cc:dd:ee:ff"))
    }

    @Test fun proximityBuckets() {
        assertEquals("Very close", MacUtil.proximity(-40))
        assertEquals("Near", MacUtil.proximity(-60))
        assertEquals("Far", MacUtil.proximity(-80))
        assertEquals("Faint", MacUtil.proximity(-95))
    }

    @Test fun cleanAirTextStripsControlsBidiAndNewlines() {
        assertEquals("", MacUtil.cleanAirText(null))
        assertEquals("", MacUtil.cleanAirText("<unknown ssid>"))
        assertEquals("Cafe Net", MacUtil.cleanAirText("  Cafe\n\r\tNet  "))
        assertEquals("CleanSSID", MacUtil.cleanAirText("Clean\u0000\u001B\u007F\u202ESSID\uFFFD"))
        assertEquals(32, MacUtil.cleanAirText("A".repeat(80), maxLen = 32).length)
    }
}
