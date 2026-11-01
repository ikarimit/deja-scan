package com.dejascan.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Always-visible scrollbar drawn over the right edge of a vertically scrolling area. Put it before verticalScroll. */
fun Modifier.scrollbar(state: ScrollState, color: Color = Phosphor.green, track: Color = Phosphor.grid): Modifier =
    drawWithContent {
        drawContent()
        val max = state.maxValue
        if (max > 0 && max < Int.MAX_VALUE) {
            val w = 4.dp.toPx()
            val h = size.height
            drawRoundRect(track, Offset(size.width - w, 0f), Size(w, h), CornerRadius(w / 2))
            val bar = (h * h / (h + max)).coerceAtLeast(28.dp.toPx())
            val y = (h - bar) * state.value / max
            drawRoundRect(color, Offset(size.width - w, y), Size(w, bar), CornerRadius(w / 2))
        }
    }
