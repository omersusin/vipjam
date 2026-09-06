package com.vipjam.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vipjam.data.LiveProgEntry
import com.vipjam.data.LiveProgScripts
import com.vipjam.data.LiveProgStore
import com.vipjam.service.VipJamService
import com.vipjam.ui.components.EmptyState
import com.vipjam.ui.components.SectionHeader
import com.vipjam.ui.components.rememberReducedMotion
import com.vipjam.ui.components.staggeredDelayForIndex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private data class QueuedRun(val name: String, val atMillis: Long, val status: String)

private val EXAMPLES = listOf(
    "Stereo gain x2" to "@init\ngain = 2.0;\n\n@sample\nspl0 = spl0 * gain;\nspl1 = spl1 * gain;\n",
    "Soft clip guard" to "@init\nthresh = 0.9;\n\n@sample\nspl0 = max(-thresh, min(thresh, spl0));\nspl1 = max(-thresh, min(thresh, spl1));\n",
    "440Hz test sine" to "@init\nfreq = 440;\nphase = 0;\nstep = 2 * \$pi * freq / srate;\n\n@sample\nphase = phase + step;\nspl0 = 0.2 * sin(phase);\nspl1 = 0.2 * sin(phase);\n",
)

private fun timeOf(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))

@Composable
fun LiveProgTab(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { LiveProgStore(context.prefs) }
    val entries by store.entries.collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    var script by remember { mutableStateOf("") }
    var stdout by remember { mutableStateOf("") }
    var stderr by remember { mutableStateOf("") }
    var lastRun by remember { mutableStateOf("") }
    var queue by remember { mutableStateOf(emptyList<QueuedRun>()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    val reducedMotion = rememberReducedMotion()

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun runScript(runName: String, text: String) {
        val label = runName.trim().ifBlank { "untitled" }
        val errors = LiveProgScripts.validate(text)
        lastRun = label
        if (text.isBlank()) {
            stdout = ""
            stderr = "empty script: nothing to run"
            queue = listOf(QueuedRun(label, System.currentTimeMillis(), "rejected: empty")) + queue
            return
        }
        if (errors.isNotEmpty()) {
            stdout = ""
            stderr = errors.joinToString("\n")
            queue = listOf(QueuedRun(label, System.currentTimeMillis(), "rejected: validation failed")) + queue
            return
        }
        try {
            VipJamService.dispatchScript(context, text)
            stdout = "sent \"$label\" (${text.length} chars) at ${timeOf(System.currentTimeMillis())}: driver ack pending"
            stderr = ""
            queue = listOf(QueuedRun(label, System.currentTimeMillis(), "sent: awaiting driver ack")) + queue
        } catch (e: Exception) {
            stdout = ""
            stderr = "send failed: ${e.message}"
            queue = listOf(QueuedRun(label, System.currentTimeMillis(), "failed: send error")) + queue
        }
    }

    val errors = LiveProgScripts.validate(script)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(title = "LiveProg")
        }
        item {
            Text(
                "Run validates the script then sends it to the driver (alloc / chunks / commit). Queue below tracks send status.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SectionHeader(title = "Examples")
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EXAMPLES.forEach { (title, body) ->
                    OutlinedButton(
                        onClick = {
                            name = title
                            script = body
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Load: $title")
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Script name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = script,
                onValueChange = { script = it },
                label = { Text("@init / @sample EEL script") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 8,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
        item {
            if (errors.isEmpty() && script.isNotBlank()) {
                Text(
                    "Valid",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                errors.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { runScript(name, script) },
                    enabled = script.isNotBlank(),
                ) {
                    Text("Run")
                }
                Button(
                    onClick = {
                        scope.launch {
                            store.save(LiveProgEntry(name.trim(), script))
                                .onSuccess {
                                    message("Saved $name")
                                    name = ""
                                    script = ""
                                }
                                .onFailure { message("Invalid: ${it.message}") }
                        }
                    },
                    enabled = name.isNotBlank() && errors.isEmpty(),
                ) {
                    Text("Save")
                }
                OutlinedButton(
                    onClick = {
                        script = "@init\n\n@sample\n"
                    },
                ) {
                    Text("Template")
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Output" + (lastRun.ifBlank { "" }.let { if (it.isEmpty()) "" else ": $it" }),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("stdout", style = MaterialTheme.typography.labelSmall)
                    Text(
                        stdout.ifBlank { "(no output yet — press Run)" },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = if (stdout.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Text("stderr", style = MaterialTheme.typography.labelSmall)
                    Text(
                        stderr.ifBlank { "(no errors)" },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = if (stderr.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        item {
            SectionHeader(title = "Run queue (${queue.size})")
        }
        if (queue.isEmpty()) {
            item {
                EmptyState(
                    title = "Queue is empty",
                    body = "Sent runs land here with driver ack status.",
                )
            }
        } else {
            itemsIndexed(queue, key = { _, q -> q.atMillis to q.name }) { index, q ->
                StaggeredLiveProg(index, reducedMotion) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(q.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${timeOf(q.atMillis)} — ${q.status}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    }
                }
            }
        }
        item {
            SectionHeader(title = "Saved scripts (${entries.size})")
        }
        if (entries.isEmpty()) {
            item {
                EmptyState(
                    title = "No saved scripts yet",
                    body = "Write one above or load an example.",
                )
            }
        }
        itemsIndexed(entries, key = { _, it -> it.name }) { index, entry ->
            StaggeredLiveProg(index, reducedMotion) {
                Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                    val entryErrors = LiveProgScripts.validate(entry.script)
                    Text(
                        if (entryErrors.isEmpty()) "Valid" else entryErrors.joinToString("; "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (entryErrors.isEmpty()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    if (expanded == entry.name) {
                        Text(
                            entry.script,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                expanded = if (expanded == entry.name) null else entry.name
                            },
                        ) {
                            Text(if (expanded == entry.name) "Hide" else "View")
                        }
                        OutlinedButton(
                            onClick = {
                                name = entry.name
                                script = entry.script
                            },
                        ) {
                            Text("Edit")
                        }
                        OutlinedButton(
                            onClick = { runScript(entry.name, entry.script) },
                        ) {
                            Text("Run")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    store.delete(entry.name)
                                    message("Deleted ${entry.name}")
                                }
                            },
                        ) {
                            Text("Delete")
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun StaggeredLiveProg(index: Int, reducedMotion: Boolean, content: @Composable () -> Unit) {
    if (reducedMotion) {
        content()
    } else {
        val delay = staggeredDelayForIndex(index)
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(240, delayMillis = delay.toInt(), easing = LinearOutSlowInEasing)) +
                slideInVertically(tween(240, delayMillis = delay.toInt(), easing = LinearOutSlowInEasing)) { it / 4 },
            exit = fadeOut(),
        ) {
            content()
        }
    }
}
