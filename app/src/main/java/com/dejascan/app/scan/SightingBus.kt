package com.dejascan.app.scan

import com.dejascan.app.data.Device
import com.dejascan.app.scan.promiscuous.PromiscuousBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class PhoneFix(val lat: Double, val lon: Double, val acc: Float, val time: Long)

/** In-process channel from the scan service to the UI. */
object SightingBus {
    private const val HISTORY_MS = 10 * 60_000L
    private const val MAX_POINTS = 600

    val running = MutableStateFlow(false)
    val inForeground = MutableStateFlow(false)
    val sightings = MutableStateFlow<Map<String, Sighting>>(emptyMap())
    val message = MutableStateFlow("")
    val phone = MutableStateFlow<PhoneFix?>(null)
    val densityTracker = DensityTracker()

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
        channelWidth: Int = -1,
        centerFreq0: Int = 0,
        wifiStandard: Int = 0,
        standardLabel: String = "",
    ) {
        val isKnown = inDatabase || dev.lastTime > 0L
        val cleanLiveName = MacUtil.cleanAirText(liveName)
        densityTracker.record(dev.mac, dev.type, isKnown, now)
        sightings.update { map ->
            val prev = map[dev.mac]
            val effectiveRssi = if (rssi > -100) rssi else (prev?.rssi ?: rssi)
            var hist = prev?.history.orEmpty()
            val last = hist.lastOrNull()
            if (effectiveRssi > -100 && (last == null || now - last.time >= 1000)) {
                hist = (hist + Reading(now, effectiveRssi)).filter { now - it.time <= HISTORY_MS }.takeLast(MAX_POINTS)
            }
            val prevResolvedName = prev?.name?.takeUnless { it.equals(dev.mac, ignoreCase = true) }.orEmpty()
            val resolvedName = dev.name.ifEmpty { cleanLiveName }.ifEmpty { prevResolvedName }.ifEmpty { dev.mac }
            val mergedAdv = when {
                adv.isEmpty() -> prev?.adv.orEmpty()
                prev != null -> {
                    val unmaskedLine = prev.adv.firstOrNull { it.startsWith("Unmasked SSID:") }
                    if (unmaskedLine != null && adv.none { it.startsWith("Unmasked SSID:") }) adv + unmaskedLine else adv
                }
                else -> adv
            }
            map + (dev.mac to Sighting(
                mac = dev.mac,
                name = resolvedName,
                type = dev.type,
                randomised = dev.randomised,
                rssi = effectiveRssi,
                time = now,
                firstSeen = prev?.firstSeen ?: now,
                best = if (effectiveRssi > -100) maxOf(prev?.best ?: Int.MIN_VALUE, effectiveRssi) else (prev?.best ?: effectiveRssi),
                count = (prev?.count ?: 0) + 1,
                history = hist,
                freq = if (freq != 0) freq else (prev?.freq ?: 0),
                adv = mergedAdv,
                wigleLastTime = dev.lastTime,
                lat = dev.lat,
                lon = dev.lon,
                watched = watched,
                inDatabase = isKnown || (prev?.inDatabase ?: false),
                securitySuite = if (securitySuite != SecuritySuite.UNKNOWN) securitySuite else (prev?.securitySuite ?: SecuritySuite.UNKNOWN),
                channelWidth = if (channelWidth >= 0) channelWidth else (prev?.channelWidth ?: -1),
                centerFreq0 = if (centerFreq0 != 0) centerFreq0 else (prev?.centerFreq0 ?: 0),
                wifiStandard = if (wifiStandard != 0) wifiStandard else (prev?.wifiStandard ?: 0),
                standardLabel = standardLabel.ifEmpty { prev?.standardLabel.orEmpty() },
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
        PromiscuousBus.tracker.clear()
    }
}
