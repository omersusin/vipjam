package com.vipjam.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

fun consoleStaggerDelay(index: Int): Long =
    (index.coerceAtLeast(0) * 30L).coerceIn(0L, 300L)

fun chainAnimateSpec() =
    spring<IntSize>(stiffness = Spring.StiffnessMedium)

@Composable
fun PowerDot(on: Boolean, modifier: Modifier = Modifier) {
    val color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Canvas(
        modifier = modifier
            .size(12.dp)
            .semantics { contentDescription = if (on) "On" else "Off" }
    ) {
        drawCircle(color = color, radius = size.minDimension / 2f)
    }
}

@Composable
fun StripChevron(expanded: Boolean, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier = modifier
            .size(24.dp)
            .semantics { contentDescription = if (expanded) "Collapse" else "Expand" }
    ) {
        val half = 7.dp.toPx()
        val rise = 4.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val dir = if (expanded) -1f else 1f
        val stroke = 2.dp.toPx()
        drawLine(
            color = tint,
            start = Offset(cx - half, cy - dir * rise / 2f),
            end = Offset(cx, cy + dir * rise / 2f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(cx, cy + dir * rise / 2f),
            end = Offset(cx + half, cy - dir * rise / 2f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun PopSwitch(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val haptics = LocalHapticFeedback.current
    val reduced = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (checked && !reduced) 1.12f else 1f,
        animationSpec = if (reduced) tween(0) else tween(150),
        label = "pop"
    )
    Switch(
        checked = checked,
        onCheckedChange = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onToggle(it)
        },
        enabled = enabled,
        modifier = modifier.graphicsLayer(scaleX = scale, scaleY = scale)
    )
}
