package com.vipjam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.vipjam.ui.TabPage
import com.vipjam.ui.TopDestinations
import com.vipjam.ui.theme.VipJamTheme
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun DestinationGlyph(
    destination: TabPage,
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .size(24.dp)
            .semantics { this.contentDescription = contentDescription }
    ) {
        val stroke = 2.dp.toPx()
        when (destination) {
            TabPage.Effects -> {
                listOf(
                    6.dp.toPx() to 15.dp.toPx(),
                    12.dp.toPx() to 8.dp.toPx(),
                    18.dp.toPx() to 17.dp.toPx()
                ).forEach { (y, x) ->
                    drawLine(
                        color = tint,
                        start = Offset(3.dp.toPx(), y),
                        end = Offset(21.dp.toPx(), y),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = tint,
                        radius = 3.2.dp.toPx(),
                        center = Offset(x, y)
                    )
                }
            }
            TabPage.Presets -> {
                listOf(4.5.dp.toPx(), 9.75.dp.toPx(), 15.dp.toPx()).forEach { y ->
                    drawRect(
                        color = tint,
                        topLeft = Offset(4.dp.toPx(), y),
                        size = Size(16.dp.toPx(), 4.5.dp.toPx()),
                        style = Stroke(width = stroke)
                    )
                }
            }
            TabPage.TestTone -> {
                val wave = Path().apply {
                    moveTo(2.dp.toPx(), 12.dp.toPx())
                    cubicTo(
                        6.dp.toPx(), 5.dp.toPx(),
                        9.dp.toPx(), 5.dp.toPx(),
                        12.dp.toPx(), 12.dp.toPx()
                    )
                    cubicTo(
                        15.dp.toPx(), 19.dp.toPx(),
                        18.dp.toPx(), 19.dp.toPx(),
                        22.dp.toPx(), 12.dp.toPx()
                    )
                }
                drawPath(
                    path = wave,
                    color = tint,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            TabPage.LiveProg -> {
                val play = Path().apply {
                    moveTo(8.dp.toPx(), 5.dp.toPx())
                    lineTo(19.dp.toPx(), 12.dp.toPx())
                    lineTo(8.dp.toPx(), 19.dp.toPx())
                    close()
                }
                drawPath(path = play, color = tint)
            }
            TabPage.AutoEq -> {
                listOf(5.dp.toPx() to 7.dp.toPx(), 10.25.dp.toPx() to 11.dp.toPx(), 15.5.dp.toPx() to 5.dp.toPx()).forEach { (x, top) ->
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(x, top),
                        size = Size(3.5.dp.toPx(), 19.dp.toPx() - top - stroke),
                        cornerRadius = CornerRadius(1.75.dp.toPx(), 1.75.dp.toPx())
                    )
                }
                drawLine(
                    color = tint,
                    start = Offset(4.dp.toPx(), 19.dp.toPx()),
                    end = Offset(20.dp.toPx(), 19.dp.toPx()),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
            TabPage.AppProfiles -> {
                val cell = 8.dp.toPx()
                val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                listOf(
                    Offset(4.dp.toPx(), 4.dp.toPx()),
                    Offset(12.dp.toPx(), 4.dp.toPx()),
                    Offset(4.dp.toPx(), 12.dp.toPx()),
                    Offset(12.dp.toPx(), 12.dp.toPx())
                ).forEach { origin ->
                    drawRoundRect(
                        color = tint,
                        topLeft = origin,
                        size = Size(cell, cell),
                        cornerRadius = radius
                    )
                }
            }
            TabPage.Status -> {
                drawCircle(
                    color = tint,
                    radius = 8.5.dp.toPx(),
                    center = center,
                    style = Stroke(width = stroke)
                )
                drawCircle(
                    color = tint,
                    radius = 1.6.dp.toPx(),
                    center = Offset(center.x, 8.dp.toPx())
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(center.x - 1.dp.toPx(), 11.dp.toPx()),
                    size = Size(2.dp.toPx(), 6.dp.toPx()),
                    cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
                )
            }
            TabPage.Module -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(4.dp.toPx(), 3.dp.toPx()),
                    size = Size(16.dp.toPx(), 11.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                    style = Stroke(width = stroke)
                )
                drawLine(
                    color = tint,
                    start = Offset(center.x, 6.dp.toPx()),
                    end = Offset(center.x, 13.dp.toPx()),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = tint,
                    start = Offset(9.dp.toPx(), 10.5.dp.toPx()),
                    end = Offset(center.x, 13.5.dp.toPx()),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = tint,
                    start = Offset(15.dp.toPx(), 10.5.dp.toPx()),
                    end = Offset(center.x, 13.5.dp.toPx()),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = tint,
                    start = Offset(4.dp.toPx(), 20.dp.toPx()),
                    end = Offset(20.dp.toPx(), 20.dp.toPx()),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
            TabPage.Home -> {
                val roof = Path().apply {
                    moveTo(3.5.dp.toPx(), 11.dp.toPx())
                    lineTo(12.dp.toPx(), 4.dp.toPx())
                    lineTo(20.5.dp.toPx(), 11.dp.toPx())
                }
                drawPath(
                    path = roof,
                    color = tint,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                val body = Path().apply {
                    moveTo(6.5.dp.toPx(), 10.dp.toPx())
                    lineTo(6.5.dp.toPx(), 19.5.dp.toPx())
                    lineTo(17.5.dp.toPx(), 19.5.dp.toPx())
                    lineTo(17.5.dp.toPx(), 10.dp.toPx())
                }
                drawPath(
                    path = body,
                    color = tint,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            TabPage.Sound -> {
                val speaker = Path().apply {
                    moveTo(3.dp.toPx(), 9.5.dp.toPx())
                    lineTo(8.dp.toPx(), 9.5.dp.toPx())
                    lineTo(13.dp.toPx(), 5.dp.toPx())
                    lineTo(13.dp.toPx(), 19.dp.toPx())
                    lineTo(8.dp.toPx(), 14.5.dp.toPx())
                    lineTo(3.dp.toPx(), 14.5.dp.toPx())
                    close()
                }
                drawPath(path = speaker, color = tint)
                drawArc(
                    color = tint,
                    startAngle = -55f,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(13.5.dp.toPx(), 7.5.dp.toPx()),
                    size = Size(7.dp.toPx(), 9.dp.toPx()),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                drawArc(
                    color = tint,
                    startAngle = -55f,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(12.dp.toPx(), 5.dp.toPx()),
                    size = Size(10.dp.toPx(), 14.dp.toPx()),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            TabPage.Lab -> {
                val flask = Path().apply {
                    moveTo(10.dp.toPx(), 3.dp.toPx())
                    lineTo(10.dp.toPx(), 8.5.dp.toPx())
                    lineTo(5.dp.toPx(), 18.5.dp.toPx())
                    lineTo(19.dp.toPx(), 18.5.dp.toPx())
                    lineTo(14.dp.toPx(), 8.5.dp.toPx())
                    lineTo(14.dp.toPx(), 3.dp.toPx())
                }
                drawPath(
                    path = flask,
                    color = tint,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                drawLine(
                    color = tint,
                    start = Offset(7.5.dp.toPx(), 15.5.dp.toPx()),
                    end = Offset(16.5.dp.toPx(), 15.5.dp.toPx()),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = tint,
                    radius = 1.4.dp.toPx(),
                    center = Offset(12.dp.toPx(), 12.dp.toPx())
                )
            }
            TabPage.System -> {
                drawCircle(
                    color = tint,
                    radius = 5.5.dp.toPx(),
                    center = center,
                    style = Stroke(width = stroke)
                )
                val toothInner = 7.dp.toPx()
                val toothOuter = 9.5.dp.toPx()
                for (i in 0 until 8) {
                    val angle = (kotlin.math.PI / 4 * i).toFloat()
                    val dx = cos(angle)
                    val dy = sin(angle)
                    drawLine(
                        color = tint,
                        start = Offset(center.x + dx * toothInner, center.y + dy * toothInner),
                        end = Offset(center.x + dx * toothOuter, center.y + dy * toothOuter),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
                drawCircle(
                    color = tint,
                    radius = 1.8.dp.toPx(),
                    center = center
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DestinationGlyphPreview() {
    VipJamTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TopDestinations.forEach { tab ->
                DestinationGlyph(
                    destination = tab,
                    contentDescription = tab.label,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
