package com.dejascan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.dejascan.app.scan.MacUtil
import com.dejascan.app.scan.PhoneFix
import com.dejascan.app.scan.Sighting
import com.dejascan.app.scan.SightingBus
import com.dejascan.app.scan.trendOf
import kotlinx.coroutines.delay
import org.mapsforge.core.model.LatLong
import java.text.DateFormat
import java.util.Date

enum class InfoTab(val label: String) { INFO("INFO"), TRACK("TRACK"), MAP("MAP") }

@Composable
private fun Line(key: String, value: String, color: Color = Phosphor.text) {
    Row {
        MText(key, Modifier.width(78.dp), color = Phosphor.sub, size = 11.sp)
        MText(value, Modifier.weight(1f), color = color, size = 11.sp)
    }
}

@Composable
private fun Section(title: String) {
    MText("-- $title --", color = Phosphor.green, size = 11.sp, weight = FontWeight.Bold)
}

/** A device known only from the WiGLE data, shown with the same panel as a live one. */
private fun Device.asSighting() = Sighting(
    mac = mac, name = name.ifEmpty { mac }, type = type, randomised = randomised, rssi = -100, time = 0L,
    firstSeen = 0L, best = -100, count = 0, history = emptyList(), freq = 0, adv = emptyList(),
    wigleLastTime = lastTime, lat = lat, lon = lon,
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
    val s = liveS ?: dev?.asSighting()
    val est by produceState<Estimate?>(null, mac, now / 5000) { value = vm.estimate(mac) }
    val ready by Vendors.ready.collectAsState()
    val vendor = remember(mac, ready) { Vendors.forMac(mac) }
    if (s == null) return
    InfoPanel(
        s, liveS != null, now, vendor, est, phone, running, vm.haptic, vm.beep,
        { vm.changeHaptic(it) }, { vm.changeBeep(it) }, vm.mapFiles, showMapTab,
        if (vm.selectedMac == mac) vm.selectedPlaces else emptyList(), { vm.select(null) }, modifier,
    )
}

/** Details of the chosen device, with INFO / TRACK / MAP tabs down the left side. */
@Composable
fun InfoPanel(
    s: Sighting,
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
    val shape = RoundedCornerShape(14.dp)
    var tab by rememberSaveable { mutableStateOf(InfoTab.INFO) }
    if (!showMapTab && tab == InfoTab.MAP) tab = InfoTab.INFO
    val scroll = rememberScrollState()

    Row(modifier.fillMaxWidth().background(Phosphor.bg, shape).border(1.dp, Phosphor.edge, shape).padding(8.dp)) {
        Column(Modifier.width(60.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoTab.entries.filter { showMapTab || it != InfoTab.MAP }.forEach { t ->
                val on = t == tab
                Box(
                    Modifier.fillMaxWidth().height(48.dp)
                        .background(if (on) Phosphor.green else Phosphor.bg, RoundedCornerShape(8.dp))
                        .border(1.dp, if (on) Phosphor.green else Phosphor.dim, RoundedCornerShape(8.dp))
                        // pressing TRACK again while it is open stops tracking and goes back to INFO
                        .clickable { tab = if (on && t == InfoTab.TRACK) InfoTab.INFO else t },
                    contentAlignment = Alignment.Center,
                ) {
                    MText(t.label, color = if (on) Phosphor.bg else Phosphor.green, size = 11.sp, weight = FontWeight.Bold)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                VendorBadge(vendor, 30.dp, Modifier.padding(end = 8.dp))
                MText("> ${s.name}", Modifier.weight(1f), color = Phosphor.text, size = 14.sp, weight = FontWeight.Bold, maxLines = 1)
                Box(
                    Modifier.padding(start = 8.dp).size(40.dp)
                        .border(2.dp, Phosphor.red, RoundedCornerShape(8.dp))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center,
                ) { MText("X", color = Phosphor.red, size = 22.sp, weight = FontWeight.Bold) }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp).scrollbar(scroll).verticalScroll(scroll)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                when (tab) {
                    InfoTab.INFO -> InfoTabBody(s, live, now, vendor, estimate, phone, places)
                    InfoTab.TRACK -> if (live) {
                        TrackerPanel(s, running, now, haptic, beep, onHaptic, onBeep, Modifier.fillMaxWidth().height(340.dp))
                    } else {
                        MText("> not heard right now.\n> press START WATCHING and get near it to track.", color = Phosphor.amber, size = 11.sp)
                    }
                    InfoTab.MAP -> MapTabBody(s, estimate, phone, mapFiles)
                }
            }
        }
    }
}

@Composable
private fun InfoTabBody(s: Sighting, live: Boolean, now: Long, vendor: String?, estimate: Estimate?, phone: PhoneFix?, places: List<Place>) {
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    val clock = DateFormat.getTimeInstance(DateFormat.MEDIUM)
    val ageSec = ((now - s.time) / 1000).coerceAtLeast(0)
    val hasLoc = s.lat != 0.0 || s.lon != 0.0
    Section("IDENTITY")
    Line("MAC", s.mac)
    Line("TYPE", typeLabel(s.type))
    Line("VENDOR", vendor ?: if (s.randomised) "n/a (randomised address)" else "unknown")
    Line(
        "ADDRESS",
        if (s.randomised) "RANDOMISED (may be a different device)" else "fixed",
        if (s.randomised) Phosphor.red else Phosphor.text,
    )
    Section("WIGLE HISTORY")
    Line("LAST SEEN", if (s.wigleLastTime > 0) date.format(Date(s.wigleLastTime)) else "unknown")
    Line("LOCATION", if (hasLoc) "%.5f, %.5f".format(s.lat, s.lon) else "unknown")
    if (places.isNotEmpty()) PlacesSection(places, date)
    Section("LIVE")
    if (live) {
        Line("SIGNAL", "${s.rssi} dBm  ${MacUtil.proximity(s.rssi)}", Phosphor.forRssi(s.rssi))
        Line("TREND", trendOf(s.history).name)
    } else {
        Line("STATUS", "not heard right now", Phosphor.amber)
    }
    if (estimate != null && phone != null) {
        val d = Geo.haversineM(phone.lat, phone.lon, estimate.lat, estimate.lon)
        Line(
            "EST. DIST",
            "~%.0f m (+/-%.0f m)  %s, %d samples".format(
                d, estimate.radiusM, if (estimate.method == Method.PATH_LOSS_FIT) "path-loss fit" else "centroid", estimate.samples,
            ),
        )
    } else {
        Line("EST. DIST", if (phone == null) "no GPS fix yet" else "need 3+ GPS-tagged readings: move around", Phosphor.sub)
    }
    if (live) {
        Line("BEST", "${s.best} dBm")
        Line("HEARD", "${s.count}x since ${clock.format(Date(s.firstSeen))}")
        Line("LAST", "${ageSec}s ago")
        Section("ADVERTISED")
        if (s.adv.isEmpty()) Line("", "(nothing captured yet)", Phosphor.sub)
        else s.adv.forEach { Line("", it) }
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
            estimate != null -> "> shown: estimated position (cross, circle = uncertainty)"
            hasLoc -> "> shown: WiGLE last known place (diamond)"
            else -> "> no known position for this device yet"
        },
        color = Phosphor.sub, size = 11.sp,
    )
    if (target != null) {
        MiniMap(mapFiles, target, estimate, phone, Modifier.fillMaxWidth().height(260.dp))
        if (phone != null) {
            val d = Geo.haversineM(phone.lat, phone.lon, target.latitude, target.longitude)
            MText("> ~%.0f m from you (blue ring)".format(d), color = Phosphor.text, size = 11.sp)
        }
        if (mapFiles.isEmpty()) MText("> no offline map loaded: add one with MAPS on the Map tab", color = Phosphor.amber, size = 11.sp)
    }
}

/** The same box as [InfoPanel] while nothing is chosen, so the layout does not jump. */
@Composable
fun InfoPlaceholder(lines: List<String>, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val scroll = rememberScrollState()
    Row(modifier.fillMaxWidth().background(Phosphor.bg, shape).border(1.dp, Phosphor.edge, shape).padding(8.dp)) {
        Column(Modifier.width(60.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoTab.entries.filter { it != InfoTab.MAP }.forEach { t ->
                Box(
                    Modifier.fillMaxWidth().height(48.dp).border(1.dp, Phosphor.dim, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) { MText(t.label, color = Phosphor.dim, size = 11.sp, weight = FontWeight.Bold) }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp).scrollbar(scroll).verticalScroll(scroll).padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            lines.forEachIndexed { i, l -> MText(l, color = if (i == 0) Phosphor.green else Phosphor.sub, size = if (i == 0) 13.sp else 11.sp) }
        }
    }
}

private fun coords(p: Place) = "%.5f, %.5f".format(p.lat, p.lon)

/** First and last seen with their locations, then every place the device was seen (oldest first). */
@Composable
private fun PlacesSection(places: List<Place>, date: DateFormat) {
    fun whenText(p: Place) = if (p.time > 0) date.format(Date(p.time)) else "time unknown"
    val timed = places.filter { it.time > 0 }
    val first = timed.firstOrNull() ?: places.first()
    val last = timed.lastOrNull() ?: places.last()
    Section(if (places.size == 1) "SEEN IN 1 PLACE" else "SEEN IN ${places.size} PLACES")
    Line("FIRST SEEN", "${whenText(first)}\n${coords(first)}", Phosphor.green)
    if (places.size >= 2) {
        Line("LAST SEEN", "${whenText(last)}\n${coords(last)}", Phosphor.red)
        val d = Geo.haversineM(first.lat, first.lon, last.lat, last.lon)
        Line("APART", if (d >= 1000) "%.1f km between first and last".format(d / 1000) else "%.0f m between first and last".format(d))
    }
    places.take(12).forEachIndexed { i, p ->
        val step = if (i == 0) "" else {
            val m = Geo.haversineM(places[i - 1].lat, places[i - 1].lon, p.lat, p.lon)
            "  +" + (if (m >= 1000) "%.1f km".format(m / 1000) else "%.0f m".format(m))
        }
        Line("${i + 1}", "${whenText(p)}${step}\n${coords(p)}  ${if (p.own) "my scan" else "WiGLE"}")
    }
    if (places.size > 12) Line("", "... ${places.size - 12} more")
}
