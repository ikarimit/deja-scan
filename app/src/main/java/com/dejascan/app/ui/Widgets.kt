package com.dejascan.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dejascan.app.data.Vendors
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dejascan.app.scan.Sighting
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class IconKind { WIFI, BLUETOOTH, BLE, STAR, STAR_OUTLINE, RADAR, LIST, THEME, MAP }

fun iconKindForType(type: String) = when (type) {
    "W" -> IconKind.WIFI
    "B" -> IconKind.BLUETOOTH
    else -> IconKind.BLE
}

fun typeLabel(t: String) = when (t) { "W" -> "Wi-Fi"; "B" -> "Bluetooth"; "E" -> "BLE"; else -> t }

/** Simple hand-drawn icons, so no icon library is needed. */
@Composable
fun DejaIcon(kind: IconKind, tint: Color, modifier: Modifier = Modifier, iconSize: Dp = 24.dp) {
    Canvas(modifier.size(iconSize)) {
        val w = size.width
        val sw = w * 0.09f
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            IconKind.WIFI -> {
                val cx = w * 0.5f
                val cy = w * 0.78f
                for (f in listOf(0.22f, 0.4f, 0.58f)) {
                    val r = w * f
                    drawArc(
                        color = tint, startAngle = 225f, sweepAngle = 90f, useCenter = false,
                        topLeft = Offset(cx - r, cy - r), size = Size(2 * r, 2 * r), style = stroke,
                    )
                }
                drawCircle(tint, radius = sw * 0.9f, center = Offset(cx, cy))
            }
            IconKind.BLUETOOTH, IconKind.BLE -> {
                val p = Path().apply {
                    moveTo(w * 0.3f, w * 0.3f); lineTo(w * 0.7f, w * 0.7f); lineTo(w * 0.5f, w * 0.9f)
                    lineTo(w * 0.5f, w * 0.1f); lineTo(w * 0.7f, w * 0.3f); lineTo(w * 0.3f, w * 0.7f)
                }
                drawPath(p, tint, style = stroke)
                if (kind == IconKind.BLE) drawCircle(tint, radius = sw * 0.8f, center = Offset(w * 0.88f, w * 0.5f))
            }
            IconKind.STAR, IconKind.STAR_OUTLINE -> {
                val p = Path()
                for (i in 0 until 10) {
                    val a = (-90.0 + i * 36.0) * PI / 180.0
                    val r = if (i % 2 == 0) w * 0.46f else w * 0.19f
                    val x = (w * 0.5f + r * cos(a)).toFloat()
                    val y = (w * 0.53f + r * sin(a)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                if (kind == IconKind.STAR) drawPath(p, tint, style = Fill) else drawPath(p, tint, style = stroke)
            }
            IconKind.RADAR -> {
                val c = Offset(w / 2, w / 2)
                drawCircle(tint, radius = w * 0.42f, center = c, style = stroke)
                drawCircle(tint, radius = w * 0.2f, center = c, style = stroke)
                drawLine(tint, c, Offset(w * 0.8f, w * 0.2f), strokeWidth = sw, cap = StrokeCap.Round)
                drawCircle(tint, radius = sw * 0.8f, center = c)
            }
            IconKind.LIST -> {
                for (y in listOf(0.25f, 0.5f, 0.75f)) {
                    drawCircle(tint, radius = sw * 0.7f, center = Offset(w * 0.2f, w * y))
                    drawLine(tint, Offset(w * 0.38f, w * y), Offset(w * 0.88f, w * y), strokeWidth = sw, cap = StrokeCap.Round)
                }
            }
            IconKind.MAP -> {
                val p = Path().apply {
                    moveTo(w * 0.5f, w * 0.92f)
                    lineTo(w * 0.22f, w * 0.5f)
                    cubicTo(w * 0.1f, w * 0.1f, w * 0.9f, w * 0.1f, w * 0.78f, w * 0.5f)
                    close()
                }
                drawPath(p, tint, style = stroke)
                drawCircle(tint, radius = sw * 1.2f, center = Offset(w * 0.5f, w * 0.38f))
            }
            IconKind.THEME -> {
                val c = Offset(w / 2, w / 2)
                val r = w * 0.38f
                drawCircle(tint, radius = r, center = c, style = stroke)
                drawArc(
                    tint, startAngle = -90f, sweepAngle = 180f, useCenter = true,
                    topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r),
                )
            }
        }
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Box(
        Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

/** 0..4 bars from signal strength in dBm. Heuristic buckets, not a distance. */
fun barsFor(rssi: Int) = when {
    rssi >= -55 -> 4
    rssi >= -67 -> 3
    rssi >= -78 -> 2
    rssi >= -88 -> 1
    else -> 0
}

@Composable
fun signalColor(rssi: Int): Color {
    val c = LocalDejaColors.current
    return when (barsFor(rssi)) { 4, 3 -> c.strong; 2 -> c.mid; else -> c.weak }
}

@Composable
fun SignalBars(rssi: Int, modifier: Modifier = Modifier) {
    val bars = barsFor(rssi)
    val on = signalColor(rssi)
    val off = LocalDejaColors.current.grid
    Row(modifier) {
        for (i in 1..4) {
            Canvas(Modifier.padding(end = 2.dp).size(width = 5.dp, height = 18.dp)) {
                val h = size.height * (0.3f + 0.7f * i / 4f)
                drawRoundRect(
                    color = if (i <= bars) on else off,
                    topLeft = Offset(0f, size.height - h),
                    size = Size(size.width, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
                )
            }
        }
    }
}

/** Coloured circle with the manufacturer's initials (no logos: avoids trademark issues). */
@Composable
fun VendorBadge(name: String?, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val known = !name.isNullOrBlank()
    val hue = Vendors.hue(name)
    val bg = if (known) Color.hsv(hue, 0.5f, 0.42f) else Color(0xFF2A2F2C)
    val fg = if (known) Color.hsv(hue, 0.3f, 1f) else Color(0xFF8A948E)
    Box(modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        MText(Vendors.monogram(name), color = fg, size = (size.value * 0.36f).sp, weight = FontWeight.Bold)
    }
}
