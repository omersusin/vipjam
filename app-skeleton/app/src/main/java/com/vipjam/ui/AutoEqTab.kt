package com.vipjam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.vipjam.autoeq.AutoEq
import com.vipjam.autoeq.AutoEqCache
import com.vipjam.autoeq.AutoEqDownloader
import com.vipjam.autoeq.ParametricEq
import com.vipjam.data.PresetEntry
import com.vipjam.data.PresetStore
import com.vipjam.data.VipJamPrefs
import com.vipjam.dsp.VipJamDispatcher
import com.vipjam.service.VipJamService
import com.vipjam.ui.components.EmptyState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val KNOWN_SOURCES = listOf("oratory1990", "crinacle", "rtings")

private data class CachedProfile(
    val key: String,
    val fileName: String,
    val summary: String,
)

private fun biquadDb(c: DoubleArray, fHz: Double, fs: Int): Double {
    val w = 2.0 * PI * fHz / fs
    val cos1 = cos(w)
    val sin1 = sin(w)
    val cos2 = cos(2.0 * w)
    val sin2 = sin(2.0 * w)
    val br = c[0] + c[1] * cos1 + c[2] * cos2
    val bi = -(c[1] * sin1 + c[2] * sin2)
    val ar = 1.0 - c[3] * cos1 - c[4] * cos2
    val ai = c[3] * sin1 + c[4] * sin2
    val mag = sqrt(br * br + bi * bi) / max(sqrt(ar * ar + ai * ai), 1e-12)
    return 20.0 * log10(max(mag, 1e-12))
}

private fun sampleResponse(eq: ParametricEq, sampleRate: Int): List<Double> {
    return EqCurveMath.BAND_FREQS_HZ.map { f ->
        var total = eq.preampDb
        for (flt in eq.filters) {
            val c = AutoEq.rbjBiquad(flt.type, flt.fcHz, flt.gainDb, flt.q, sampleRate)
            total += biquadDb(c, f.toDouble(), sampleRate)
        }
        total.coerceIn(-EqCurveMath.MAX_DB.toDouble(), EqCurveMath.MAX_DB.toDouble())
    }
}

@Composable
private fun AutoEqPreviewCurve(values: List<Double>) {
    val density = LocalDensity.current
    val padLeftPx = with(density) { 32.dp.toPx() }
    val padRightPx = with(density) { 12.dp.toPx() }
    val padTopPx = with(density) { 12.dp.toPx() }
    val padBottomPx = with(density) { 12.dp.toPx() }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val zeroColor = MaterialTheme.colorScheme.outline
    val curveColor = MaterialTheme.colorScheme.primary
    val strokePx = with(density) { 3.dp.toPx() }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
    ) {
        val w = size.width
        val h = size.height
        for (db in listOf(12f, 6f, 0f, -6f, -12f)) {
            val y = EqCurveMath.dbToY(db, h, padTopPx, padBottomPx)
            drawLine(
                if (db == 0f) zeroColor else gridColor,
                Offset(padLeftPx, y),
                Offset(w - padRightPx, y),
            )
        }
        val points = values.mapIndexed { i, db ->
            Offset(
                EqCurveMath.freqToX(EqCurveMath.bandFreqHz(i), w, padLeftPx, padRightPx),
                EqCurveMath.dbToY(db.toFloat(), h, padTopPx, padBottomPx),
            )
        }
        for (i in 0 until points.size - 1) {
            drawLine(points[i], points[i + 1], curveColor, strokeWidth = strokePx)
        }
        points.forEach { drawCircle(curveColor, radius = strokePx * 1.5f, center = it) }
    }
}

@Composable
fun AutoEqTab(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val presetStore = remember { PresetStore(context.prefs) }
    var cacheGen by remember { mutableStateOf(0) }
    val cache = remember(cacheGen) { AutoEqCache(context) }
    val downloader = remember { AutoEqDownloader() }

    var fullUrl by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(KNOWN_SOURCES[0]) }
    var modelPath by remember { mutableStateOf("") }
    var sourceMenu by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var profiles by remember { mutableStateOf(emptyList<CachedProfile>()) }
    var downloading by remember { mutableStateOf(false) }
    var previewKey by remember { mutableStateOf<String?>(null) }
    var previewEq by remember { mutableStateOf<ParametricEq?>(null) }
    var previewBands by remember { mutableStateOf(emptyList<Double>()) }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    suspend fun refresh() {
        val list = withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "autoeq/profiles")
            val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
            files.map { f ->
                val text = try {
                    cache.loadProfileText(f.nameWithoutExtension)
                        ?: f.readText()
                } catch (e: Exception) {
                    null
                }
                val summary = if (text == null) {
                    "unreadable file"
                } else {
                    try {
                        val eq = AutoEq.parseParametric(text)
                        "Preamp ${eq.preampDb} dB, ${eq.filters.size} filters"
                    } catch (e: Exception) {
                        "unparseable: ${e.message}"
                    }
                }
                CachedProfile(f.nameWithoutExtension, f.name, summary)
            }
        }
        profiles = list
    }

    LaunchedEffect(cacheGen) { refresh() }

    fun builtRelativePath(): String {
        val p = modelPath.trim().trim('/')
        if (p.isEmpty()) return ""
        return if (p.endsWith(".txt", ignoreCase = true)) "results/$source/$p"
        else "results/$source/$p/ParametricEQ.txt"
    }

    fun resolvedUrl(): String {
        val u = fullUrl.trim()
        if (u.isNotEmpty()) return u
        val rel = builtRelativePath()
        if (rel.isEmpty()) return ""
        return downloader.profileUrl(rel)
    }

    fun cacheKeyFor(url: String): String {
        val rel = builtRelativePath()
        if (fullUrl.trim().isEmpty() && rel.isNotEmpty()) return "$source/$modelPath".trim()
        val base = AutoEq.BASE_URL
        if (url.startsWith(base)) return url.removePrefix(base).trim('/')
        return url
    }

    fun doDownload(url: String) {
        if (downloading) return
        downloading = true
        scope.launch {
            val text = withContext(Dispatchers.IO) { downloader.fetchText(url) }
            downloading = false
            if (text == null) {
                message("Download failed (bad URL, network, or >1MB)")
                return@launch
            }
            try {
                AutoEq.parseParametric(text)
            } catch (e: Exception) {
                message("Downloaded but invalid ParametricEQ: ${e.message}")
                return@launch
            }
            withContext(Dispatchers.IO) {
                try {
                    cache.saveProfileText(cacheKeyFor(url), text)
                } catch (e: Exception) {
                    null
                }
            }
            message("Saved AutoEq profile")
            refresh()
        }
    }

    fun preview(profile: CachedProfile) {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    cache.loadProfileText(profile.key)
                } catch (e: Exception) {
                    null
                }
            }
            if (text == null) {
                message("Cannot read ${profile.key}")
                return@launch
            }
            val eq = try {
                AutoEq.parseParametric(text)
            } catch (e: Exception) {
                message("Cannot preview: ${e.message}")
                return@launch
            }
            previewKey = profile.key
            previewEq = eq
            previewBands = withContext(Dispatchers.Default) { sampleResponse(eq, 48000) }
        }
    }

    fun applyCurve(key: String, values: List<Double>) {
        if (values.isEmpty()) return
        scope.launch {
            val all = try {
                presetStore.entries.first()
            } catch (_: Exception) {
                emptyList()
            }
            val activeName = try {
                context.prefs.data.first()[VipJamPrefs.ACTIVE_PRESET]
            } catch (_: Exception) {
                null
            }
            val target = all.find { it.name == activeName } ?: all.firstOrNull()
            if (target == null) {
                message("No preset installed to apply to")
                return@launch
            }
            val updated = try {
                val obj = JSONObject(target.settingsJson)
                val g = obj.optJSONObject("equalizer") ?: JSONObject()
                g.put("enable", true)
                g.put("bandCount", values.size)
                g.put("bands", JSONArray(values))
                obj.put("equalizer", g)
                obj.toString()
            } catch (e: Exception) {
                message("Apply failed: bad preset JSON")
                return@launch
            }
            presetStore.save(PresetEntry(target.name, updated))
                .onSuccess {
                    VipJamService.dispatchParam(context, VipJamDispatcher.P_EQ_ENABLE, 1)
                    values.forEachIndexed { i, db ->
                        VipJamService.dispatchParam(
                            context, VipJamDispatcher.F_EQ, i, db.roundToInt(),
                        )
                    }
                    message("Applied $key curve to ${target.name}")
                }
                .onFailure { message("Apply failed: ${it.message}") }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("AutoEq", style = MaterialTheme.typography.headlineLarge) }
        item {
            Text(
                "No bundled headphone index ships in this build: find a profile on autoeq.app, then paste its raw ParametricEQ.txt URL or pick a source + model path. Downloads are cached on-device and searchable below.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            OutlinedTextField(
                value = fullUrl,
                onValueChange = { fullUrl = it },
                label = { Text("Full raw URL (…/ParametricEQ.txt)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    OutlinedButton(onClick = { sourceMenu = true }) {
                        Text(source)
                    }
                    DropdownMenu(
                        expanded = sourceMenu,
                        onDismissRequest = { sourceMenu = false },
                    ) {
                        KNOWN_SOURCES.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s) },
                                onClick = { source = s; sourceMenu = false },
                            )
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = modelPath,
                onValueChange = { modelPath = it },
                label = { Text("Model path, e.g. over-ear/HD 600/HD 600") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            val url = resolvedUrl()
            Text(
                if (url.isEmpty()) "Enter a URL or a model path to build one."
                else url,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Button(
                onClick = { doDownload(resolvedUrl()) },
                enabled = !downloading && resolvedUrl().isNotBlank(),
            ) { Text(if (downloading) "Downloading…" else "Download") }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search headphones (model substring)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        val shown = if (search.isBlank()) profiles
            else profiles.filter { it.key.contains(search.trim(), ignoreCase = true) }
        if (profiles.isEmpty()) {
            item {
                EmptyState(
                    title = "No downloaded profiles yet",
                    body = "Download one above to browse, preview and apply.",
                )
            }
        } else if (shown.isEmpty()) {
            item {
                Text(
                    "No headphones match \"${search.trim()}\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (previewEq != null && previewBands.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Preview: ${previewKey ?: ""}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Preamp ${previewEq!!.preampDb} dB, ${previewEq!!.filters.size} filters, sampled at 48 kHz",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        AutoEqPreviewCurve(previewBands)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { applyCurve(previewKey ?: "", previewBands) }) {
                                Text("Apply to EQ")
                            }
                            OutlinedButton(
                                onClick = {
                                    previewKey = null
                                    previewEq = null
                                    previewBands = emptyList()
                                },
                            ) {
                                Text("Close")
                            }
                        }
                    }
                }
            }
        }
        items(shown, key = { it.fileName }) { p ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(p.key, style = MaterialTheme.typography.titleMedium)
                    Text(p.summary, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { preview(p) }) { Text("Preview") }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val text = withContext(Dispatchers.IO) {
                                        try {
                                            cache.loadProfileText(p.key)
                                        } catch (e: Exception) {
                                            null
                                        }
                                    }
                                    val eq = try {
                                        text?.let { AutoEq.parseParametric(it) }
                                    } catch (e: Exception) {
                                        null
                                    }
                                    if (eq == null) {
                                        message("Cannot apply ${p.key}")
                                        return@launch
                                    }
                                    val bands = withContext(Dispatchers.Default) {
                                        sampleResponse(eq, 48000)
                                    }
                                    applyCurve(p.key, bands)
                                }
                            },
                        ) { Text("Apply to EQ") }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        File(context.filesDir, "autoeq/profiles/${p.fileName}").delete()
                                    }
                                    if (previewKey == p.key) {
                                        previewKey = null
                                        previewEq = null
                                        previewBands = emptyList()
                                    }
                                    cacheGen++
                                    message("Deleted ${p.key}")
                                }
                            },
                        ) { Text("Delete") }
                    }
                }
            }
        }
    }
}
