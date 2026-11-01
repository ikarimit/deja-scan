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
)

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
