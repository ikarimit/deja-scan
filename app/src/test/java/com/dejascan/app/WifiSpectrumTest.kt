package com.dejascan.app

import com.dejascan.app.data.Device
import com.dejascan.app.scan.ChannelWidthHelper
import com.dejascan.app.scan.Sighting
import com.dejascan.app.scan.SightingBus
import com.dejascan.app.scan.WifiBand
import com.dejascan.app.scan.WifiStandardHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiSpectrumTest {

    @Test
    fun testWifiBandClassification() {
        // 2.4 GHz band
        assertEquals(WifiBand.BAND_2_4_GHZ, WifiBand.forFrequency(2412))
        assertEquals(WifiBand.BAND_2_4_GHZ, WifiBand.forFrequency(2437))
        assertEquals(WifiBand.BAND_2_4_GHZ, WifiBand.forFrequency(2472))
        assertEquals(WifiBand.BAND_2_4_GHZ, WifiBand.forFrequency(2484))

        // 5 GHz band
        assertEquals(WifiBand.BAND_5_GHZ, WifiBand.forFrequency(5180))
        assertEquals(WifiBand.BAND_5_GHZ, WifiBand.forFrequency(5320))
        assertEquals(WifiBand.BAND_5_GHZ, WifiBand.forFrequency(5500))
        assertEquals(WifiBand.BAND_5_GHZ, WifiBand.forFrequency(5745))
        assertEquals(WifiBand.BAND_5_GHZ, WifiBand.forFrequency(5885))

        // 6 GHz band (Wi-Fi 6E & 7)
        assertEquals(WifiBand.BAND_6_GHZ, WifiBand.forFrequency(5955))
        assertEquals(WifiBand.BAND_6_GHZ, WifiBand.forFrequency(6135))
        assertEquals(WifiBand.BAND_6_GHZ, WifiBand.forFrequency(6515))
        assertEquals(WifiBand.BAND_6_GHZ, WifiBand.forFrequency(6875))
        assertEquals(WifiBand.BAND_6_GHZ, WifiBand.forFrequency(7115))

        // Unknown / out of range
        assertEquals(WifiBand.UNKNOWN, WifiBand.forFrequency(0))
        assertEquals(WifiBand.UNKNOWN, WifiBand.forFrequency(900))
        assertEquals(WifiBand.UNKNOWN, WifiBand.forFrequency(3500))
        assertEquals(WifiBand.UNKNOWN, WifiBand.forFrequency(8000))
    }

    @Test
    fun testWifiStandardHelperLabelsAndBadges() {
        // Wi-Fi 7 (802.11be)
        assertEquals("Wi-Fi 7 (802.11be)", WifiStandardHelper.getStandardName(WifiStandardHelper.STANDARD_11BE, 5180))
        assertEquals("Wi-Fi 7 (802.11be)", WifiStandardHelper.getStandardName(WifiStandardHelper.STANDARD_11BE, 6135))
        assertEquals("Wi-Fi 7", WifiStandardHelper.getStandardBadge(WifiStandardHelper.STANDARD_11BE, 6135))

        // Wi-Fi 6 on 2.4/5 GHz
        assertEquals("Wi-Fi 6 (802.11ax)", WifiStandardHelper.getStandardName(WifiStandardHelper.STANDARD_11AX, 2412))
        assertEquals("Wi-Fi 6 (802.11ax)", WifiStandardHelper.getStandardName(WifiStandardHelper.STANDARD_11AX, 5180))
        assertEquals("Wi-Fi 6", WifiStandardHelper.getStandardBadge(WifiStandardHelper.STANDARD_11AX, 5180))

        // Wi-Fi 6E on 6 GHz (5925+ MHz)
        assertEquals("Wi-Fi 6E (802.11ax 6 GHz)", WifiStandardHelper.getStandardName(WifiStandardHelper.STANDARD_11AX, 5955))
        assertEquals("Wi-Fi 6E (802.11ax 6 GHz)", WifiStandardHelper.getStandardName(WifiStandardHelper.STANDARD_11AX, 6515))
        assertEquals("Wi-Fi 6E", WifiStandardHelper.getStandardBadge(WifiStandardHelper.STANDARD_11AX, 5955))

        // Wi-Fi 5 (802.11ac)
        assertEquals("Wi-Fi 5 (802.11ac)", WifiStandardHelper.getStandardName(WifiStandardHelper.STANDARD_11AC, 5180))
        assertEquals("Wi-Fi 5", WifiStandardHelper.getStandardBadge(WifiStandardHelper.STANDARD_11AC, 5180))

        // Wi-Fi 4 (802.11n)
        assertEquals("Wi-Fi 4 (802.11n)", WifiStandardHelper.getStandardName(WifiStandardHelper.STANDARD_11N, 2412))
        assertEquals("Wi-Fi 4", WifiStandardHelper.getStandardBadge(WifiStandardHelper.STANDARD_11N, 2412))

        // Legacy (802.11a/b/g)
        assertEquals("Legacy (802.11a/b/g)", WifiStandardHelper.getStandardName(WifiStandardHelper.STANDARD_LEGACY, 2412))
        assertEquals("Legacy", WifiStandardHelper.getStandardBadge(WifiStandardHelper.STANDARD_LEGACY, 2412))
    }

    @Test
    fun testWifiStandardInference() {
        // Unknown standard in 6 GHz is inferred as 11AX (Wi-Fi 6E)
        val inferred6g = WifiStandardHelper.resolveStandard(WifiStandardHelper.STANDARD_UNKNOWN, 5955, ChannelWidthHelper.WIDTH_20MHZ)
        assertEquals(WifiStandardHelper.STANDARD_11AX, inferred6g)

        // Unknown standard with 320 MHz width is inferred as 11BE (Wi-Fi 7)
        val inferred320m = WifiStandardHelper.resolveStandard(WifiStandardHelper.STANDARD_UNKNOWN, 6135, ChannelWidthHelper.WIDTH_320MHZ)
        assertEquals(WifiStandardHelper.STANDARD_11BE, inferred320m)

        // Unknown standard with 80 MHz width in 5 GHz is inferred as 11AC (Wi-Fi 5)
        val inferred80m = WifiStandardHelper.resolveStandard(WifiStandardHelper.STANDARD_UNKNOWN, 5180, ChannelWidthHelper.WIDTH_80MHZ)
        assertEquals(WifiStandardHelper.STANDARD_11AC, inferred80m)

        // Unknown standard with 40 MHz width is inferred as 11N (Wi-Fi 4)
        val inferred40m = WifiStandardHelper.resolveStandard(WifiStandardHelper.STANDARD_UNKNOWN, 2412, ChannelWidthHelper.WIDTH_40MHZ)
        assertEquals(WifiStandardHelper.STANDARD_11N, inferred40m)

        // Explicit standard is preserved
        val explicit = WifiStandardHelper.resolveStandard(WifiStandardHelper.STANDARD_11BE, 5180, ChannelWidthHelper.WIDTH_80MHZ)
        assertEquals(WifiStandardHelper.STANDARD_11BE, explicit)
    }

    @Test
    fun testChannelWidthCalculations() {
        // Width in MHz
        assertEquals(20, ChannelWidthHelper.getWidthMhz(ChannelWidthHelper.WIDTH_20MHZ))
        assertEquals(40, ChannelWidthHelper.getWidthMhz(ChannelWidthHelper.WIDTH_40MHZ))
        assertEquals(80, ChannelWidthHelper.getWidthMhz(ChannelWidthHelper.WIDTH_80MHZ))
        assertEquals(160, ChannelWidthHelper.getWidthMhz(ChannelWidthHelper.WIDTH_160MHZ))
        assertEquals(160, ChannelWidthHelper.getWidthMhz(ChannelWidthHelper.WIDTH_80PLUS80MHZ))
        assertEquals(320, ChannelWidthHelper.getWidthMhz(ChannelWidthHelper.WIDTH_320MHZ))

        // Labels
        assertEquals("20 MHz", ChannelWidthHelper.getWidthLabel(ChannelWidthHelper.WIDTH_20MHZ))
        assertEquals("40 MHz", ChannelWidthHelper.getWidthLabel(ChannelWidthHelper.WIDTH_40MHZ))
        assertEquals("80 MHz", ChannelWidthHelper.getWidthLabel(ChannelWidthHelper.WIDTH_80MHZ))
        assertEquals("160 MHz", ChannelWidthHelper.getWidthLabel(ChannelWidthHelper.WIDTH_160MHZ))
        assertEquals("80+80 MHz", ChannelWidthHelper.getWidthLabel(ChannelWidthHelper.WIDTH_80PLUS80MHZ))
        assertEquals("320 MHz", ChannelWidthHelper.getWidthLabel(ChannelWidthHelper.WIDTH_320MHZ))

        // Frequency Span: 20 MHz channel (Ch 1 @ 2412 MHz)
        val span20 = ChannelWidthHelper.getFrequencySpan(2412, ChannelWidthHelper.WIDTH_20MHZ, 0)
        assertEquals(2402, span20.first)
        assertEquals(2422, span20.last)

        // Frequency Span: 80 MHz channel (Ch 36 @ 5180 MHz, centerFreq0 = 5210 MHz)
        val span80 = ChannelWidthHelper.getFrequencySpan(5180, ChannelWidthHelper.WIDTH_80MHZ, 5210)
        assertEquals(5170, span80.first)
        assertEquals(5250, span80.last)

        // Frequency Span: 160 MHz channel (Ch 36 @ 5180 MHz, centerFreq0 = 5250 MHz)
        val span160 = ChannelWidthHelper.getFrequencySpan(5180, ChannelWidthHelper.WIDTH_160MHZ, 5250)
        assertEquals(5170, span160.first)
        assertEquals(5330, span160.last)

        // Frequency Span: 320 MHz Wi-Fi 7 channel (Ch 1 @ 5955 MHz, centerFreq0 = 6105 MHz)
        val span320 = ChannelWidthHelper.getFrequencySpan(5955, ChannelWidthHelper.WIDTH_320MHZ, 6105)
        assertEquals(5945, span320.first)
        assertEquals(6265, span320.last)
    }

    @Test
    fun testChannelWithBandFormatting() {
        assertEquals("Ch 1 (2.4 GHz)", ChannelWidthHelper.channelWithBand(2412))
        assertEquals("Ch 6 (2.4 GHz)", ChannelWidthHelper.channelWithBand(2437))
        assertEquals("Ch 14 (2.4 GHz)", ChannelWidthHelper.channelWithBand(2484))
        assertEquals("Ch 36 (5 GHz)", ChannelWidthHelper.channelWithBand(5180))
        assertEquals("Ch 149 (5 GHz)", ChannelWidthHelper.channelWithBand(5745))
        assertEquals("Ch 1 (6 GHz)", ChannelWidthHelper.channelWithBand(5955))
        assertEquals("Ch 37 (6 GHz)", ChannelWidthHelper.channelWithBand(6135))
    }

    @Test
    fun testSightingModelConvenienceExtensions() {
        val s = Sighting(
            mac = "11:22:33:44:55:66",
            name = "Test-WiFi-7-AP",
            type = "W",
            randomised = false,
            rssi = -55,
            time = 1000L,
            firstSeen = 1000L,
            best = -55,
            count = 1,
            history = emptyList(),
            freq = 5955,
            adv = emptyList(),
            wigleLastTime = 0L,
            lat = 0.0,
            lon = 0.0,
            channelWidth = ChannelWidthHelper.WIDTH_320MHZ,
            centerFreq0 = 6105,
            wifiStandard = WifiStandardHelper.STANDARD_11BE,
            standardLabel = "Wi-Fi 7 (802.11be)",
        )

        assertEquals(WifiBand.BAND_6_GHZ, s.wifiBand)
        assertEquals(320, s.bandwidthMhz)
        assertEquals("320 MHz", s.bandwidthLabel)
        assertEquals(6105, s.centerFreq)
        assertEquals(5945..6265, s.frequencySpan)
        assertEquals("Wi-Fi 7", s.standardBadge)
    }

    @Test
    fun testSightingBusWifiReport() {
        val dev = Device(
            mac = "AA:BB:CC:DD:EE:FF",
            name = "My-6E-Router",
            type = "W",
            lastTime = 0L,
            lat = 0.0,
            lon = 0.0,
            randomised = false,
            watched = true,
        )

        SightingBus.report(
            dev = dev,
            liveName = "My-6E-Router",
            rssi = -48,
            freq = 6135,
            adv = listOf("SSID: My-6E-Router"),
            now = 2000L,
            channelWidth = ChannelWidthHelper.WIDTH_160MHZ,
            centerFreq0 = 6140,
            wifiStandard = WifiStandardHelper.STANDARD_11AX,
            standardLabel = "Wi-Fi 6E (802.11ax 6 GHz)",
        )

        val reported = SightingBus.sightings.value[dev.mac]
        assertTrue(reported != null)
        assertEquals(6135, reported!!.freq)
        assertEquals(WifiBand.BAND_6_GHZ, reported.wifiBand)
        assertEquals(160, reported.bandwidthMhz)
        assertEquals("160 MHz", reported.bandwidthLabel)
        assertEquals("Wi-Fi 6E", reported.standardBadge)
        assertEquals(WifiStandardHelper.STANDARD_11AX, reported.wifiStandard)
        assertEquals("Wi-Fi 6E (802.11ax 6 GHz)", reported.standardLabel)
    }
}
