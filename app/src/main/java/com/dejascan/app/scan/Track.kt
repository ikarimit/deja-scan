package com.dejascan.app.scan

data class Reading(val time: Long, val rssi: Int)

/** Live state for one watched device that has been heard. */
data class Sighting(
    val mac: String,
    val name: String,
    val type: String,
    val randomised: Boolean,
    val rssi: Int,
    val time: Long,
    val firstSeen: Long,
    val best: Int,
    val count: Int,
    val history: List<Reading>,
    val freq: Int,
    val adv: List<String>,
    val wigleLastTime: Long,
    val lat: Double,
    val lon: Double,
    /** True when the device matches the watch rule (shown on the radar, may alert); false for other devices heard nearby. */
    val watched: Boolean = true,
    /** True when this device already exists in the local database. */
    val inDatabase: Boolean = false,
    /** Categorized Wi-Fi security suite (WPA2, WPA3, OPEN, WEP, etc.). */
    val securitySuite: SecuritySuite = SecuritySuite.UNKNOWN,
    /** Channel width (e.g. 20, 40, 80, 160, 320 MHz). */
    val channelWidth: Int = 0,
    /** Center frequency 0 in MHz (for bonded channels: 40, 80, 160, 320 MHz). */
    val centerFreq0: Int = 0,
    /** Center frequency 1 in MHz (for 80+80 MHz or secondary segments). */
    val centerFreq1: Int = 0,
    /** Wi-Fi standard (e.g. 11ax = 6, 11be = 8). */
    val wifiStandard: Int = 0,
    /** Human-readable Wi-Fi standard label (e.g. "Wi-Fi 6E (802.11ax 6 GHz)"). */
    val standardLabel: String = "",
) {
    val wifiBand: WifiBand get() = WifiBand.forFrequency(freq)
    val bandwidthMhz: Int get() = ChannelWidthHelper.getWidthMhz(channelWidth)
    val bandwidthLabel: String get() = ChannelWidthHelper.getWidthLabel(channelWidth)
    val centerFreq: Int get() = ChannelWidthHelper.getCenterFreq(freq, centerFreq0)
    val frequencySpan: IntRange get() = ChannelWidthHelper.getFrequencySpan(freq, channelWidth, centerFreq0)
    val standardBadge: String get() = WifiStandardHelper.getStandardBadge(wifiStandard, freq)

    val parsedCapabilities: ParsedCapabilities? get() =
        if (type == "W") WifiSecurity.fromAdv(adv) ?: (if (securitySuite != SecuritySuite.UNKNOWN) WifiSecurity.fromSuite(securitySuite) else null) else null

    val securityIssues: List<SecurityIssue> get() = parsedCapabilities?.securityIssues ?: emptyList()
    val isWep: Boolean get() = securitySuite == SecuritySuite.WEP || (parsedCapabilities?.suite == SecuritySuite.WEP)
    val isOpen: Boolean get() = (securitySuite == SecuritySuite.OPEN || parsedCapabilities?.suite == SecuritySuite.OPEN) && securitySuite != SecuritySuite.OWE
    val hasSecurityIssue: Boolean get() = securityIssues.isNotEmpty()
    val hasCriticalSecurityIssue: Boolean get() = securityIssues.any { it.severity == SecurityIssueSeverity.CRITICAL }
    val primarySecurityIssue: SecurityIssue? get() = securityIssues.minByOrNull { it.severity.ordinal }
}

enum class Trend { APPROACHING, LEAVING, STEADY }

/** Compares the last three readings against the three before them. */
fun trendOf(history: List<Reading>): Trend {
    if (history.size < 4) return Trend.STEADY
    val recent = history.takeLast(3).map { it.rssi }.average()
    val before = history.dropLast(3).takeLast(3).map { it.rssi }.average()
    val d = recent - before
    return when {
        d >= 3 -> Trend.APPROACHING
        d <= -3 -> Trend.LEAVING
        else -> Trend.STEADY
    }
}

/** Wi-Fi channel number from centre frequency. 5 GHz and 6 GHz numbers overlap; 6 GHz is not told apart. */
fun channelFor(freqMhz: Int): Int = when {
    freqMhz == 2484 -> 14
    freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
    freqMhz in 5000..5895 -> (freqMhz - 5000) / 5
    freqMhz in 5955..7115 -> (freqMhz - 5950) / 5
    else -> 0
}

/** Maps -35 dBm (centre) .. -95 dBm (edge) onto 0..1 distance from the centre. */
fun radialFrac(rssi: Int): Float = ((-35 - rssi) / 60f).coerceIn(0f, 1f)
