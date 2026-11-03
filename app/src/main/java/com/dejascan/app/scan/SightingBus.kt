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
    val densityTracker = DensityTracker()
    val wifiCapabilities = MutableStateFlow(WifiCapabilities())

    fun report(
        dev: Device,
        liveName: String,
        rssi: Int,
        freq: Int,
        adv: List<String>,
        now: Long,
        watched: Boolean = true,
        inDatabase: Boolean = false,
        securitySuite: SecuritySuite = SecuritySuite.UNKNOWN,
        channelWidth: Int = 0,
        centerFreq0: Int = 0,
        centerFreq1: Int = 0,
        wifiStandard: Int = 0,
        standardLabel: String = "",
    ) {
        val isKnown = inDatabase || (dev.lastTime > 0L)
        densityTracker.record(dev.mac, dev.type, isKnown, now)
        sightings.update { map ->
            val prev = map[dev.mac]
            var hist = prev?.history ?: emptyList()
            val last = hist.lastOrNull()
            if (last == null || now - last.time >= 1000) {
                hist = (hist + Reading(now, rssi)).filter { now - it.time <= HISTORY_MS }.takeLast(MAX_POINTS)
            }
            val effectiveSuite = if (securitySuite != SecuritySuite.UNKNOWN) securitySuite else (prev?.securitySuite ?: SecuritySuite.UNKNOWN)
            val effectiveWidth = if (channelWidth != 0) channelWidth else (prev?.channelWidth ?: 0)
            val effectiveCf0 = if (centerFreq0 != 0) centerFreq0 else (prev?.centerFreq0 ?: 0)
            val effectiveCf1 = if (centerFreq1 != 0) centerFreq1 else (prev?.centerFreq1 ?: 0)
            val effectiveStd = if (wifiStandard != 0) wifiStandard else (prev?.wifiStandard ?: 0)
            val effectiveStdLabel = standardLabel.ifEmpty { prev?.standardLabel ?: "" }
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
                inDatabase = isKnown || (prev?.inDatabase ?: false),
                securitySuite = effectiveSuite,
                channelWidth = effectiveWidth,
                centerFreq0 = effectiveCf0,
                centerFreq1 = effectiveCf1,
                wifiStandard = effectiveStd,
                standardLabel = effectiveStdLabel,
            ))
        }
    }

    fun markKnown(knownMacs: Set<String>) {
        if (knownMacs.isEmpty()) return
        densityTracker.markKnown(knownMacs)
        sightings.update { map ->
            map.mapValues { (mac, s) ->
                if (mac in knownMacs && !s.inDatabase) s.copy(inDatabase = true) else s
            }
        }
    }

    fun clear() {
        sightings.value = emptyMap()
        densityTracker.clear()
    }
}
