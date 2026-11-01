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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    RADAR("RADAR"), BARS("BARS"), HISTORY("HISTORY"), SPECTRUM("SPECTRUM")
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
            if (s.mac == selected) {
                val b = 13.dp.toPx()
                drawRect(
                    Phosphor.text, Offset(p.x - b, p.y - b), Size(2 * b, 2 * b),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                drawText(measurer, s.name.take(14), Offset(p.x - b, p.y + b + 2.dp.toPx()), nameLabel)
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
                MText(t.name, Modifier.width(84.dp), color = Phosphor.text, size = 11.sp, maxLines = 1)
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

/** Strongest watched Wi-Fi signal per channel. */
@Composable
fun SpectrumPanel(tracks: List<Sighting>, modifier: Modifier = Modifier) {
    val wifi = tracks.filter { it.type == "W" && it.freq > 0 }
    if (wifi.isEmpty()) {
        EmptyHint("> no watched Wi-Fi devices heard")
        return
    }
    val byChan = wifi.groupBy { channelFor(it.freq) }
    val channels = ((1..13).toList() + byChan.keys.filter { it > 13 }).distinct().sorted()
    val measurer = rememberTextMeasurer()
    val small = TextStyle(fontFamily = Mono, fontSize = 8.sp, color = Phosphor.dim)
    val count = TextStyle(fontFamily = Mono, fontSize = 9.sp, color = Phosphor.text)
    Column(modifier.padding(10.dp)) {
        MText("WI-FI CHANNELS  (height = strongest signal)", color = Phosphor.dim, size = 10.sp)
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
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
                val filled = if (best == null) 0 else (((best + 100) / 60f) * blocks).toInt().coerceIn(1, blocks)
                for (b in 0 until blocks) {
                    val y = top + plotH - (b + 1) * bh
                    val on = b < filled
                    drawRect(
                        if (on) Phosphor.forRssi(best!!) else Phosphor.grid.copy(alpha = 0.5f),
                        Offset(x, y + 1.dp.toPx()), Size(cw, bh - 2.dp.toPx()),
                    )
                }
                if (here != null) drawText(measurer, "${here.size}", Offset(x, top + plotH - filled * bh - 13.dp.toPx()), count)
                drawText(measurer, "$ch", Offset(x, size.height - labelH + 2.dp.toPx()), small)
            }
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

@Suppress("unused")
private fun DrawScope.unusedGuard() = Unit
