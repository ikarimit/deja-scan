package com.dejascan.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import com.dejascan.app.scan.ChannelWidthHelper
import com.dejascan.app.scan.SecuritySuite
import com.dejascan.app.scan.WifiBand
import com.dejascan.app.scan.WifiCapabilities
import com.dejascan.app.scan.WifiStandardHelper
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dejascan.app.scan.DensityTracker
import com.dejascan.app.scan.MacUtil
import com.dejascan.app.scan.Sighting
import com.dejascan.app.scan.channelFor
import com.dejascan.app.scan.radialFrac
import com.dejascan.app.scan.trendOf
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class LiveView(val label: String) {
    RADAR("RADAR"), BARS("BARS"), HISTORY("HISTORY"), SPECTRUM("SPECTRUM"), DENSITY("DENSITY"), VENDORS("VENDORS")
}

/** Devices not heard for this long drop off the views. */
const val STALE_MS = 5 * 60_000L
private const val HISTORY_WINDOW_MS = 5 * 60_000L

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MText(text, color = Phosphor.dim, size = 12.sp, align = TextAlign.Center, modifier = Modifier.padding(20.dp))
    }
}

private fun blipPos(mac: String, rssi: Int, c: Offset, radius: Float): Offset {
    val a = ((mac.hashCode() and 0xFFFF) / 65535.0) * 2 * PI
    val r = radius * maxOf(0.06f, radialFrac(rssi))
    return Offset((c.x + r * cos(a)).toFloat(), (c.y + r * sin(a)).toFloat())
}

/** Radar: stronger signal = nearer the centre. Tap a blip to select it. */
@Composable
fun RadarPanel(
    tracks: List<Sighting>,
    running: Boolean,
    now: Long,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "sweep")
    val angle = transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    val pulse = transition.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val measurer = rememberTextMeasurer()
    val ringLabel = TextStyle(fontFamily = Mono, fontSize = 9.sp, color = Phosphor.dim)
    val nameLabel = TextStyle(fontFamily = Mono, fontSize = 10.sp, color = Phosphor.text)

    Canvas(
        modifier.pointerInput(tracks) {
            detectTapGestures { tap ->
                val c = Offset(size.width / 2f, size.height / 2f)
                val radius = minOf(size.width, size.height) / 2f - 10.dp.toPx()
                val hit = tracks.minByOrNull { (blipPos(it.mac, it.rssi, c, radius) - tap).getDistance() }
                val near = hit != null && (blipPos(hit.mac, hit.rssi, c, radius) - tap).getDistance() <= 30.dp.toPx()
                onSelect(if (near) hit!!.mac else null)
            }
        }
    ) {
        val c = Offset(size.width / 2, size.height / 2)
        val radius = minOf(size.width, size.height) / 2 - 10.dp.toPx()
        val line = Stroke(width = 1.dp.toPx())
        val glow = Stroke(width = 5.dp.toPx())

        for (i in 1..4) {
            val r = radius * i / 4f
            drawCircle(Phosphor.dim.copy(alpha = 0.25f), radius = r, center = c, style = glow)
            drawCircle(Phosphor.dim, radius = r, center = c, style = line)
            drawText(
                measurer, "${-35 - 15 * i}", Offset(c.x + 3.dp.toPx(), c.y - r - 11.dp.toPx()), ringLabel,
            )
        }
        drawLine(Phosphor.dim, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), strokeWidth = 1.dp.toPx())
        drawLine(Phosphor.dim, Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), strokeWidth = 1.dp.toPx())

        if (running) {
            rotate(angle.value, pivot = c) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent, 0.75f to Color.Transparent,
                        1f to Phosphor.green.copy(alpha = 0.4f), center = c,
                    ),
                    radius = radius, center = c,
                )
                drawLine(Phosphor.green.copy(alpha = 0.25f), c, Offset(c.x + radius, c.y), strokeWidth = 6.dp.toPx())
                drawLine(Phosphor.green, c, Offset(c.x + radius, c.y), strokeWidth = 1.5.dp.toPx())
            }
        }
        drawRect(Phosphor.green, Offset(c.x - 2.dp.toPx(), c.y - 2.dp.toPx()), Size(4.dp.toPx(), 4.dp.toPx()))

        for (s in tracks) {
            val p = blipPos(s.mac, s.rssi, c, radius)
            val ageSec = ((now - s.time) / 1000f).coerceAtLeast(0f)
            val alpha = (1f - ageSec / 150f).coerceIn(0.25f, 1f)
            val col = Phosphor.forRssi(s.rssi)
            val half = 4.5.dp.toPx()
            if (running) {
                val g = 10.dp.toPx() * pulse.value
                drawRect(col.copy(alpha = 0.22f * alpha), Offset(p.x - g, p.y - g), Size(2 * g, 2 * g))
            }
            drawRect(col.copy(alpha = alpha), Offset(p.x - half, p.y - half), Size(2 * half, 2 * half))
            if (s.isWep) {
                val warnRadius = 8.dp.toPx() * (0.85f + 0.3f * pulse.value)
                drawCircle(Phosphor.red.copy(alpha = 0.75f * alpha), radius = warnRadius, center = p, style = Stroke(width = 1.5.dp.toPx()))
            } else if (s.hasSecurityIssue) {
                drawCircle(Phosphor.amber.copy(alpha = 0.5f * alpha), radius = 7.dp.toPx(), center = p, style = Stroke(width = 1.dp.toPx()))
            }
            if (s.mac == selected) {
                val b = 13.dp.toPx()
                drawRect(
                    Phosphor.text, Offset(p.x - b, p.y - b), Size(2 * b, 2 * b),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                val labelText = s.name.take(14) + if (s.isWep) " [WEP!]" else if (s.isOpen) " [OPEN]" else ""
                drawText(measurer, labelText, Offset(p.x - b, p.y + b + 2.dp.toPx()), nameLabel)
            }
        }
    }
}

/** One segmented bar per device, strongest first. */
@Composable
fun BarsPanel(tracks: List<Sighting>, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val rows = tracks.take(12)
    if (rows.isEmpty()) {
        EmptyHint("> no watched devices in range")
        return
    }
    LazyColumn(modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(rows, key = { it.mac }) { t ->
            Row(
                Modifier.fillMaxWidth()
                    .background(if (t.mac == selected) Phosphor.grid else Color.Transparent)
                    .clickable { onSelect(t.mac) }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.width(96.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MText(t.name, Modifier.weight(1f, fill = false), color = Phosphor.text, size = 11.sp, maxLines = 1)
                    if (t.isWep) {
                        MText("WEP", color = Phosphor.red, size = 8.sp, weight = FontWeight.Bold)
                    } else if (t.isOpen) {
                        MText("OPEN", color = Phosphor.amber, size = 8.sp, weight = FontWeight.Bold)
                    }
                }
                Canvas(Modifier.weight(1f).height(14.dp)) {
                    val cells = 20
                    val gap = 2.dp.toPx()
                    val cw = (size.width - gap * (cells - 1)) / cells
                    val filled = (((t.rssi + 100) / 60f) * cells).toInt().coerceIn(0, cells)
                    val col = Phosphor.forRssi(t.rssi)
                    for (i in 0 until cells) {
                        drawRect(if (i < filled) col else Phosphor.grid, Offset(i * (cw + gap), 0f), Size(cw, size.height))
                    }
                }
                MText("${t.rssi}", Modifier.width(40.dp).padding(start = 6.dp), size = 11.sp, align = TextAlign.End)
            }
        }
    }
}

/** Signal over the last five minutes for up to five devices (the selected one first). */
@Composable
fun HistoryPanel(
    tracks: List<Sighting>,
    selected: String?,
    now: Long,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) {
        EmptyHint("> no watched devices in range")
        return
    }
    val chosen = (tracks.filter { it.mac == selected } + tracks.filter { it.mac != selected }).take(5)
    val measurer = rememberTextMeasurer()
    val axis = TextStyle(fontFamily = Mono, fontSize = 9.sp, color = Phosphor.dim)
    Column(modifier.padding(10.dp)) {
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val left = 28.dp.toPx()
            val w = size.width - left
            val h = size.height
            for (db in listOf(-40, -60, -80)) {
                val y = (-30 - db) / 70f * h
                drawLine(Phosphor.grid, Offset(left, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                drawText(measurer, "$db", Offset(0f, y - 6.dp.toPx()), axis)
            }
            for (m in 0..5) {
                val x = left + w * m / 5f
                drawLine(Phosphor.grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1.dp.toPx())
            }
            chosen.forEachIndexed { i, t ->
                val col = Phosphor.series[i % Phosphor.series.size]
                val path = Path()
                var started = false
                for (r in t.history) {
                    val age = now - r.time
                    if (age > HISTORY_WINDOW_MS) continue
                    val x = left + w * (1f - age / HISTORY_WINDOW_MS.toFloat())
                    val y = ((-30 - r.rssi) / 70f).coerceIn(0f, 1f) * h
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
                val thick = if (t.mac == selected) 2.5f else 1.5f
                drawPath(path, col.copy(alpha = 0.18f), style = Stroke(thick.dp.toPx() * 3, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, col, style = Stroke(thick.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
            chosen.forEachIndexed { i, t ->
                Row(
                    Modifier.clickable { onSelect(t.mac) }.padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).background(Phosphor.series[i % Phosphor.series.size]))
                    MText(t.name.take(12), Modifier.padding(start = 4.dp), color = Phosphor.text, size = 10.sp, maxLines = 1)
                }
            }
        }
    }
}

enum class SpectrumViewMode(val label: String) {
    CURVES("CURVES"),
    BARS("BARS"),
}

enum class SpectrumFilter(val label: String) {
    ALL("ALL"),
    WATCHED("WATCHED"),
}

enum class Subband6G(val label: String, val range: IntRange) {
    ALL("ALL 6G", 5925..7125),
    UNII_5("U-NII-5", 5925..6425),
    UNII_6_7("U-NII-6/7", 6425..6875),
    UNII_8("U-NII-8", 6875..7125),
}

private fun wifiStandardColor(s: Sighting): Color = when {
    s.wifiStandard == WifiStandardHelper.STANDARD_11BE || s.standardLabel.contains("Wi-Fi 7") -> Phosphor.cyan
    s.wifiBand == WifiBand.BAND_6_GHZ || s.standardLabel.contains("Wi-Fi 6E") -> Color(0xFF00E676)
    s.wifiStandard == WifiStandardHelper.STANDARD_11AX || s.standardLabel.contains("Wi-Fi 6") -> Phosphor.green
    s.wifiStandard == WifiStandardHelper.STANDARD_11AC || s.standardLabel.contains("Wi-Fi 5") -> Phosphor.amber
    s.wifiStandard == WifiStandardHelper.STANDARD_11N || s.standardLabel.contains("Wi-Fi 4") -> Color(0xFFFF9100)
    else -> Phosphor.sub
}

private fun getBandChannelTicks(band: WifiBand, range: IntRange): List<Pair<Int, Int>> {
    return when (band) {
        WifiBand.BAND_2_4_GHZ -> (1..13).map { it to (2407 + 5 * it) } + listOf(14 to 2484)
        WifiBand.BAND_5_GHZ -> {
            val chs = listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165)
            chs.map { it to (5000 + 5 * it) }.filter { it.second in range }
        }
        WifiBand.BAND_6_GHZ -> {
            val step = if (range.last - range.first > 600) 16 else 8
            val list = ArrayList<Pair<Int, Int>>()
            for (ch in 1..233 step step) {
                val f = 5950 + 5 * ch
                if (f in range) list.add(ch to f)
            }
            list
        }
        WifiBand.UNKNOWN -> emptyList()
    }
}

/**
 * Wi-Fi 6 / 6E / 7 (6 GHz) & Channel Bandwidth Spectrum scanning view.
 * Visualizes channel bandwidth envelopes (20/40/80/160/320 MHz) and channel occupancy across
 * 2.4 GHz, 5 GHz, and 6 GHz spectrum with device hardware capability reporting.
 */
@Composable
fun SpectrumPanel(
    tracks: List<Sighting>? = null,
    all: List<Sighting> = tracks ?: emptyList(),
    watched: List<Sighting> = tracks?.filter { it.watched } ?: emptyList(),
    wifiCapabilities: WifiCapabilities = WifiCapabilities(),
    selectedMac: String? = null,
    onSelect: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selectedBand by rememberSaveable { mutableStateOf(WifiBand.BAND_2_4_GHZ) }
    var filterMode by rememberSaveable { mutableStateOf(SpectrumFilter.ALL) }
    var viewMode by rememberSaveable { mutableStateOf(SpectrumViewMode.CURVES) }
    var subband6g by rememberSaveable { mutableStateOf(Subband6G.ALL) }

    val baseList = (if (filterMode == SpectrumFilter.WATCHED) watched else all)
        .filter { it.type == "W" && it.freq > 0 }

    val activeRange = if (selectedBand == WifiBand.BAND_6_GHZ) subband6g.range else selectedBand.displayRange

    val inBand = baseList.filter {
        it.wifiBand == selectedBand && (selectedBand != WifiBand.BAND_6_GHZ || it.freq in subband6g.range)
    }.sortedByDescending { it.rssi }

    val count24 = baseList.count { it.wifiBand == WifiBand.BAND_2_4_GHZ }
    val count5 = baseList.count { it.wifiBand == WifiBand.BAND_5_GHZ }
    val count6 = baseList.count { it.wifiBand == WifiBand.BAND_6_GHZ }

    Column(modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        // 1. Device capabilities bar
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MText("DEVICE:", color = Phosphor.dim, size = 9.sp, weight = FontWeight.Bold)
            CapabilityChip("2.4G", true)
            CapabilityChip("5G", wifiCapabilities.is5GhzSupported)
            CapabilityChip("6G(6E/7)", wifiCapabilities.is6GhzSupported)
            CapabilityChip("Wi-Fi 6", wifiCapabilities.isWifi6Supported)
            CapabilityChip("Wi-Fi 7", wifiCapabilities.isWifi7Supported)
        }

        // 2. Control bar: Band selector + Filter (ALL/WATCHED) + View Mode (CURVES/BARS)
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    WifiBand.BAND_2_4_GHZ to "2.4G ($count24)",
                    WifiBand.BAND_5_GHZ to "5G ($count5)",
                    WifiBand.BAND_6_GHZ to ("6G ($count6)" + if (!wifiCapabilities.is6GhzSupported) " [N/A]" else ""),
                ).forEach { (b, label) ->
                    val active = selectedBand == b
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (active) Phosphor.green else Phosphor.grid)
                            .border(1.dp, if (active) Phosphor.green else Phosphor.edge, RoundedCornerShape(4.dp))
                            .clickable { selectedBand = b }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    ) {
                        MText(
                            label,
                            color = if (active) Phosphor.bg else Phosphor.text,
                            size = 10.sp,
                            weight = FontWeight.Bold,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Phosphor.grid)
                        .border(1.dp, Phosphor.edge, RoundedCornerShape(4.dp))
                        .clickable {
                            filterMode = if (filterMode == SpectrumFilter.ALL) SpectrumFilter.WATCHED else SpectrumFilter.ALL
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    MText("[${filterMode.label}]", color = Phosphor.cyan, size = 10.sp, weight = FontWeight.Bold)
                }

                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Phosphor.grid)
                        .border(1.dp, Phosphor.edge, RoundedCornerShape(4.dp))
                        .clickable {
                            viewMode = if (viewMode == SpectrumViewMode.CURVES) SpectrumViewMode.BARS else SpectrumViewMode.CURVES
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    MText("[${viewMode.label}]", color = Phosphor.amber, size = 10.sp, weight = FontWeight.Bold)
                }
            }
        }

        // Subband selector for 6 GHz (if 6 GHz is active)
        if (selectedBand == WifiBand.BAND_6_GHZ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MText("SUB-BAND:", color = Phosphor.dim, size = 9.sp)
                Subband6G.entries.forEach { sb ->
                    val active = subband6g == sb
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (active) Phosphor.sub else Phosphor.grid)
                            .clickable { subband6g = sb }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        MText(
                            sb.label,
                            color = if (active) Phosphor.bg else Phosphor.sub,
                            size = 9.sp,
                            weight = if (active) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        if (selectedBand == WifiBand.BAND_6_GHZ && !wifiCapabilities.is6GhzSupported && inBand.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .background(Phosphor.bg, RoundedCornerShape(8.dp))
                    .border(1.dp, Phosphor.amber, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    MText("! 6 GHz (Wi-Fi 6E / Wi-Fi 7) NOT SUPPORTED BY DEVICE", color = Phosphor.amber, size = 11.sp, weight = FontWeight.Bold)
                    MText("> WifiManager.is6GHzBandSupported() = false", color = Phosphor.sub, size = 10.sp)
                    MText("> Radio lacks 6 GHz hardware reception (5925–7125 MHz).", color = Phosphor.text, size = 10.sp)
                    MText("> Wi-Fi 6 (802.11ax) supported: ${if (wifiCapabilities.isWifi6Supported) "YES (on 2.4/5 GHz)" else "NO"}", color = Phosphor.sub, size = 10.sp)
                    MText("> Wi-Fi 7 (802.11be) supported: ${if (wifiCapabilities.isWifi7Supported) "YES" else "NO"}", color = Phosphor.sub, size = 10.sp)
                    MText("> Wi-Fi 6E/7 6 GHz spectrum scanning will activate on supported hardware.", color = Phosphor.dim, size = 9.sp)
                }
            }
        } else if (inBand.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyHint(
                    if (filterMode == SpectrumFilter.WATCHED) "> no watched Wi-Fi APs heard in ${selectedBand.label}"
                    else "> scanning ${selectedBand.label} spectrum... waiting for Wi-Fi AP beacons"
                )
            }
        } else {
            // Spectrum Drawing
            Box(Modifier.weight(0.55f).fillMaxWidth()) {
                if (viewMode == SpectrumViewMode.CURVES) {
                    SpectrumCurvesCanvas(
                        band = selectedBand,
                        range = activeRange,
                        networks = inBand,
                        selectedMac = selectedMac,
                        onSelect = onSelect,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    SpectrumBarsCanvas(
                        band = selectedBand,
                        networks = inBand,
                        selectedMac = selectedMac,
                        onSelect = onSelect,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            MText(
                "${selectedBand.label} NETWORKS (${inBand.size} detected) — tap to inspect",
                color = Phosphor.dim,
                size = 10.sp,
                weight = FontWeight.Bold,
            )
            LazyColumn(
                Modifier.weight(0.45f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(inBand, key = { it.mac }) { s ->
                    SpectrumApRow(
                        s = s,
                        isSelected = s.mac == selectedMac,
                        onClick = { onSelect(s.mac) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CapabilityChip(label: String, supported: Boolean) {
    Box(
        Modifier
            .background(if (supported) Phosphor.grid else Phosphor.bg, RoundedCornerShape(3.dp))
            .border(0.5.dp, if (supported) Phosphor.edge else Phosphor.dim, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        MText(
            "$label:${if (supported) "OK" else "-"}",
            color = if (supported) Phosphor.green else Phosphor.dim,
            size = 8.sp,
            weight = if (supported) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SpectrumCurvesCanvas(
    band: WifiBand,
    range: IntRange,
    networks: List<Sighting>,
    selectedMac: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val small = TextStyle(fontFamily = Mono, fontSize = 7.sp, color = Phosphor.dim)
    val labelStyle = TextStyle(fontFamily = Mono, fontSize = 8.sp, color = Phosphor.text, fontWeight = FontWeight.Bold)
    val highlightStyle = TextStyle(fontFamily = Mono, fontSize = 8.sp, color = Phosphor.green, fontWeight = FontWeight.Bold)

    Canvas(
        modifier
            .pointerInput(networks, range) {
                detectTapGestures { offset ->
                    val minFreq = range.first.toFloat()
                    val maxFreq = range.last.toFloat()
                    val left = 24.dp.toPx()
                    val right = 8.dp.toPx()
                    val plotW = size.width - left - right
                    val tapFreq = minFreq + ((offset.x - left) / plotW) * (maxFreq - minFreq)
                    val hit = networks.minByOrNull {
                        val dCenter = kotlin.math.abs(it.centerFreq - tapFreq)
                        val inSpan = tapFreq >= it.frequencySpan.first && tapFreq <= it.frequencySpan.last
                        if (inSpan) dCenter else dCenter + 1000f
                    }
                    if (hit != null) onSelect(hit.mac)
                }
            }
    ) {
        val minFreq = range.first.toFloat()
        val maxFreq = range.last.toFloat()
        val left = 24.dp.toPx()
        val right = 8.dp.toPx()
        val top = 16.dp.toPx()
        val bottom = 18.dp.toPx()
        val plotW = size.width - left - right
        val plotH = size.height - top - bottom
        val yBottom = top + plotH

        fun freqToX(f: Float): Float = left + plotW * ((f - minFreq) / (maxFreq - minFreq)).coerceIn(0f, 1f)
        fun rssiToY(rssi: Int): Float = top + plotH * ((-30f - rssi.coerceIn(-100, -30)) / 70f)

        // RSSI Grid lines: -40, -60, -80, -100 dBm
        listOf(-40, -60, -80, -100).forEach { dbm ->
            val y = rssiToY(dbm)
            drawLine(Phosphor.grid, Offset(left, y), Offset(left + plotW, y), strokeWidth = 1f)
            drawText(measurer, "$dbm", Offset(2.dp.toPx(), y - 5.dp.toPx()), small)
        }

        // Channel Ticks along X-axis
        val ticks = getBandChannelTicks(band, range)
        ticks.forEach { (ch, f) ->
            val x = freqToX(f.toFloat())
            drawLine(Phosphor.grid, Offset(x, top), Offset(x, yBottom + 3.dp.toPx()), strokeWidth = 1f)
            val isMajor = (band == WifiBand.BAND_2_4_GHZ && ch in listOf(1, 6, 11)) ||
                (band == WifiBand.BAND_5_GHZ && ch in listOf(36, 48, 64, 100, 149, 165))
            drawText(
                measurer, "$ch",
                Offset(x - 4.dp.toPx(), yBottom + 3.dp.toPx()),
                if (isMajor) highlightStyle else small,
            )
        }

        // Draw Wi-Fi Channel Bandwidth Envelopes (draw non-selected first, selected last on top)
        val sortedForDraw = networks.sortedBy { it.mac == selectedMac }
        sortedForDraw.forEach { s ->
            val isSelected = s.mac == selectedMac
            val fCenter = s.centerFreq.toFloat()
            val halfW = s.bandwidthMhz / 2f
            val fStart = fCenter - halfW
            val fEnd = fCenter + halfW

            val rawXStart = left + plotW * ((fStart - minFreq) / (maxFreq - minFreq))
            val rawXEnd = left + plotW * ((fEnd - minFreq) / (maxFreq - minFreq))
            val rawXCenter = left + plotW * ((fCenter - minFreq) / (maxFreq - minFreq))

            val widthPx = maxOf(14f, rawXEnd - rawXStart)
            val xCenter = rawXCenter.coerceIn(left + widthPx / 2f, left + plotW - widthPx / 2f)
            val xStart = xCenter - widthPx / 2f
            val xEnd = xCenter + widthPx / 2f
            val flatW = widthPx * 0.50f
            val xFlatStart = xCenter - flatW / 2f
            val xFlatEnd = xCenter + flatW / 2f
            val yPeak = rssiToY(s.rssi)

            val path = Path().apply {
                moveTo(xStart, yBottom)
                cubicTo(
                    xStart + (xFlatStart - xStart) * 0.35f, yBottom,
                    xFlatStart - (xFlatStart - xStart) * 0.25f, yPeak,
                    xFlatStart, yPeak,
                )
                lineTo(xFlatEnd, yPeak)
                cubicTo(
                    xFlatEnd + (xEnd - xFlatEnd) * 0.25f, yPeak,
                    xEnd - (xEnd - xFlatEnd) * 0.35f, yBottom,
                    xEnd, yBottom,
                )
                close()
            }

            val c = wifiStandardColor(s)
            drawPath(path, c.copy(alpha = if (isSelected) 0.35f else 0.16f))
            drawPath(
                path,
                if (isSelected) Phosphor.text else c,
                style = Stroke(
                    width = if (isSelected) 3.dp.toPx() else 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )

            // Label above peak
            val badge = s.standardBadge.ifEmpty { "${s.bandwidthMhz}M" }
            val label = "${s.name.take(10)} [${s.rssi}dBm • $badge]"
            val textY = (yPeak - 12.dp.toPx()).coerceAtLeast(top)
            drawText(
                measurer, label,
                Offset((xCenter - 25.dp.toPx()).coerceIn(left, left + plotW - 60.dp.toPx()), textY),
                if (isSelected) highlightStyle else labelStyle,
            )

            // Bandwidth span bracket at bottom
            if (s.bandwidthMhz > 20) {
                drawLine(c.copy(alpha = 0.7f), Offset(xStart, yBottom - 2.dp.toPx()), Offset(xEnd, yBottom - 2.dp.toPx()), strokeWidth = 2f)
                drawLine(c.copy(alpha = 0.7f), Offset(xStart, yBottom - 5.dp.toPx()), Offset(xStart, yBottom - 2.dp.toPx()), strokeWidth = 2f)
                drawLine(c.copy(alpha = 0.7f), Offset(xEnd, yBottom - 5.dp.toPx()), Offset(xEnd, yBottom - 2.dp.toPx()), strokeWidth = 2f)
            }
        }
    }
}

@Composable
private fun SpectrumBarsCanvas(
    band: WifiBand,
    networks: List<Sighting>,
    selectedMac: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byChan = networks.groupBy { channelFor(it.freq) }
    val channels = when (band) {
        WifiBand.BAND_2_4_GHZ -> (1..14).toList()
        WifiBand.BAND_5_GHZ -> (listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165) + byChan.keys).distinct().sorted()
        WifiBand.BAND_6_GHZ -> byChan.keys.sorted().ifEmpty { listOf(1, 5, 9, 13, 17, 21, 25, 29, 33) }
        WifiBand.UNKNOWN -> byChan.keys.sorted().ifEmpty { listOf(1) }
    }

    val measurer = rememberTextMeasurer()
    val small = TextStyle(fontFamily = Mono, fontSize = 8.sp, color = Phosphor.dim)
    val countStyle = TextStyle(fontFamily = Mono, fontSize = 9.sp, color = Phosphor.text)
    val bwStyle = TextStyle(fontFamily = Mono, fontSize = 7.sp, color = Phosphor.cyan)

    Canvas(
        modifier
            .pointerInput(channels, byChan) {
                detectTapGestures { offset ->
                    val gap = 3.dp.toPx()
                    val cw = (size.width - gap * (channels.size - 1)) / channels.size
                    val idx = (offset.x / (cw + gap)).toInt().coerceIn(0, channels.size - 1)
                    val ch = channels[idx]
                    val hit = byChan[ch]?.firstOrNull()
                    if (hit != null) onSelect(hit.mac)
                }
            }
    ) {
        val labelH = 14.dp.toPx()
        val top = 14.dp.toPx()
        val plotH = size.height - labelH - top
        val gap = 3.dp.toPx()
        val cw = (size.width - gap * (channels.size - 1)) / channels.size
        val blocks = 10
        val bh = plotH / blocks

        channels.forEachIndexed { i, ch ->
            val x = i * (cw + gap)
            val here = byChan[ch]
            val best = here?.maxOf { it.rssi }
            val maxBw = here?.maxOfOrNull { it.bandwidthMhz } ?: 20
            val hasSelected = here?.any { it.mac == selectedMac } == true
            val filled = if (best == null) 0 else (((best + 100) / 60f) * blocks).toInt().coerceIn(1, blocks)

            for (b in 0 until blocks) {
                val y = top + plotH - (b + 1) * bh
                val on = b < filled
                drawRect(
                    if (on) (if (hasSelected) Phosphor.text else Phosphor.forRssi(best!!)) else Phosphor.grid.copy(alpha = 0.5f),
                    Offset(x, y + 1.dp.toPx()), Size(cw, bh - 2.dp.toPx()),
                )
            }
            if (here != null) {
                drawText(measurer, "${here.size}", Offset(x, top + plotH - filled * bh - 13.dp.toPx()), countStyle)
                if (maxBw > 20) {
                    drawText(measurer, "${maxBw}M", Offset(x, top + 2.dp.toPx()), bwStyle)
                }
            }
            drawText(measurer, "$ch", Offset(x, size.height - labelH + 2.dp.toPx()), small)
        }
    }
}

@Composable
private fun SpectrumApRow(s: Sighting, isSelected: Boolean, onClick: () -> Unit) {
    val stdBadge = s.standardBadge.ifEmpty {
        if (s.standardLabel.contains("Wi-Fi 7")) "Wi-Fi 7"
        else if (s.standardLabel.contains("Wi-Fi 6E")) "Wi-Fi 6E"
        else if (s.standardLabel.contains("Wi-Fi 6")) "Wi-Fi 6"
        else if (s.standardLabel.contains("Wi-Fi 5")) "Wi-Fi 5"
        else if (s.standardLabel.contains("Wi-Fi 4")) "Wi-Fi 4"
        else ""
    }
    val stdColor = when {
        stdBadge.contains("7") -> Phosphor.cyan
        stdBadge.contains("6E") -> Color(0xFF00E676)
        stdBadge.contains("6") -> Phosphor.green
        stdBadge.contains("5") -> Phosphor.amber
        stdBadge.contains("4") -> Color(0xFFFF9100)
        else -> Phosphor.sub
    }
    val span = s.frequencySpan
    val cfText = if (s.centerFreq0 > 0 && s.centerFreq0 != s.freq) " (Cf: ${s.centerFreq0}M)" else ""

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) Phosphor.grid else Phosphor.bg)
            .border(1.dp, if (isSelected) Phosphor.green else Phosphor.edge, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MText(s.name.take(18), color = Phosphor.text, size = 11.sp, weight = FontWeight.Bold, maxLines = 1)
                if (stdBadge.isNotEmpty()) {
                    Box(
                        Modifier
                            .background(stdColor.copy(alpha = 0.2f), RoundedCornerShape(3.dp))
                            .border(0.5.dp, stdColor, RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        MText(stdBadge, color = stdColor, size = 8.sp, weight = FontWeight.Bold)
                    }
                }
                if (s.isWep) {
                    Box(
                        Modifier
                            .background(Phosphor.red.copy(alpha = 0.25f), RoundedCornerShape(3.dp))
                            .border(0.5.dp, Phosphor.red, RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        MText("WEP ⚠️", color = Phosphor.red, size = 8.sp, weight = FontWeight.Bold)
                    }
                } else if (s.isOpen) {
                    Box(
                        Modifier
                            .background(Phosphor.amber.copy(alpha = 0.2f), RoundedCornerShape(3.dp))
                            .border(0.5.dp, Phosphor.amber, RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        MText("OPEN ⚠️", color = Phosphor.amber, size = 8.sp, weight = FontWeight.Bold)
                    }
                } else if (s.securitySuite != SecuritySuite.UNKNOWN) {
                    MText(s.securitySuite.label, color = Phosphor.dim, size = 9.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MText("Ch ${channelFor(s.freq)}", color = Phosphor.green, size = 10.sp, weight = FontWeight.Bold)
                MText("•", color = Phosphor.dim, size = 10.sp)
                MText("${s.bandwidthLabel} [${span.first}–${span.last} MHz$cfText]", color = Phosphor.sub, size = 9.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            MText("${s.rssi} dBm", color = Phosphor.forRssi(s.rssi), size = 11.sp, weight = FontWeight.Bold)
            MText(s.mac, color = Phosphor.dim, size = 8.sp)
        }
    }
}

@Suppress("DEPRECATION")
private fun vibrator(ctx: Context): Vibrator? = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

/**
 * Buzzes with alarm attributes: plain vibrations are dropped on silent mode / do-not-disturb and when the
 * phone's touch-feedback setting is off, which made the tracker's haptics seem dead.
 */
@Suppress("DEPRECATION")
private fun Vibrator.buzz(ms: Long) {
    val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE), attrs)
}

/** Hot/cold tracker for one device: gauge, big number, haptics and beeps that speed up as it gets closer. */
@Composable
fun TrackerPanel(
    track: Sighting,
    running: Boolean,
    now: Long,
    haptic: Boolean,
    beep: Boolean,
    onHaptic: (Boolean) -> Unit,
    onBeep: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val rssi = rememberUpdatedState(track.rssi)
    val lastHeard = rememberUpdatedState(track.time)
    LaunchedEffect(track.mac, haptic, beep, running) {
        if (!running || (!haptic && !beep)) return@LaunchedEffect
        val tone = if (beep) ToneGenerator(AudioManager.STREAM_MUSIC, 60) else null
        @Suppress("DEPRECATION")
        val vib = if (haptic) vibrator(ctx) else null
        try {
            while (true) {
                val fresh = System.currentTimeMillis() - lastHeard.value < 20_000
                if (fresh) {
                    vib?.buzz(30)
                    tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
                }
                delay(150L + (-30 - rssi.value).coerceIn(0, 70) * 20L)
            }
        } finally {
            tone?.release()
        }
    }

    val lost = now - track.time > 20_000
    Column(modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MText(track.name, color = Phosphor.text, size = 14.sp, weight = FontWeight.Bold, maxLines = 1)
        MText(track.mac, color = Phosphor.sub, size = 10.sp)
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp)) {
            val frac = ((track.rssi + 100) / 70f).coerceIn(0f, 1f)
            val rad = minOf(size.width / 2f, size.height) * 0.92f
            val c = Offset(size.width / 2f, size.height * 0.98f)
            val cells = 24
            for (i in 0 until cells) {
                val a0 = 180f + 180f * i / cells
                val on = (i + 1f) / cells <= frac
                drawArc(
                    color = if (on) Phosphor.forRssi(track.rssi) else Phosphor.grid,
                    startAngle = a0 + 1f, sweepAngle = 180f / cells - 2f, useCenter = false,
                    topLeft = Offset(c.x - rad, c.y - rad), size = Size(2 * rad, 2 * rad),
                    style = Stroke(width = 12.dp.toPx()),
                )
            }
            val a = (180f + 180f * frac) * PI.toFloat() / 180f
            drawLine(
                Phosphor.text, c, Offset(c.x + cos(a) * rad * 0.8f, c.y + sin(a) * rad * 0.8f),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            )
        }
        MText(
            if (lost) "SIGNAL LOST" else "${track.rssi} dBm",
            color = if (lost) Phosphor.red else Phosphor.forRssi(track.rssi), size = 26.sp, weight = FontWeight.Bold,
        )
        MText("${MacUtil.proximity(track.rssi).uppercase()}  /  ${trendOf(track.history).name}", color = Phosphor.text, size = 11.sp)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = haptic, onCheckedChange = { on ->
                onHaptic(on)
                if (on) vibrator(ctx)?.buzz(120) // one test buzz so you can feel that it works
            })
            MText("HAPTIC", Modifier.padding(horizontal = 6.dp), size = 10.sp)
            Switch(checked = beep, onCheckedChange = onBeep)
            MText("BEEP", Modifier.padding(horizontal = 6.dp), size = 10.sp)
        }
    }
}

/** Rolling graph of AP density per minute over the last 30 minutes, highlighting new APs. */
@Composable
fun DensityPanel(
    points: List<DensityTracker.MinutePoint>,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val totalApsInWindow = points.sumOf { it.apCount }
    if (totalApsInWindow == 0 && !running) {
        EmptyHint("> no Wi-Fi APs recorded yet\n> press START WATCHING to scan")
        return
    }

    val maxAp = points.maxOfOrNull { it.apCount } ?: 0
    val maxVal = when {
        maxAp <= 5 -> 5
        maxAp <= 10 -> 10
        maxAp <= 20 -> 20
        maxAp <= 50 -> ((maxAp + 9) / 10) * 10
        else -> ((maxAp + 24) / 25) * 25
    }
    val activeMinutes = points.filter { it.apCount > 0 }
    val avgAp = if (activeMinutes.isNotEmpty()) (activeMinutes.map { it.apCount }.average() + 0.5).toInt() else 0
    val curAp = points.lastOrNull()?.apCount ?: 0

    var selectedIdx by remember { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontFamily = Mono, fontSize = 9.sp, color = Phosphor.dim)

    val transition = rememberInfiniteTransition(label = "density_pulse")
    val pulse = transition.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
        label = "density_pulse_val",
    )

    Column(modifier.padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val sel = selectedIdx?.let { points.getOrNull(it) }
            if (sel != null) {
                val minAgo = points.lastIndex - selectedIdx!!
                val timeLabel = if (minAgo == 0) "NOW" else "-${minAgo}m"
                MText("$timeLabel: ${sel.apCount} APs (${sel.newApCount} new)", color = Phosphor.text, size = 11.sp, weight = FontWeight.Bold)
            } else {
                MText("AP DENSITY / MIN  (30M)", color = Phosphor.dim, size = 11.sp, weight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MText("PEAK: $maxAp", color = Phosphor.amber, size = 10.sp)
                MText("AVG: $avgAp", color = Phosphor.cyan, size = 10.sp)
                MText("NOW: $curAp", color = Phosphor.green, size = 10.sp)
            }
        }

        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 6.dp)
                .pointerInput(points) {
                    detectTapGestures { tap ->
                        val left = 26.dp.toPx()
                        val bottom = 14.dp.toPx()
                        val top = 8.dp.toPx()
                        val w = size.width - left
                        if (tap.x >= left && tap.y in top..(size.height - bottom)) {
                            val slotW = w / points.size.toFloat()
                            val idx = ((tap.x - left) / slotW).toInt().coerceIn(0, points.lastIndex)
                            selectedIdx = if (selectedIdx == idx) null else idx
                        } else {
                            selectedIdx = null
                        }
                    }
                }
        ) {
            val left = 26.dp.toPx()
            val bottom = 14.dp.toPx()
            val top = 8.dp.toPx()
            val w = size.width - left
            val h = size.height - bottom - top
            if (w <= 0f || h <= 0f) return@Canvas

            // Horizontal gridlines & Y-axis labels
            val ySteps = 3
            for (step in 0..ySteps) {
                val frac = step / ySteps.toFloat()
                val y = top + h * (1f - frac)
                val value = (maxVal * frac).toInt()
                drawLine(Phosphor.grid, Offset(left, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                drawText(measurer, "$value", Offset(0f, (y - 6.dp.toPx()).coerceAtLeast(0f)), axisStyle)
            }

            // Average line
            if (avgAp in 1..maxVal) {
                val yAvg = top + h * (1f - avgAp / maxVal.toFloat())
                drawLine(
                    Phosphor.cyan.copy(alpha = 0.45f),
                    Offset(left, yAvg), Offset(size.width, yAvg),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            val n = points.size
            val slotW = w / n.toFloat()
            val barW = (slotW - 2.dp.toPx()).coerceIn(2.dp.toPx(), 14.dp.toPx())

            // Vertical 5-minute gridlines and labels
            for (i in points.indices) {
                val minAgo = n - 1 - i
                if (minAgo % 5 == 0) {
                    val x = left + i * slotW + slotW / 2f
                    drawLine(Phosphor.grid, Offset(x, top), Offset(x, top + h), strokeWidth = 1.dp.toPx())
                    val label = if (minAgo == 0) "NOW" else "-${minAgo}m"
                    drawText(measurer, label, Offset(x - 8.dp.toPx(), size.height - bottom + 2.dp.toPx()), axisStyle)
                }
            }

            // Path for rolling density trend line
            val trendPath = Path()
            var trendStarted = false

            // Draw bars
            points.forEachIndexed { i, pt ->
                val x = left + i * slotW + (slotW - barW) / 2f
                val apH = if (maxVal > 0) (pt.apCount / maxVal.toFloat()) * h else 0f
                val newH = if (maxVal > 0) (pt.newApCount / maxVal.toFloat()) * h else 0f
                val yAp = top + h - apH
                val yNew = top + h - newH

                val isCur = running && i == points.lastIndex
                val alpha = if (isCur) (0.6f + 0.4f * pulse.value) else 0.85f

                if (apH > 0f) {
                    // Total AP bar (green)
                    drawRect(
                        Phosphor.green.copy(alpha = 0.35f * alpha),
                        Offset(x, yAp), Size(barW, apH),
                    )
                    drawRect(
                        Phosphor.green.copy(alpha = alpha),
                        Offset(x, yAp), Size(barW, 2.dp.toPx()),
                    )
                    // New AP portion (amber)
                    if (newH > 0f) {
                        drawRect(
                            Phosphor.amber.copy(alpha = 0.85f * alpha),
                            Offset(x, yNew), Size(barW, newH),
                        )
                        drawRect(
                            Phosphor.amber,
                            Offset(x, yNew), Size(barW, 2.dp.toPx()),
                        )
                    }
                } else if (isCur) {
                    // Scanning now indicator even if 0
                    drawRect(
                        Phosphor.green.copy(alpha = 0.4f * pulse.value),
                        Offset(x, top + h - 2.dp.toPx()), Size(barW, 2.dp.toPx()),
                    )
                }

                // Add to trend line
                val cx = x + barW / 2f
                if (!trendStarted) {
                    trendPath.moveTo(cx, yAp)
                    trendStarted = true
                } else {
                    trendPath.lineTo(cx, yAp)
                }

                // Selection highlight
                if (i == selectedIdx) {
                    drawRect(
                        Phosphor.text.copy(alpha = 0.25f),
                        Offset(left + i * slotW, top),
                        Size(slotW, h),
                    )
                    drawRect(
                        Phosphor.text,
                        Offset(x - 1.dp.toPx(), yAp - 1.dp.toPx()),
                        Size(barW + 2.dp.toPx(), apH + 1.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }

            // Draw trend line across tops
            if (trendStarted && maxAp > 0) {
                drawPath(
                    trendPath, Phosphor.green.copy(alpha = 0.5f),
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }

        // Legend at bottom
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(Phosphor.green))
                MText(" TOTAL APs", Modifier.padding(start = 4.dp), color = Phosphor.text, size = 9.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(Phosphor.amber))
                MText(" NEW APs", Modifier.padding(start = 4.dp), color = Phosphor.amber, size = 9.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(12.dp).height(2.dp).background(Phosphor.cyan))
                MText(" AVG", Modifier.padding(start = 4.dp), color = Phosphor.cyan, size = 9.sp)
            }
        }
    }
}

@Suppress("unused")
private fun DrawScope.unusedGuard() = Unit
