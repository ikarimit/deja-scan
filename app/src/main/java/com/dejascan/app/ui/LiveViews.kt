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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dejascan.app.scan.Classification
import com.dejascan.app.scan.DensityTracker
import com.dejascan.app.scan.DetectionCategory
import com.dejascan.app.scan.MacUtil
import com.dejascan.app.scan.Sighting
import com.dejascan.app.scan.radialFrac
import com.dejascan.app.scan.trendOf
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Condensed Live compartments (Wi-Fi Spectrum Analyzer removed; History & Density unified under Activity). */
enum class LiveView(val label: String) {
    RADAR("RADAR"),
    SIGNALS("SIGNALS"),
    ACTIVITY("ACTIVITY"),
    VENDORS("VENDORS"),
}

enum class ActivitySubView(val label: String) {
    SIGNAL("SIGNAL"),
    DENSITY("DENSITY"),
    MONITOR("MONITOR"),
}

enum class SignalFilter(val label: String) {
    ALL("ALL"),
    WATCHED("WATCHED"),
    ALERTS("THREAT/WEP"),
    RARE("RARE"),
    RAND("RAND"),
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

/**
 * Radar: stronger signal = nearer the centre. Blips are color-coded by detection category
 * (Attacker, Outdated WEP, Rare, Uncommon, Randomized MAC, Normal). Tap a blip to inspect.
 */
@Composable
fun RadarPanel(
    tracks: List<Sighting>,
    classifications: Map<String, Classification>,
    running: Boolean,
    now: Long,
    selected: String?,
    showAllNearby: Boolean,
    onToggleShowAll: (Boolean) -> Unit,
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
    val nameLabel = TextStyle(fontFamily = Mono, fontSize = 10.sp, color = Phosphor.text, fontWeight = FontWeight.Bold)
    val subLabel = TextStyle(fontFamily = Mono, fontSize = 9.sp, color = Phosphor.sub)

    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(tracks) {
                detectTapGestures { tap ->
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val radius = minOf(size.width, size.height) / 2f - 12.dp.toPx()
                    val hit = tracks.minByOrNull { (blipPos(it.mac, it.rssi, c, radius) - tap).getDistance() }
                    val near = hit != null && (blipPos(hit.mac, hit.rssi, c, radius) - tap).getDistance() <= 30.dp.toPx()
                    onSelect(if (near) hit.mac else null)
                }
            }
        ) {
            val c = Offset(size.width / 2, size.height / 2)
            val radius = minOf(size.width, size.height) / 2 - 12.dp.toPx()
            if (radius <= 10f) return@Canvas
            val line = Stroke(width = 1.dp.toPx())
            val glow = Stroke(width = 4.dp.toPx())

            for (i in 1..4) {
                val r = radius * i / 4f
                drawCircle(Phosphor.dim.copy(alpha = 0.22f), radius = r, center = c, style = glow)
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
                            1f to Phosphor.green.copy(alpha = 0.35f), center = c,
                        ),
                        radius = radius, center = c,
                    )
                    drawLine(Phosphor.green.copy(alpha = 0.22f), c, Offset(c.x + radius, c.y), strokeWidth = 5.dp.toPx())
                    drawLine(Phosphor.green, c, Offset(c.x + radius, c.y), strokeWidth = 1.5.dp.toPx())
                }
            }
            drawRect(Phosphor.green, Offset(c.x - 2.dp.toPx(), c.y - 2.dp.toPx()), Size(4.dp.toPx(), 4.dp.toPx()))

            // Draw normal blips first, higher-priority threats/rare blips on top
            val ordered = tracks.sortedByDescending { classifications[it.mac]?.category?.priority ?: 5 }
            for (s in ordered) {
                val p = blipPos(s.mac, s.rssi, c, radius)
                val ageSec = ((now - s.time) / 1000f).coerceAtLeast(0f)
                val alpha = (1f - ageSec / 150f).coerceIn(0.3f, 1f)
                val cat = classifications[s.mac]?.category ?: DetectionCategory.NORMAL
                val col = Phosphor.forCategory(cat)
                val half = if (cat.priority <= 2) 5.dp.toPx() else 4.dp.toPx()

                if (running) {
                    val g = (if (cat == DetectionCategory.ATTACKER) 12.dp else 8.dp).toPx() * pulse.value
                    drawCircle(col.copy(alpha = 0.2f * alpha), radius = g, center = p)
                }
                drawRect(col.copy(alpha = alpha), Offset(p.x - half, p.y - half), Size(2 * half, 2 * half))

                when (cat) {
                    DetectionCategory.ATTACKER -> {
                        val r1 = 10.dp.toPx() * (0.85f + 0.35f * pulse.value)
                        drawCircle(col.copy(alpha = 0.9f * alpha), radius = r1, center = p, style = Stroke(width = 1.8.dp.toPx()))
                    }
                    DetectionCategory.WEP -> {
                        val r1 = 8.5.dp.toPx() * (0.85f + 0.25f * pulse.value)
                        drawCircle(col.copy(alpha = 0.8f * alpha), radius = r1, center = p, style = Stroke(width = 1.5.dp.toPx()))
                    }
                    DetectionCategory.RARE -> {
                        drawCircle(col.copy(alpha = 0.65f * alpha), radius = 7.5.dp.toPx(), center = p, style = Stroke(width = 1.dp.toPx()))
                    }
                    else -> Unit
                }

                if (s.mac == selected) {
                    val b = 12.dp.toPx()
                    drawRect(
                        Phosphor.text, Offset(p.x - b, p.y - b), Size(2 * b, 2 * b),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                    val tag = if (cat != DetectionCategory.NORMAL) " [${cat.shortTag}]" else ""
                    val line1 = s.name.take(15) + tag
                    val lx = (p.x - b).coerceIn(6.dp.toPx(), (size.width - 120.dp.toPx()).coerceAtLeast(6.dp.toPx()))
                    val ly = if (p.y + b + 24.dp.toPx() < size.height) p.y + b + 2.dp.toPx() else (p.y - b - 24.dp.toPx()).coerceAtLeast(4.dp.toPx())
                    drawText(measurer, line1, Offset(lx, ly), nameLabel)
                    val ch = s.channelSummary
                    val line2 = if (ch.isNotEmpty()) "$ch • ${s.rssi}dBm" else "${s.rssi} dBm"
                    drawText(measurer, line2, Offset(lx, ly + 11.dp.toPx()), subLabel)
                }
            }
        }

        // Scope toggle chip in top-right corner of Radar
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Phosphor.bg.copy(alpha = 0.85f))
                .border(1.dp, Phosphor.edge, RoundedCornerShape(6.dp))
                .clickable { onToggleShowAll(!showAllNearby) }
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            MText(
                if (showAllNearby) "SCOPE: ALL (${tracks.size})" else "SCOPE: WATCHED (${tracks.size})",
                color = if (showAllNearby) Phosphor.cyan else Phosphor.green,
                size = 9.sp,
                weight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Condensed Signals panel: ranked live detections with category filter pills, color-coded rarity/security
 * badges, and Wi-Fi channel + channel width (`Ch X • YYMHz`).
 */
@Composable
fun SignalsPanel(
    allActive: List<Sighting>,
    classifications: Map<String, Classification>,
    selected: String?,
    filter: SignalFilter,
    onFilterChange: (SignalFilter) -> Unit,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filtered = remember(allActive, classifications, filter) {
        allActive.filter { s ->
            val cat = classifications[s.mac]?.category ?: DetectionCategory.NORMAL
            when (filter) {
                SignalFilter.ALL -> true
                SignalFilter.WATCHED -> s.watched
                SignalFilter.ALERTS -> cat == DetectionCategory.ATTACKER || cat == DetectionCategory.WEP || s.isOpen
                SignalFilter.RARE -> cat == DetectionCategory.RARE || cat == DetectionCategory.UNCOMMON
                SignalFilter.RAND -> cat == DetectionCategory.RANDOMIZED
            }
        }
    }

    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SignalFilter.entries.forEach { f ->
                val active = filter == f
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (active) Phosphor.dim else Color.Transparent)
                        .border(1.dp, if (active) Phosphor.green else Phosphor.grid, RoundedCornerShape(5.dp))
                        .clickable { onFilterChange(f) }
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    MText(
                        f.label,
                        color = if (active) Phosphor.green else Phosphor.sub,
                        size = 9.sp,
                        weight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            MText("${filtered.size} shown", color = Phosphor.dim, size = 9.sp)
        }

        if (filtered.isEmpty()) {
            EmptyHint("> no matching detections in range")
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(filtered, key = { it.mac }) { s ->
                    val cls = classifications[s.mac]
                    val cat = cls?.category ?: DetectionCategory.NORMAL
                    val catColor = Phosphor.forCategory(cat)
                    val isSelected = s.mac == selected
                    val chText = s.channelSummary
                    val secText = if (s.type == "W" && s.securitySuite.shortLabel != "UNK") s.securitySuite.shortLabel else typeLabel(s.type)

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) Phosphor.grid else Phosphor.bg.copy(alpha = 0.6f))
                            .border(1.dp, if (isSelected) Phosphor.text else catColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                            .clickable { onSelect(if (isSelected) null else s.mac) }
                            .padding(horizontal = 7.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(8.dp).background(catColor, CircleShape))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                MText(
                                    s.name.ifEmpty { s.mac }.take(20),
                                    color = Phosphor.text,
                                    size = 10.5.sp,
                                    weight = FontWeight.Bold,
                                    maxLines = 1,
                                )
                                Box(
                                    Modifier
                                        .background(catColor.copy(alpha = 0.16f), RoundedCornerShape(3.dp))
                                        .border(0.5.dp, catColor.copy(alpha = 0.8f), RoundedCornerShape(3.dp))
                                        .padding(horizontal = 4.dp, vertical = 0.5.dp),
                                ) {
                                    MText(cat.shortTag, color = catColor, size = 7.5.sp, weight = FontWeight.Bold)
                                }
                            }
                            val meta = buildString {
                                append(secText)
                                if (chText.isNotEmpty()) append(" • ").append(chText)
                                append(" • ").append(s.mac)
                            }
                            MText(meta, color = Phosphor.sub, size = 8.5.sp, maxLines = 1)
                        }
                        Canvas(Modifier.width(48.dp).height(12.dp)) {
                            val cells = 8
                            val gap = 1.5.dp.toPx()
                            val cw = (size.width - gap * (cells - 1)) / cells
                            val filled = (((s.rssi + 100) / 60f) * cells).toInt().coerceIn(1, cells)
                            for (i in 0 until cells) {
                                drawRect(
                                    if (i < filled) catColor else Phosphor.grid,
                                    Offset(i * (cw + gap), 0f),
                                    Size(cw, size.height),
                                )
                            }
                        }
                        MText("${s.rssi}", Modifier.width(30.dp), color = catColor, size = 10.sp, weight = FontWeight.Bold, align = TextAlign.End)
                    }
                }
            }
        }
    }
}

/**
 * Compartmentalized Activity panel combining Signal History, Rolling AP Density, and Passive 802.11 Monitor.
 */
@Composable
fun ActivityPanel(
    vm: AppViewModel,
    allSightings: Map<String, Sighting>,
    tracks: List<Sighting>,
    densityPoints: List<DensityTracker.MinutePoint>,
    running: Boolean,
    selected: String?,
    now: Long,
    subView: ActivitySubView,
    onSubViewChange: (ActivitySubView) -> Unit,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActivitySubView.entries.forEach { sv ->
                val active = subView == sv
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (active) Phosphor.green else Phosphor.grid)
                        .clickable { onSubViewChange(sv) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    MText(
                        sv.label,
                        color = if (active) Phosphor.bg else Phosphor.sub,
                        size = 9.5.sp,
                        weight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            MText(
                when (subView) {
                    ActivitySubView.SIGNAL -> "5m RSSI trace"
                    ActivitySubView.DENSITY -> "30m APs/min"
                    ActivitySubView.MONITOR -> "Passive 802.11"
                },
                color = Phosphor.dim,
                size = 9.sp,
            )
        }
        when (subView) {
            ActivitySubView.SIGNAL -> HistoryPanel(tracks, selected, now, onSelect, Modifier.weight(1f).fillMaxWidth())
            ActivitySubView.DENSITY -> DensityPanel(densityPoints, running, Modifier.weight(1f).fillMaxWidth())
            ActivitySubView.MONITOR -> MonitorPanel(vm, allSightings, selected, onSelect, Modifier.weight(1f).fillMaxWidth())
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
        EmptyHint("> no active devices in range")
        return
    }
    val chosen = (tracks.filter { it.mac == selected } + tracks.filter { it.mac != selected }).take(5)
    val measurer = rememberTextMeasurer()
    val axis = TextStyle(fontFamily = Mono, fontSize = 9.sp, color = Phosphor.dim)
    Column(modifier.padding(4.dp)) {
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
                val ch = t.channelSummary
                val lbl = t.name.take(12) + if (ch.isNotEmpty()) " ($ch)" else ""
                Row(
                    Modifier.clickable { onSelect(t.mac) }.padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).background(Phosphor.series[i % Phosphor.series.size]))
                    MText(lbl, Modifier.padding(start = 4.dp), color = Phosphor.text, size = 9.5.sp, maxLines = 1)
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun vibrator(ctx: Context): Vibrator? = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

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
        val ch = track.channelSummary
        MText(if (ch.isNotEmpty()) "${track.mac} • $ch" else track.mac, color = Phosphor.sub, size = 10.sp)
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
            color = if (lost) Phosphor.red else Phosphor.forRssi(track.rssi), size = 24.sp, weight = FontWeight.Bold,
        )
        MText("${MacUtil.proximity(track.rssi).uppercase()}  /  ${trendOf(track.history).name}", color = Phosphor.text, size = 11.sp)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = haptic, onCheckedChange = { on ->
                onHaptic(on)
                if (on) vibrator(ctx)?.buzz(120)
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
        EmptyHint("> no Wi-Fi APs recorded yet\n> press SCAN to start")
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

    var selectedIdx by rememberSaveable { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontFamily = Mono, fontSize = 9.sp, color = Phosphor.dim)

    val transition = rememberInfiniteTransition(label = "density_pulse")
    val pulse = transition.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
        label = "density_pulse_val",
    )

    Column(modifier.padding(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val sel = selectedIdx?.let { points.getOrNull(it) }
            if (sel != null) {
                val minAgo = points.lastIndex - selectedIdx!!
                val timeLabel = if (minAgo == 0) "NOW" else "-${minAgo}m"
                MText("$timeLabel: ${sel.apCount} APs (${sel.newApCount} new)", color = Phosphor.text, size = 10.5.sp, weight = FontWeight.Bold)
            } else {
                MText("PEAK: $maxAp", color = Phosphor.gold, size = 10.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MText("AVG: $avgAp", color = Phosphor.cyan, size = 10.sp)
                MText("NOW: $curAp", color = Phosphor.green, size = 10.sp)
            }
        }

        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 4.dp)
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

            val ySteps = 3
            for (step in 0..ySteps) {
                val frac = step / ySteps.toFloat()
                val y = top + h * (1f - frac)
                val value = (maxVal * frac).toInt()
                drawLine(Phosphor.grid, Offset(left, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                drawText(measurer, "$value", Offset(0f, (y - 6.dp.toPx()).coerceAtLeast(0f)), axisStyle)
            }

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

            for (i in points.indices) {
                val minAgo = n - 1 - i
                if (minAgo % 5 == 0) {
                    val x = left + i * slotW + slotW / 2f
                    drawLine(Phosphor.grid, Offset(x, top), Offset(x, top + h), strokeWidth = 1.dp.toPx())
                    val label = if (minAgo == 0) "NOW" else "-${minAgo}m"
                    drawText(measurer, label, Offset(x - 8.dp.toPx(), size.height - bottom + 2.dp.toPx()), axisStyle)
                }
            }

            val trendPath = Path()
            var trendStarted = false

            points.forEachIndexed { i, pt ->
                val x = left + i * slotW + (slotW - barW) / 2f
                val apH = if (maxVal > 0) (pt.apCount / maxVal.toFloat()) * h else 0f
                val newH = if (maxVal > 0) (pt.newApCount / maxVal.toFloat()) * h else 0f
                val yAp = top + h - apH
                val yNew = top + h - newH

                val isCur = running && i == points.lastIndex
                val alpha = if (isCur) (0.6f + 0.4f * pulse.value) else 0.85f

                if (apH > 0f) {
                    drawRect(Phosphor.green.copy(alpha = 0.35f * alpha), Offset(x, yAp), Size(barW, apH))
                    drawRect(Phosphor.green.copy(alpha = alpha), Offset(x, yAp), Size(barW, 2.dp.toPx()))
                    if (newH > 0f) {
                        drawRect(Phosphor.cyan.copy(alpha = 0.85f * alpha), Offset(x, yNew), Size(barW, newH))
                        drawRect(Phosphor.cyan, Offset(x, yNew), Size(barW, 2.dp.toPx()))
                    }
                } else if (isCur) {
                    drawRect(Phosphor.green.copy(alpha = 0.4f * pulse.value), Offset(x, top + h - 2.dp.toPx()), Size(barW, 2.dp.toPx()))
                }

                val cx = x + barW / 2f
                if (!trendStarted) {
                    trendPath.moveTo(cx, yAp)
                    trendStarted = true
                } else {
                    trendPath.lineTo(cx, yAp)
                }

                if (i == selectedIdx) {
                    drawRect(Phosphor.text.copy(alpha = 0.25f), Offset(left + i * slotW, top), Size(slotW, h))
                    drawRect(
                        Phosphor.text,
                        Offset(x - 1.dp.toPx(), yAp - 1.dp.toPx()),
                        Size(barW + 2.dp.toPx(), apH + 1.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }

            if (trendStarted && maxAp > 0) {
                drawPath(
                    trendPath, Phosphor.green.copy(alpha = 0.5f),
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }

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
                Box(Modifier.size(8.dp).background(Phosphor.cyan))
                MText(" NEW APs", Modifier.padding(start = 4.dp), color = Phosphor.cyan, size = 9.sp)
            }
        }
    }
}
