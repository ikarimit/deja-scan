package com.dejascan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dejascan.app.scan.DetectionCategory

/** Fixed phosphor palette and detection classification color scheme. */
object Phosphor {
    val bg = Color(0xFF040A06)
    val green = Color(0xFF39FF88)
    val dim = Color(0xFF1A5C36)
    /** Softer frame colour for panels and chips. */
    val edge = Color(0xFF2A7048)
    /** Secondary text that stays readable on the dark background (dim is for borders). */
    val sub = Color(0xFF7FCBA0)
    val grid = Color(0xFF0F3320)
    val amber = Color(0xFFFFB000)
    val orange = Color(0xFFFF6D00)
    val gold = Color(0xFFFFD600)
    val text = Color(0xFFB8FFD6)
    val cyan = Color(0xFF00E5FF)
    val purple = Color(0xFFB388FF)
    val red = Color(0xFFFF2A55)

    val series = listOf(green, cyan, gold, purple, orange, text)

    fun forRssi(rssi: Int): Color = when {
        rssi >= -67 -> green
        rssi >= -78 -> amber
        else -> Color(0xFF4A8A68)
    }

    fun forCategory(category: DetectionCategory): Color = Color(category.uiColorHex)
}

val Mono = FontFamily.Monospace

@Composable
fun MText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Phosphor.green,
    size: TextUnit = 12.sp,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    Text(
        text, modifier, color = color, fontSize = size, fontFamily = Mono, fontWeight = weight,
        textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis, letterSpacing = letterSpacing,
    )
}

/** Scanlines plus a darkened edge, drawn over the content. */
fun Modifier.crt(): Modifier = drawWithContent {
    drawContent()
    val step = 3.dp.toPx()
    val h = 1.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawRect(Color.Black.copy(alpha = 0.22f), Offset(0f, y), Size(size.width, h))
        y += step
    }
    drawRect(
        Brush.radialGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)),
            center = Offset(size.width / 2, size.height / 2),
            radius = maxOf(size.width, size.height) * 0.75f,
        )
    )
}

@Composable
fun TerminalScreen(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .clip(shape)
            .background(Phosphor.bg)
            .border(1.dp, Phosphor.dim, shape)
            .crt()
    ) { content() }
}
