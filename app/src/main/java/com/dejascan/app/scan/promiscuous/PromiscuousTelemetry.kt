package com.dejascan.app.scan.promiscuous

import com.dejascan.app.scan.MacUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MonitorState(val label: String) {
    IDLE("IDLE"),
    PROBING("PROBING"),
    ACTIVE_MONITOR("802.11 MONITOR"),
    ACTIVE_PROMISC("PROMISCUOUS"),
    UNSUPPORTED("UNSUPPORTED"),
    ERROR("ERROR");

    val isActive: Boolean get() = this == ACTIVE_MONITOR || this == ACTIVE_PROMISC
}

data class ControlFrameStats(
    val rts: Long = 0,
    val cts: Long = 0,
    val ack: Long = 0,
    val blockAck: Long = 0,
    val psPoll: Long = 0,
    val otherCtrl: Long = 0,
    val deauth: Long = 0,
    val disassoc: Long = 0,
    val probeReq: Long = 0,
    val beacons: Long = 0,
    val dataHeaders: Long = 0,
) {
    val totalControl: Long get() = rts + cts + ack + blockAck + psPoll + otherCtrl
    val totalFrames: Long get() = totalControl + deauth + disassoc + probeReq + beacons + dataHeaders
}

data class ApFrameStats(
    val bssid: String,
    val controlFrames: Long = 0,
    val dataFrames: Long = 0,
    val deauthFrames: Long = 0,
    val lastReasonCode: Int = 0,
    val lastDeauthTime: Long = 0L,
    val isHiddenBeacon: Boolean = false,
)

data class UnmaskedNetwork(
    val bssid: String,
    val ssid: String,
    val source: String,
    val rssi: Int,
    val freqMhz: Int,
    val firstUnmaskedAt: Long,
    val lastSeenAt: Long,
)

data class ClientStation(
    val mac: String,
    val associatedBssid: String?,
    val rssi: Int,
    val freqMhz: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val frameCount: Int,
    val controlFrames: Int,
    val probedSsids: List<String>,
    val randomised: Boolean,
)

data class PromiscuousCapabilities(
    val rootGranted: Boolean = false,
    val wirelessInterfaces: List<String> = emptyList(),
    val tcpdumpPath: String? = null,
    val iwPath: String? = null,
    val ipPath: String? = null,
    val qcomConModePath: String? = null,
    val summary: String = "Not probed yet",
) {
    val canCaptureDot11: Boolean get() = rootGranted && tcpdumpPath != null && wirelessInterfaces.isNotEmpty()
}

data class PromiscuousSnapshot(
    val state: MonitorState = MonitorState.IDLE,
    val activeInterface: String = "",
    val statusDetail: String = "Passive 802.11 capture idle",
    val capabilities: PromiscuousCapabilities = PromiscuousCapabilities(),
    val stats: ControlFrameStats = ControlFrameStats(),
    val unmasked: Map<String, UnmaskedNetwork> = emptyMap(),
    val hiddenBssids: Set<String> = emptySet(),
    val stations: Map<String, ClientStation> = emptyMap(),
    val apStats: Map<String, ApFrameStats> = emptyMap(),
)

/**
 * Passive 802.11 telemetry aggregator for control frame extraction, hidden SSID unmasking,
 * and client station tracking. Thread-safe with bounded retention.
 */
class PromiscuousTracker(
    private val maxStations: Int = 400,
    private val maxProbedPerStation: Int = 6,
) {
    private val _snapshot = MutableStateFlow(PromiscuousSnapshot())
    val snapshot: StateFlow<PromiscuousSnapshot> = _snapshot.asStateFlow()

    private var stats = ControlFrameStats()
    private val knownApBssids = HashSet<String>()
    private val hiddenBssids = HashSet<String>()
    private val unmasked = LinkedHashMap<String, UnmaskedNetwork>()
    private val stations = LinkedHashMap<String, ClientStation>()
    private val apStats = LinkedHashMap<String, ApFrameStats>()

    /** Result of ingesting a single frame, returned so callers (e.g. ScanService) can bridge AP updates to SightingBus. */
    data class IngestEvent(
        val apBssid: String? = null,
        val apSsid: String? = null,
        val unmaskedNow: Boolean = false,
        val unmaskMethod: String = "",
        val rssi: Int = -100,
        val freqMhz: Int = 0,
        val privacyBit: Boolean = false,
    )

    @Synchronized
    fun updateStatus(
        state: MonitorState,
        activeInterface: String = _snapshot.value.activeInterface,
        detail: String = _snapshot.value.statusDetail,
        capabilities: PromiscuousCapabilities = _snapshot.value.capabilities,
    ) {
        _snapshot.value = _snapshot.value.copy(
            state = state,
            activeInterface = activeInterface,
            statusDetail = detail,
            capabilities = capabilities,
        )
    }

    /** Marks a BSSID as known (e.g. from standard WifiManager scanResults) and flags if its beacon SSID was empty/hidden. */
    @Synchronized
    fun noteKnownAp(bssid: String, isHidden: Boolean) {
        val norm = MacUtil.normalize(bssid) ?: return
        knownApBssids.add(norm)
        if (isHidden && norm !in unmasked) {
            hiddenBssids.add(norm)
            val prev = apStats[norm] ?: ApFrameStats(norm)
            apStats[norm] = prev.copy(isHiddenBeacon = true)
            publish()
        }
    }

    @Synchronized
    fun ingest(frame: ParsedDot11Frame, now: Long = System.currentTimeMillis()): IngestEvent? {
        var event: IngestEvent? = null
        when (frame) {
            is ParsedDot11Frame.Control -> ingestControl(frame, now)
            is ParsedDot11Frame.Management -> event = ingestManagement(frame, now)
            is ParsedDot11Frame.Data -> ingestData(frame, now)
        }
        publish()
        return event
    }

    private fun ingestControl(f: ParsedDot11Frame.Control, now: Long) {
        stats = when (f.subtype) {
            CtrlSubtype.RTS -> stats.copy(rts = stats.rts + 1)
            CtrlSubtype.CTS -> stats.copy(cts = stats.cts + 1)
            CtrlSubtype.ACK -> stats.copy(ack = stats.ack + 1)
            CtrlSubtype.BLOCK_ACK, CtrlSubtype.BLOCK_ACK_REQ -> stats.copy(blockAck = stats.blockAck + 1)
            CtrlSubtype.PS_POLL -> stats.copy(psPoll = stats.psPoll + 1)
            CtrlSubtype.CF_END, CtrlSubtype.OTHER -> stats.copy(otherCtrl = stats.otherCtrl + 1)
        }

        val ra = f.receiverMac
        val ta = f.transmitterMac
        when {
            f.subtype == CtrlSubtype.PS_POLL && ta != null -> {
                // IEEE 802.11 PS-Poll: RA is always the BSSID, TA is always the associated client station
                knownApBssids.add(ra)
                bumpApControl(ra)
                recordStation(ta, bssid = ra, rssi = f.rssi, freqMhz = f.freqMhz, now = now, isControl = true)
            }
            ta != null && ra in knownApBssids && ta !in knownApBssids -> {
                bumpApControl(ra)
                recordStation(ta, bssid = ra, rssi = f.rssi, freqMhz = f.freqMhz, now = now, isControl = true)
            }
            ta != null && ta in knownApBssids && ra !in knownApBssids -> {
                bumpApControl(ta)
                recordStation(ra, bssid = null, rssi = -100, freqMhz = f.freqMhz, now = now, isControl = true, updateAssociation = ra in stations)
            }
            ra in knownApBssids -> bumpApControl(ra)
        }
    }

    private fun ingestManagement(f: ParsedDot11Frame.Management, now: Long): IngestEvent? {
        val bssid = f.bssid
        val sa = f.sourceMac
        val da = f.destMac

        return when (f.subtype) {
            MgmtSubtype.BEACON -> {
                stats = stats.copy(beacons = stats.beacons + 1)
                if (bssid != null) {
                    knownApBssids.add(bssid)
                    if (f.isHiddenSsid) {
                        hiddenBssids.add(bssid)
                        val prev = apStats[bssid] ?: ApFrameStats(bssid)
                        apStats[bssid] = prev.copy(isHiddenBeacon = true)
                    }
                    val effectiveSsid = f.ssid ?: unmasked[bssid]?.ssid.orEmpty()
                    IngestEvent(
                        apBssid = bssid,
                        apSsid = effectiveSsid,
                        rssi = f.rssi,
                        freqMhz = f.freqMhz,
                        privacyBit = f.privacyBit,
                    )
                } else null
            }

            MgmtSubtype.PROBE_RESP -> {
                if (bssid == null) return null
                knownApBssids.add(bssid)
                // Directed Probe Response: DA is the requesting client station, SSID IE carries the real network name
                if (da != null && da != "ff:ff:ff:ff:ff:ff" && da != bssid) {
                    recordStation(da, bssid = null, rssi = -100, freqMhz = f.freqMhz, now = now, probedSsid = f.ssid)
                }
                val newlyUnmasked = maybeUnmask(bssid, f.ssid, "Probe Response", f.rssi, f.freqMhz, now)
                IngestEvent(
                    apBssid = bssid,
                    apSsid = f.ssid.orEmpty(),
                    unmaskedNow = newlyUnmasked,
                    unmaskMethod = if (newlyUnmasked) "Probe Response" else "",
                    rssi = f.rssi,
                    freqMhz = f.freqMhz,
                    privacyBit = f.privacyBit,
                )
            }

            MgmtSubtype.ASSOC_REQ, MgmtSubtype.REASSOC_REQ -> {
                if (bssid == null) return null
                knownApBssids.add(bssid)
                val method = if (f.subtype == MgmtSubtype.ASSOC_REQ) "Assoc Request" else "Reassoc Request"
                if (sa != null && sa != bssid) {
                    recordStation(sa, bssid = bssid, rssi = f.rssi, freqMhz = f.freqMhz, now = now, probedSsid = f.ssid)
                }
                val newlyUnmasked = maybeUnmask(bssid, f.ssid, method, f.rssi, f.freqMhz, now)
                if (!f.ssid.isNullOrEmpty()) {
                    IngestEvent(
                        apBssid = bssid,
                        apSsid = f.ssid,
                        unmaskedNow = newlyUnmasked,
                        unmaskMethod = if (newlyUnmasked) method else "",
                        rssi = f.rssi,
                        freqMhz = f.freqMhz,
                    )
                } else null
            }

            MgmtSubtype.PROBE_REQ -> {
                stats = stats.copy(probeReq = stats.probeReq + 1)
                if (sa != null && sa !in knownApBssids) {
                    recordStation(sa, bssid = null, rssi = f.rssi, freqMhz = f.freqMhz, now = now, probedSsid = f.ssid)
                }
                null
            }

            MgmtSubtype.DEAUTH, MgmtSubtype.DISASSOC -> {
                stats = if (f.subtype == MgmtSubtype.DEAUTH) stats.copy(deauth = stats.deauth + 1)
                else stats.copy(disassoc = stats.disassoc + 1)
                if (bssid != null) {
                    val prev = apStats[bssid] ?: ApFrameStats(bssid)
                    apStats[bssid] = prev.copy(
                        deauthFrames = prev.deauthFrames + 1,
                        lastReasonCode = f.reasonCode,
                        lastDeauthTime = now,
                    )
                }
                null
            }

            else -> null
        }
    }

    private fun ingestData(f: ParsedDot11Frame.Data, now: Long) {
        stats = stats.copy(dataHeaders = stats.dataHeaders + 1)
        knownApBssids.add(f.bssid)
        val prevAp = apStats[f.bssid] ?: ApFrameStats(f.bssid)
        apStats[f.bssid] = prevAp.copy(dataFrames = prevAp.dataFrames + 1)
        // RSSI on uplink ToDS frames belongs to the client station; on FromDS frames it belongs to the AP
        val clientRssi = if (f.toDs && !f.fromDs) f.rssi else -100
        recordStation(f.clientMac, bssid = f.bssid, rssi = clientRssi, freqMhz = f.freqMhz, now = now)
    }

    private fun maybeUnmask(bssid: String, ssid: String?, method: String, rssi: Int, freqMhz: Int, now: Long): Boolean {
        if (ssid.isNullOrBlank()) return false
        val existing = unmasked[bssid]
        if (existing != null) {
            unmasked[bssid] = existing.copy(
                ssid = ssid,
                rssi = if (rssi > -100) rssi else existing.rssi,
                freqMhz = if (freqMhz > 0) freqMhz else existing.freqMhz,
                lastSeenAt = now,
            )
            return false
        }
        if (bssid in hiddenBssids) {
            unmasked[bssid] = UnmaskedNetwork(
                bssid = bssid,
                ssid = ssid,
                source = method,
                rssi = rssi,
                freqMhz = freqMhz,
                firstUnmaskedAt = now,
                lastSeenAt = now,
            )
            return true
        }
        return false
    }

    private fun bumpApControl(bssid: String) {
        val prev = apStats[bssid] ?: ApFrameStats(bssid)
        apStats[bssid] = prev.copy(controlFrames = prev.controlFrames + 1)
    }

    private fun recordStation(
        mac: String,
        bssid: String?,
        rssi: Int,
        freqMhz: Int,
        now: Long,
        probedSsid: String? = null,
        isControl: Boolean = false,
        updateAssociation: Boolean = true,
    ) {
        if (mac in knownApBssids) return
        val prev = stations.remove(mac) // Move to most-recently-seen end of LinkedHashMap
        val mergedProbes = if (!probedSsid.isNullOrBlank()) {
            ((prev?.probedSsids.orEmpty() - probedSsid) + probedSsid).takeLast(maxProbedPerStation)
        } else {
            prev?.probedSsids.orEmpty()
        }
        val effectiveRssi = when {
            rssi > -100 -> rssi
            prev != null -> prev.rssi
            else -> -100
        }
        val assoc = if (updateAssociation && bssid != null) bssid else prev?.associatedBssid
        stations[mac] = ClientStation(
            mac = mac,
            associatedBssid = assoc,
            rssi = effectiveRssi,
            freqMhz = if (freqMhz > 0) freqMhz else (prev?.freqMhz ?: 0),
            firstSeen = prev?.firstSeen ?: now,
            lastSeen = now,
            frameCount = (prev?.frameCount ?: 0) + 1,
            controlFrames = (prev?.controlFrames ?: 0) + (if (isControl) 1 else 0),
            probedSsids = mergedProbes,
            randomised = prev?.randomised ?: MacUtil.isRandomised(mac),
        )
        while (stations.size > maxStations) {
            val eldest = stations.keys.firstOrNull() ?: break
            stations.remove(eldest)
        }
    }

    @Synchronized
    fun clear() {
        stats = ControlFrameStats()
        knownApBssids.clear()
        hiddenBssids.clear()
        unmasked.clear()
        stations.clear()
        apStats.clear()
        publish()
    }

    private fun publish() {
        _snapshot.value = _snapshot.value.copy(
            stats = stats,
            unmasked = HashMap(unmasked),
            hiddenBssids = HashSet(hiddenBssids),
            stations = LinkedHashMap(stations),
            apStats = HashMap(apStats),
        )
    }
}

/** Shared in-process bus for passive 802.11 root/promiscuous telemetry. */
object PromiscuousBus {
    val tracker = PromiscuousTracker()
    val snapshot: StateFlow<PromiscuousSnapshot> get() = tracker.snapshot
}
