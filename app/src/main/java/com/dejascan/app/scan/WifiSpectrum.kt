package com.dejascan.app.scan

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Hardware / driver capabilities of the Android device for Wi-Fi scanning.
 */
data class WifiCapabilities(
    val is24GhzSupported: Boolean = true,
    val is5GhzSupported: Boolean = false,
    val is6GhzSupported: Boolean = false,
    val isWifi6Supported: Boolean = false,
    val isWifi7Supported: Boolean = false,
) {
    companion object {
        fun detect(context: Context): WifiCapabilities {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return WifiCapabilities()
            val is5G = try { wm.is5GHzBandSupported } catch (_: Throwable) { false }
            val is6G = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try { wm.is6GHzBandSupported } catch (_: Throwable) { false }
            } else false
            val isWifi6 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try { wm.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX) } catch (_: Throwable) { false }
            } else false
            val isWifi7 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try { wm.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11BE) } catch (_: Throwable) { false }
            } else false
            return WifiCapabilities(
                is24GhzSupported = true,
                is5GhzSupported = is5G,
                is6GhzSupported = is6G,
                isWifi6Supported = isWifi6,
                isWifi7Supported = isWifi7,
            )
        }
    }
}

/**
 * Wi-Fi frequency band categories.
 */
enum class WifiBand(
    val label: String,
    val shortLabel: String,
    val freqRange: IntRange,
    val displayRange: IntRange,
) {
    BAND_2_4_GHZ("2.4 GHz", "2.4G", 2400..2499, 2400..2500),
    BAND_5_GHZ("5 GHz", "5G", 5000..5924, 5160..5900),
    BAND_6_GHZ("6 GHz", "6G", 5925..7125, 5925..7125),
    UNKNOWN("Unknown", "?", 0..0, 0..0);

    companion object {
        fun forFrequency(freqMhz: Int): WifiBand = when {
            freqMhz in 2400..2499 -> BAND_2_4_GHZ
            freqMhz in 5000..5924 -> BAND_5_GHZ
            freqMhz in 5925..7125 -> BAND_6_GHZ
            else -> UNKNOWN
        }
    }
}

/**
 * Translates Wi-Fi standards (IEEE 802.11 standards up to Wi-Fi 7).
 */
object WifiStandardHelper {
    const val STANDARD_UNKNOWN = 0
    const val STANDARD_LEGACY = 1
    const val STANDARD_11N = 4
    const val STANDARD_11AC = 5
    const val STANDARD_11AX = 6
    const val STANDARD_11AD = 7
    const val STANDARD_11BE = 8

    fun getStandardName(standard: Int, freqMhz: Int): String = when (standard) {
        STANDARD_11BE -> "Wi-Fi 7 (802.11be)"
        STANDARD_11AX -> if (freqMhz >= 5925) "Wi-Fi 6E (802.11ax 6 GHz)" else "Wi-Fi 6 (802.11ax)"
        STANDARD_11AC -> "Wi-Fi 5 (802.11ac)"
        STANDARD_11N -> "Wi-Fi 4 (802.11n)"
        STANDARD_11AD -> "WiGig (802.11ad)"
        STANDARD_LEGACY -> "Legacy (802.11a/b/g)"
        else -> if (freqMhz in 5925..7125) "Wi-Fi 6E / 7" else "Unknown standard"
    }

    fun getStandardBadge(standard: Int, freqMhz: Int): String = when (standard) {
        STANDARD_11BE -> "Wi-Fi 7"
        STANDARD_11AX -> if (freqMhz >= 5925) "Wi-Fi 6E" else "Wi-Fi 6"
        STANDARD_11AC -> "Wi-Fi 5"
        STANDARD_11N -> "Wi-Fi 4"
        STANDARD_11AD -> "WiGig"
        STANDARD_LEGACY -> "Legacy"
        else -> if (freqMhz in 5925..7125) "6E/7" else ""
    }

    fun resolveStandard(standard: Int, freqMhz: Int, channelWidth: Int, capabilities: String = ""): Int {
        if (standard != STANDARD_UNKNOWN) return standard
        if (channelWidth == ChannelWidthHelper.WIDTH_320MHZ) return STANDARD_11BE
        if (freqMhz in 5925..7125) return STANDARD_11AX
        if (channelWidth in listOf(ChannelWidthHelper.WIDTH_80MHZ, ChannelWidthHelper.WIDTH_160MHZ, ChannelWidthHelper.WIDTH_80PLUS80MHZ)) {
            return STANDARD_11AC
        }
        if (channelWidth == ChannelWidthHelper.WIDTH_40MHZ) return STANDARD_11N
        return STANDARD_UNKNOWN
    }
}

/**
 * Handles Wi-Fi channel bandwidths from 20 MHz up to 320 MHz (Wi-Fi 7).
 */
object ChannelWidthHelper {
    const val WIDTH_20MHZ = 0
    const val WIDTH_40MHZ = 1
    const val WIDTH_80MHZ = 2
    const val WIDTH_160MHZ = 3
    const val WIDTH_80PLUS80MHZ = 4
    const val WIDTH_320MHZ = 5

    fun getWidthMhz(channelWidth: Int): Int = when (channelWidth) {
        WIDTH_20MHZ -> 20
        WIDTH_40MHZ -> 40
        WIDTH_80MHZ -> 80
        WIDTH_160MHZ -> 160
        WIDTH_80PLUS80MHZ -> 160
        WIDTH_320MHZ -> 320
        else -> 20
    }

    fun getWidthLabel(channelWidth: Int): String = when (channelWidth) {
        WIDTH_20MHZ -> "20 MHz"
        WIDTH_40MHZ -> "40 MHz"
        WIDTH_80MHZ -> "80 MHz"
        WIDTH_160MHZ -> "160 MHz"
        WIDTH_80PLUS80MHZ -> "80+80 MHz"
        WIDTH_320MHZ -> "320 MHz"
        else -> "20 MHz"
    }

    fun getCenterFreq(freqMhz: Int, centerFreq0: Int): Int =
        if (centerFreq0 > 0) centerFreq0 else freqMhz

    fun getFrequencySpan(freqMhz: Int, channelWidth: Int, centerFreq0: Int): IntRange {
        val width = getWidthMhz(channelWidth)
        val center = getCenterFreq(freqMhz, centerFreq0)
        val half = width / 2
        return (center - half)..(center + half)
    }

    fun channelWithBand(freqMhz: Int): String {
        val ch = channelFor(freqMhz)
        val band = WifiBand.forFrequency(freqMhz)
        return when (band) {
            WifiBand.BAND_2_4_GHZ -> "Ch $ch (2.4 GHz)"
            WifiBand.BAND_5_GHZ -> "Ch $ch (5 GHz)"
            WifiBand.BAND_6_GHZ -> "Ch $ch (6 GHz)"
            WifiBand.UNKNOWN -> if (ch > 0) "Ch $ch" else "$freqMhz MHz"
        }
    }
}
