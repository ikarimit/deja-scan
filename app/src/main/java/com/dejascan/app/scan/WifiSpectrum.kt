package com.dejascan.app.scan

/**
 * Wi-Fi frequency band categories.
 */
enum class WifiBand(val label: String, val shortLabel: String) {
    BAND_2_4_GHZ("2.4 GHz", "2.4G"),
    BAND_5_GHZ("5 GHz", "5G"),
    BAND_6_GHZ("6 GHz", "6G"),
    UNKNOWN("Unknown", "?");

    companion object {
        fun forFrequency(freqMhz: Int): WifiBand = when (freqMhz) {
            in 2400..2499 -> BAND_2_4_GHZ
            in 5000..5924 -> BAND_5_GHZ
            in 5925..7125 -> BAND_6_GHZ
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

    fun resolveStandard(standard: Int, freqMhz: Int, channelWidth: Int): Int = when {
        standard != STANDARD_UNKNOWN -> standard
        channelWidth == ChannelWidthHelper.WIDTH_320MHZ -> STANDARD_11BE
        freqMhz in 5925..7125 -> STANDARD_11AX
        channelWidth == ChannelWidthHelper.WIDTH_80MHZ ||
            channelWidth == ChannelWidthHelper.WIDTH_160MHZ ||
            channelWidth == ChannelWidthHelper.WIDTH_80PLUS80MHZ -> STANDARD_11AC
        channelWidth == ChannelWidthHelper.WIDTH_40MHZ -> STANDARD_11N
        else -> STANDARD_UNKNOWN
    }
}

/**
 * Handles Wi-Fi channel numbers and channel bandwidths from 20 MHz up to 320 MHz (Wi-Fi 7).
 */
object ChannelWidthHelper {
    const val WIDTH_20MHZ = 0
    const val WIDTH_40MHZ = 1
    const val WIDTH_80MHZ = 2
    const val WIDTH_160MHZ = 3
    const val WIDTH_80PLUS80MHZ = 4
    const val WIDTH_320MHZ = 5

    fun getWidthMhz(channelWidth: Int): Int = when (channelWidth) {
        WIDTH_40MHZ -> 40
        WIDTH_80MHZ -> 80
        WIDTH_160MHZ, WIDTH_80PLUS80MHZ -> 160
        WIDTH_320MHZ -> 320
        else -> 20
    }

    fun getWidthLabel(channelWidth: Int): String = when (channelWidth) {
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
        val half = getWidthMhz(channelWidth) / 2
        val center = getCenterFreq(freqMhz, centerFreq0)
        return (center - half)..(center + half)
    }

    fun channelWithBand(freqMhz: Int): String {
        val ch = channelFor(freqMhz)
        val band = WifiBand.forFrequency(freqMhz)
        return when {
            band != WifiBand.UNKNOWN -> "Ch $ch (${band.label})"
            ch > 0 -> "Ch $ch"
            else -> "$freqMhz MHz"
        }
    }

    /**
     * Formats channel number together with channel width (when known, i.e. channelWidth >= 0).
     * Example compact: "Ch 36 • 80MHz", "Ch 1 (6G) • 160MHz", or "Ch 6 (2.4G)" when width is unknown.
     */
    fun formatChannelWidth(freqMhz: Int, channelWidth: Int, compact: Boolean = false): String {
        if (freqMhz <= 0) return ""
        val ch = channelFor(freqMhz)
        val band = WifiBand.forFrequency(freqMhz)
        val chPart = when {
            ch <= 0 -> "$freqMhz MHz"
            compact && band == WifiBand.BAND_6_GHZ -> "Ch $ch (6G)"
            compact -> "Ch $ch"
            band != WifiBand.UNKNOWN -> "Ch $ch (${band.label})"
            else -> "Ch $ch"
        }
        return when {
            channelWidth >= 0 -> {
                val widthPart = if (compact) "${getWidthMhz(channelWidth)}MHz" else getWidthLabel(channelWidth)
                "$chPart • $widthPart"
            }
            compact && ch > 0 && band != WifiBand.UNKNOWN && band != WifiBand.BAND_6_GHZ -> "$chPart (${band.shortLabel})"
            else -> chPart
        }
    }
}
