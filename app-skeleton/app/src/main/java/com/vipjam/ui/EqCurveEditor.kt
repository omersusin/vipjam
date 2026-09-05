package com.vipjam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vipjam.effect.VipJamEffects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
fun rememberDebouncedDispatcher(scope: CoroutineScope): (String, Long, suspend () -> Unit) -> Unit {
    val jobs = remember { mutableMapOf<String, Job>() }
    return remember(scope) {
        { key: String, delayMs: Long, action: suspend () -> Unit ->
            jobs[key]?.cancel()
            jobs[key] = scope.launch {
                delay(delayMs)
                action()
            }
        }
    }
}

internal fun parseEqBands(settingsJson: String): List<Double>? {
    val eq = runCatching { JSONObject(settingsJson).optJSONObject(VipJamEffects.EQ) }.getOrNull()
        ?: return null
    if (!eq.optBoolean("enable", false)) return null
    val bands = eq.optJSONArray("bands") ?: return null
    if (bands.length() == 0) return null
    return List(bands.length()) { bands.optDouble(it) }
}

@Composable
fun EqCurveEditorCard(
    bands: List<Double>,
    onBandChange: (Int, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    var selected by remember(bands.size) { mutableStateOf<Int?>(null) }
    val padLeftPx = with(density) { 32.dp.toPx() }
    val padRightPx = with(density) { 12.dp.toPx() }
    val padTopPx = with(density) { 12.dp.toPx() }
    val padBottomPx = with(density) { 24.dp.toPx() }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val zeroColor = MaterialTheme.colorScheme.outline
    val curveColor = MaterialTheme.colorScheme.primary
    val dotFill = MaterialTheme.colorScheme.primaryContainer
    val dotCore = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    val strokePx = with(density) { 3.dp.toPx() }
    val dotOuter = with(density) { 12.dp.toPx() }
    val dotOuterSel = with(density) { 16.dp.toPx() }
    val dotInner = with(density) { 6.dp.toPx() }
    val dotInnerSel = with(density) { 8.dp.toPx() }
    val labelGapPx = with(density) { 4.dp.toPx() }

    fun bandCount() = bands.size

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("EQ curve", style = MaterialTheme.typography.titleMedium)
            Text(
                "Drag a dot up/down to adjust that band",
                style = MaterialTheme.typography.labelMedium,
            )
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .pointerInput(bands.size, padLeftPx, padRightPx) {
                        detectTapGestures { offset ->
                            val idx = EqCurveMath.nearestBand(
                                offset.x, size.width.toFloat(),
                                padLeftPx, padRightPx, bandCount(),
                            )
                            val db = EqCurveMath.clampDb(
                                EqCurveMath.yToDb(
                                    offset.y, size.height.toFloat(),
                                    padTopPx, padBottomPx,
                                ),
                            )
                            selected = idx
                            onBandChange(idx, db.toDouble())
                        }
                    }
                    .pointerInput(bands.size, padLeftPx, padRightPx, padTopPx, padBottomPx) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                selected = EqCurveMath.nearestBand(
                                    offset.x, size.width.toFloat(),
                                    padLeftPx, padRightPx, bandCount(),
                                )
                            },
                            onDragEnd = { selected = null },
                            onDragCancel = { selected = null },
                            onDrag = { change, _ ->
                                change.consume()
                                val idx = selected ?: return@detectDragGestures
                                val db = EqCurveMath.clampDb(
                                    EqCurveMath.yToDb(
                                        change.position.y, size.height.toFloat(),
                                        padTopPx, padBottomPx,
                                    ),
                                )
                                onBandChange(idx, db.toDouble())
                            },
                        )
                    },
            ) {
                val w = size.width
                val h = size.height
                val dbSteps = listOf(12f, 6f, 0f, -6f, -12f)
                for (db in dbSteps) {
                    val y = EqCurveMath.dbToY(db, h, padTopPx, padBottomPx)
                    drawLine(
                        if (db == 0f) zeroColor else gridColor,
                        Offset(padLeftPx, y),
                        Offset(w - padRightPx, y),
                    )
                    val label = (if (db > 0f) "+" else "") + db.roundToInt().toString()
                    val layout = textMeasurer.measure(label, labelStyle)
                    drawText(
                        layout,
                        topLeft = Offset(0f, y - layout.size.height / 2f),
                    )
                }
                val n = bands.size
                for (i in 0 until n) {
                    val x = EqCurveMath.freqToX(
                        EqCurveMath.bandFreqHz(i), w, padLeftPx, padRightPx,
                    )
                    drawLine(
                        gridColor,
                        Offset(x, padTopPx),
                        Offset(x, h - padBottomPx),
                    )
                }
                val points = bands.mapIndexed { i, db ->
                    Offset(
                        EqCurveMath.freqToX(EqCurveMath.bandFreqHz(i), w, padLeftPx, padRightPx),
                        EqCurveMath.dbToY(db.toFloat(), h, padTopPx, padBottomPx),
                    )
                }
                drawPath(smoothPathThrough(points), curveColor, style = Stroke(width = strokePx))
                points.forEachIndexed { i, p ->
                    val isSel = i == selected
                    drawCircle(
                        dotFill,
                        radius = if (isSel) dotOuterSel else dotOuter,
                        center = p,
                    )
                    drawCircle(
                        dotCore,
                        radius = if (isSel) dotInnerSel else dotInner,
                        center = p,
                    )
                }
                for (i in 0 until n) {
                    val x = EqCurveMath.freqToX(
                        EqCurveMath.bandFreqHz(i), w, padLeftPx, padRightPx,
                    )
                    val label = EqCurveMath.shortFreqLabel(EqCurveMath.bandFreqHz(i))
                    val layout = textMeasurer.measure(label, labelStyle)
                    drawText(
                        layout,
                        topLeft = Offset(
                            x - layout.size.width / 2f,
                            h - padBottomPx + labelGapPx,
                        ),
                    )
                }
            }
        }
    }
}

private fun smoothPathThrough(pts: List<Offset>): Path {
    val path = Path()
    if (pts.isEmpty()) return path
    if (pts.size == 1) {
        path.moveTo(pts[0].x, pts[0].y)
        return path
    }
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 0 until pts.size - 1) {
        val p0 = pts.getOrElse(i - 1) { pts[i] }
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts.getOrElse(i + 2) { p2 }
        val c1x = p1.x + (p2.x - p0.x) / 6f
        val c1y = p1.y + (p2.y - p0.y) / 6f
        val c2x = p2.x - (p3.x - p1.x) / 6f
        val c2y = p2.y - (p3.y - p1.y) / 6f
        path.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
    }
    return path
}
