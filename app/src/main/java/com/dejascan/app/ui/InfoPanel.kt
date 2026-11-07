package com.dejascan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dejascan.app.data.Device
import com.dejascan.app.data.Vendors
import com.dejascan.app.geo.Estimate
import com.dejascan.app.geo.Geo
import com.dejascan.app.geo.Method
import com.dejascan.app.scan.Classification
import com.dejascan.app.scan.DetectionClassifier
import com.dejascan.app.scan.MacUtil
import com.dejascan.app.scan.PhoneFix
import com.dejascan.app.scan.SecurityIssueSeverity
import com.dejascan.app.scan.SecurityLevel
import com.dejascan.app.scan.Sighting
import com.dejascan.app.scan.SightingBus
import com.dejascan.app.scan.promiscuous.PromiscuousBus
import com.dejascan.app.scan.trendOf
import kotlinx.coroutines.delay
import org.mapsforge.core.model.LatLong
import java.text.DateFormat
import java.util.Date

enum class InfoTab(val label: String) { INFO("INFO"), TRACK("TRACK"), MAP("MAP") }

@Composable
private fun Line(key: String, value: String, color: Color = Phosphor.text) {
    Row(Modifier.fillMaxWidth()) {
        MText(key, Modifier.width(80.dp), color = Phosphor.sub, size = 10.5.sp)
        MText(value, Modifier.weight(1f), color = color, size = 10.5.sp)
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(2.dp))
    MText("-- $title --", color = Phosphor.green, size = 10.5.sp, weight = FontWeight.Bold)
}

/** A device known only from the WiGLE database, preserving stored frequency and capabilities. */
private fun Device.asSighting() = Sighting(
    mac = mac,
    name = name.ifEmpty { mac },
    type = type,
    randomised = randomised,
    rssi = -100,
    time = 0L,
    firstSeen = 0L,
    best = -100,
    count = 0,
    history = emptyList(),
    freq = freq,
    adv = if (caps.isNotEmpty()) listOf("Raw Caps: $caps") else emptyList(),
    wigleLastTime = lastTime,
    lat = lat,
    lon = lon,
    channelWidth = -1,
)

/** Looks up everything the panel needs for one device (live or WiGLE-only). */
@Composable
fun DeviceDetails(vm: AppViewModel, mac: String, showMapTab: Boolean, modifier: Modifier = Modifier) {
    val all by SightingBus.sightings.collectAsState()
    val running by SightingBus.running.collectAsState()
    val phone by SightingBus.phone.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1000); value = System.currentTimeMillis() }
    }
    val dev by produceState<Device?>(null, mac) { value = vm.device(mac) }
    val liveS = all[mac]?.takeIf { now - it.time <= STALE_MS }
    val s = liveS ?: dev?.asSighting() ?: all[mac]
    val est by produceState<Estimate?>(null, mac, now / 5000) { value = vm.estimate(mac) }
    val ready by Vendors.ready.collectAsState()
    val vendor = remember(mac, ready) { Vendors.forMac(mac) }
    if (s == null) return
    val cls = remember(s, dev, liveS, vendor, vm.total) {
        dev?.let { DetectionClassifier.classifyDevice(it, liveS, hasDatabase = vm.total > 0, vendor = vendor) }
            ?: DetectionClassifier.classify(s, hasDatabase = vm.total > 0, vendor = vendor)
    }
    InfoPanel(
        s = s,
        classification = cls,
        live = liveS != null,
        now = now,
        vendor = vendor,
        estimate = est,
        phone = phone,
        running = running,
        haptic = vm.haptic,
        beep = vm.beep,
        onHaptic = { vm.changeHaptic(it) },
        onBeep = { vm.changeBeep(it) },
        mapFiles = vm.mapFiles,
        showMapTab = showMapTab,
        places = if (vm.selectedMac == mac) vm.selectedPlaces else emptyList(),
        onClose = { vm.select(null) },
        modifier = modifier,
    )
}

/** Details of the chosen device, with INFO / TRACK / MAP tabs down the left side. */
@Composable
fun InfoPanel(
    s: Sighting,
    classification: Classification,
    live: Boolean,
    now: Long,
    vendor: String?,
    estimate: Estimate?,
    phone: PhoneFix?,
    running: Boolean,
    haptic: Boolean,
    beep: Boolean,
    onHaptic: (Boolean) -> Unit,
    onBeep: (Boolean) -> Unit,
    mapFiles: List<MapFileInfo>,
    showMapTab: Boolean,
    places: List<Place>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    var tab by rememberSaveable { mutableStateOf(InfoTab.INFO) }
    if (!showMapTab && tab == InfoTab.MAP) tab = InfoTab.INFO
    val scroll = rememberScrollState()
    val catColor = Phosphor.forCategory(classification.category)

    Row(modifier.fillMaxWidth().background(Phosphor.bg, shape).border(1.dp, catColor.copy(alpha = 0.65f), shape).padding(8.dp)) {
        Column(Modifier.width(54.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            InfoTab.entries.filter { showMapTab || it != InfoTab.MAP }.forEach { t ->
                val on = t == tab
                Box(
                    Modifier.fillMaxWidth().height(42.dp)
                        .background(if (on) Phosphor.green else Phosphor.bg, RoundedCornerShape(7.dp))
                        .border(1.dp, if (on) Phosphor.green else Phosphor.dim, RoundedCornerShape(7.dp))
                        .clickable { tab = if (on && t == InfoTab.TRACK) InfoTab.INFO else t },
                    contentAlignment = Alignment.Center,
                ) {
                    MText(t.label, color = if (on) Phosphor.bg else Phosphor.green, size = 10.sp, weight = FontWeight.Bold)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                VendorBadge(vendor, 28.dp, Modifier.padding(end = 6.dp))
                Column(Modifier.weight(1f)) {
                    MText(s.name, color = Phosphor.text, size = 13.sp, weight = FontWeight.Bold, maxLines = 1)
                    val metaParts = buildList {
                        add(typeLabel(s.type))
                        if (s.type == "W" && s.freq > 0) add(s.channelDetail)
                        if (live) add("${s.rssi} dBm")
                    }
                    MText(metaParts.joinToString(" • "), color = Phosphor.sub, size = 9.5.sp, maxLines = 1)
                }
                Box(
                    Modifier.padding(start = 6.dp).size(34.dp)
                        .border(1.5.dp, Phosphor.red, RoundedCornerShape(7.dp))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center,
                ) { MText("X", color = Phosphor.red, size = 16.sp, weight = FontWeight.Bold) }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(top = 5.dp).scrollbar(scroll).verticalScroll(scroll)
                    .padding(end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                when (tab) {
                    InfoTab.INFO -> InfoTabBody(s, classification, live, now, vendor, estimate, phone, places)
                    InfoTab.TRACK -> if (live) {
                        TrackerPanel(s, running, now, haptic, beep, onHaptic, onBeep, Modifier.fillMaxWidth().height(320.dp))
                    } else {
                        MText("> not heard right now.\n> press SCAN and move closer to track.", color = Phosphor.amber, size = 11.sp)
                    }
                    InfoTab.MAP -> MapTabBody(s, estimate, phone, mapFiles)
                }
            }
        }
    }
}

@Composable
private fun InfoTabBody(
    s: Sighting,
    classification: Classification,
    live: Boolean,
    now: Long,
    vendor: String?,
    estimate: Estimate?,
    phone: PhoneFix?,
    places: List<Place>,
) {
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    val clock = DateFormat.getTimeInstance(DateFormat.MEDIUM)
    val ageSec = ((now - s.time) / 1000).coerceAtLeast(0)
    val hasLoc = s.lat != 0.0 || s.lon != 0.0
    val catColor = Phosphor.forCategory(classification.category)

    // Color-coded Classification Banner
    Box(
        Modifier
            .fillMaxWidth()
            .background(catColor.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .border(1.dp, catColor.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .background(catColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                MText(classification.category.shortTag, color = Phosphor.bg, size = 9.sp, weight = FontWeight.Bold)
            }
            MText(classification.reason, color = catColor, size = 10.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
    }

    Section("RADIO & IDENTITY")
    Line("MAC", s.mac)
    Line("TYPE", typeLabel(s.type))
    if (s.type == "W" && s.freq > 0) {
        Line("CHANNEL", s.channelDetail, Phosphor.cyan)
        if (s.channelWidth >= 0) {
            val span = s.frequencySpan
            val cf = if (s.centerFreq0 > 0 && s.centerFreq0 != s.freq) " (center ${s.centerFreq0} MHz)" else ""
            Line("FREQ SPAN", "${span.first}–${span.last} MHz • ${s.freq} MHz primary$cf", Phosphor.sub)
        } else {
            Line("FREQUENCY", "${s.freq} MHz (width not stored in WiGLE)", Phosphor.sub)
        }
    }
    val std = s.standardLabel.ifEmpty {
        s.adv.firstOrNull { it.startsWith("Standard: ") }?.removePrefix("Standard: ").orEmpty()
    }
    if (std.isNotEmpty()) {
        Line("STANDARD", std, if (std.contains("Wi-Fi 7") || std.contains("Wi-Fi 6")) Phosphor.cyan else Phosphor.text)
    }
    Line("VENDOR", vendor ?: if (s.randomised) "Private / Randomized MAC" else "Unknown OUI")
    Line(
        "MAC TYPE",
        if (s.randomised) "RANDOMIZED (locally administered)" else "Fixed hardware OUI",
        if (s.randomised) Phosphor.purple else Phosphor.text,
    )

    val monSnap by PromiscuousBus.snapshot.collectAsState()

    if (s.type == "W") {
        val parsed = s.parsedCapabilities
        if (parsed != null) {
            Section("SECURITY")
            val secColor = when (parsed.suite.level) {
                SecurityLevel.INSECURE -> Phosphor.orange
                SecurityLevel.OPEN -> Phosphor.cyan
                SecurityLevel.LEGACY -> Phosphor.amber
                SecurityLevel.HIGH, SecurityLevel.ENHANCED_OPEN -> Phosphor.cyan
                SecurityLevel.MODERN, SecurityLevel.ENTERPRISE -> Phosphor.green
            }
            Line("SUITE", parsed.suite.label, secColor)
            if (parsed.keyManagement.isNotEmpty() && parsed.keyManagement != "None") {
                Line("AUTH", parsed.keyManagement)
            }
            if (parsed.ciphers.isNotEmpty()) {
                Line("CIPHERS", parsed.ciphers.joinToString(", "))
            }
            Line("MODE", parsed.mode.label)
            if (parsed.hasWps) {
                Line("WPS", "Enabled" + (parsed.wpsDetails?.let { " ($it)" } ?: ""), Phosphor.amber)
            }
            for (issue in parsed.securityIssues) {
                val alertColor = when (issue.severity) {
                    SecurityIssueSeverity.CRITICAL -> Phosphor.orange
                    SecurityIssueSeverity.HIGH, SecurityIssueSeverity.MEDIUM -> Phosphor.amber
                    SecurityIssueSeverity.LOW -> Phosphor.cyan
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp)
                        .background(alertColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .border(1.dp, alertColor.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(6.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        MText(issue.title, color = alertColor, size = 10.sp, weight = FontWeight.Bold)
                        MText(issue.detail, color = Phosphor.text.copy(alpha = 0.85f), size = 9.sp)
                    }
                }
            }
        }

        val normMac = s.mac.lowercase()
        val unmasked = monSnap.unmasked[normMac] ?: monSnap.unmasked[s.mac]
        val apStats = monSnap.apStats[normMac] ?: monSnap.apStats[s.mac]
        val assocClients = remember(monSnap.stations, normMac, s.mac) {
            monSnap.stations.values.filter { it.associatedBssid.equals(normMac, ignoreCase = true) }
        }
        if (unmasked != null || apStats != null || assocClients.isNotEmpty()) {
            Section("802.11 MONITOR")
            if (unmasked != null) {
                Line("UNMASKED", "${unmasked.ssid} (via ${unmasked.source})", Phosphor.gold)
            } else if (apStats?.isHiddenBeacon == true) {
                Line("HIDDEN AP", "Awaiting passive client response", Phosphor.amber)
            }
            if (apStats != null && (apStats.controlFrames > 0 || apStats.dataFrames > 0 || apStats.deauthFrames > 0)) {
                Line(
                    "802.11 HDR",
                    "Ctrl ${apStats.controlFrames} • Data ${apStats.dataFrames} • Deauth ${apStats.deauthFrames}",
                    if (apStats.deauthFrames > 0) Phosphor.red else Phosphor.cyan,
                )
                if (apStats.deauthFrames > 0 && apStats.lastReasonCode > 0) {
                    Line("DEAUTH RSN", "IEEE reason code ${apStats.lastReasonCode}", Phosphor.red)
                }
            }
            if (assocClients.isNotEmpty()) {
                val summary = assocClients.take(6).joinToString(", ") { c ->
                    if (c.rssi > -100) "${c.mac} (${c.rssi}dBm)" else c.mac
                }
                Line("CLIENTS (${assocClients.size})", summary, Phosphor.cyan)
            }
        }
    }

    Section("SIGNAL & PROXIMITY")
    if (live) {
        Line("LIVE RSSI", "${s.rssi} dBm (${MacUtil.proximity(s.rssi)} • ${trendOf(s.history).name})", Phosphor.forRssi(s.rssi))
        Line("SESSION", "Best ${s.best} dBm • ${s.count}x since ${clock.format(Date(s.firstSeen))} (${ageSec}s ago)")
    } else {
        Line("STATUS", "Offline (stored WiGLE record)", Phosphor.sub)
    }
    if (estimate != null && phone != null) {
        val d = Geo.haversineM(phone.lat, phone.lon, estimate.lat, estimate.lon)
        Line(
            "EST. DIST",
            "~%.0f m (±%.0f m, %s, %d pts)".format(
                d, estimate.radiusM, if (estimate.method == Method.PATH_LOSS_FIT) "path-loss" else "centroid", estimate.samples,
            ),
        )
    }

    Section("HISTORY")
    Line("WIGLE SEEN", if (s.wigleLastTime > 0) date.format(Date(s.wigleLastTime)) else "not in WiGLE DB")
    if (hasLoc) Line("COORDS", "%.5f, %.5f".format(s.lat, s.lon))
    if (places.isNotEmpty()) PlacesSection(places, date)

    val displayAdv = if (s.type == "W") {
        s.adv.filter {
            !it.startsWith("Security:") && !it.startsWith("Mode:") && !it.startsWith("Raw Caps:") &&
                !it.startsWith("Auth:") && !it.startsWith("Ciphers:") && !it.startsWith("WPS:") &&
                !it.startsWith("802.11r") && !it.startsWith("Passpoint:") && !it.startsWith("Channel:") &&
                !it.startsWith("Standard:") && !it.startsWith("Bandwidth:") && !it.startsWith("Unmasked SSID:")
        }
    } else s.adv
    if (displayAdv.isNotEmpty()) {
        Section("ADVERTISED")
        displayAdv.forEach { Line("", it) }
    }
}

@Composable
private fun MapTabBody(s: Sighting, estimate: Estimate?, phone: PhoneFix?, mapFiles: List<MapFileInfo>) {
    val hasLoc = s.lat != 0.0 || s.lon != 0.0
    val target = when {
        estimate != null -> LatLong(estimate.lat, estimate.lon)
        hasLoc -> LatLong(s.lat, s.lon)
        else -> null
    }
    MText(
        when {
            estimate != null -> "> estimated position (cross, circle = uncertainty)"
            hasLoc -> "> WiGLE last known position (diamond)"
            else -> "> no GPS coordinates recorded yet"
        },
        color = Phosphor.sub, size = 10.5.sp,
    )
    if (target != null) {
        MiniMap(mapFiles, target, estimate, phone, Modifier.fillMaxWidth().height(230.dp))
        if (phone != null) {
            val d = Geo.haversineM(phone.lat, phone.lon, target.latitude, target.longitude)
            MText("> ~%.0f m from your position".format(d), color = Phosphor.text, size = 10.5.sp)
        }
        if (mapFiles.isEmpty()) MText("> add a Mapsforge .map file on the Map tab for streets", color = Phosphor.amber, size = 10.sp)
    }
}

private fun coords(p: Place) = "%.5f, %.5f".format(p.lat, p.lon)

@Composable
private fun PlacesSection(places: List<Place>, date: DateFormat) {
    fun whenText(p: Place) = if (p.time > 0) date.format(Date(p.time)) else "time unknown"
    val timed = places.filter { it.time > 0 }
    val first = timed.firstOrNull() ?: places.first()
    val last = timed.lastOrNull() ?: places.last()
    Section(if (places.size == 1) "SEEN IN 1 PLACE" else "SEEN IN ${places.size} PLACES")
    Line("FIRST", "${whenText(first)} (${coords(first)})", Phosphor.green)
    if (places.size >= 2) {
        Line("LAST", "${whenText(last)} (${coords(last)})", Phosphor.red)
        val d = Geo.haversineM(first.lat, first.lon, last.lat, last.lon)
        Line("SPAN", if (d >= 1000) "%.1f km apart".format(d / 1000) else "%.0f m apart".format(d))
    }
    places.take(8).forEachIndexed { i, p ->
        val step = if (i == 0) "" else {
            val m = Geo.haversineM(places[i - 1].lat, places[i - 1].lon, p.lat, p.lon)
            " +" + (if (m >= 1000) "%.1fkm".format(m / 1000) else "%.0fm".format(m))
        }
        Line("#${i + 1}", "${whenText(p)}$step  [${if (p.own) "scan" else "WiGLE"}]")
    }
    if (places.size > 8) Line("", "... ${places.size - 8} more places")
}
