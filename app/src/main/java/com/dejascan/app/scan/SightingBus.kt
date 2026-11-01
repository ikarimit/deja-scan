package com.dejascan.app.scan

import com.dejascan.app.data.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class PhoneFix(val lat: Double, val lon: Double, val acc: Float, val time: Long)

/** In-process channel from the scan service to the UI. */
object SightingBus {
    private const val HISTORY_MS = 10 * 60_000L
    private const val MAX_POINTS = 600

    val running = MutableStateFlow(false)
    val sightings = MutableStateFlow<Map<String, Sighting>>(emptyMap())
    val message = MutableStateFlow("")
    val phone = MutableStateFlow<PhoneFix?>(null)

    fun report(dev: Device, liveName: String, rssi: Int, freq: Int, adv: List<String>, now: Long, watched: Boolean = true) {
        sightings.update { map ->
            val prev = map[dev.mac]
            var hist = prev?.history ?: emptyList()
            val last = hist.lastOrNull()
            if (last == null || now - last.time >= 1000) {
                hist = (hist + Reading(now, rssi)).filter { now - it.time <= HISTORY_MS }.takeLast(MAX_POINTS)
            }
            map + (dev.mac to Sighting(
                mac = dev.mac,
                name = dev.name.ifEmpty { liveName }.ifEmpty { dev.mac },
                type = dev.type,
                randomised = dev.randomised,
                rssi = rssi,
                time = now,
                firstSeen = prev?.firstSeen ?: now,
                best = maxOf(prev?.best ?: Int.MIN_VALUE, rssi),
                count = (prev?.count ?: 0) + 1,
                history = hist,
                freq = if (freq != 0) freq else prev?.freq ?: 0,
                adv = if (adv.isNotEmpty()) adv else prev?.adv ?: emptyList(),
                wigleLastTime = dev.lastTime,
                lat = dev.lat,
                lon = dev.lon,
                watched = watched,
            ))
        }
    }

    fun clear() {
        sightings.value = emptyMap()
    }
}
