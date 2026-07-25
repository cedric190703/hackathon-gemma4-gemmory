package com.gemmory.chat.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gemmory.BuildConfig
import com.gemmory.inference.EngineDiagnostics
import java.io.File
import java.util.Locale

const val TAG_DIAGNOSTICS = "diagnostics_panel"

/**
 * Debug-only performance panel.
 *
 * The absolute private path is never shown: only the file name and its parent
 * directory name, which is enough to debug without leaking the sandbox layout
 * into screenshots of a production build.
 */
@Composable
fun DiagnosticsPanel(diagnostics: EngineDiagnostics, modifier: Modifier = Modifier) {
    if (!BuildConfig.DEBUG) return

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(TAG_DIAGNOSTICS),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Diagnostics (debug build)", style = MaterialTheme.typography.labelLarge)
            Row("State", diagnostics.state::class.simpleName ?: "-")
            Row("Active backend", diagnostics.selectedBackend?.name ?: "not selected")
            diagnostics.backendFallback?.let { fallback ->
                Row("Backend attempts", fallback.attempted.joinToString(" → ") { it.name })
                if (fallback.failures.isNotEmpty()) {
                    Row(
                        "Backend failures",
                        fallback.failures.entries.joinToString("; ") { "${it.key}: ${it.value}" },
                    )
                }
            }
            Row("Model", diagnostics.modelPath?.let { redactPath(it) } ?: "-")
            Row("Model size", diagnostics.modelSizeBytes?.let { "${it.toGb()} GB" } ?: "-")
            Row("Init time", diagnostics.initializationTimeMs?.let { "$it ms" } ?: "-")
            Row("Time to first token", diagnostics.lastTimeToFirstTokenMs?.let { "$it ms" } ?: "-")
            Row("Decode speed", diagnostics.lastTokensPerSecond?.let { "${it.round1()} tok/s" } ?: "-")
            Row("Prefill speed", diagnostics.lastPrefillTokensPerSecond?.let { "${it.round1()} tok/s" } ?: "-")
            Row("Context tokens", diagnostics.contextTokenCount?.toString() ?: "-")
            Row("Last error", diagnostics.lastError?.let { it::class.simpleName } ?: "none")
            Row("App version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Row("LiteRT-LM", diagnostics.runtimeVersion ?: "-")
        }
    }
}

@Composable
private fun Row(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun redactPath(path: String): String {
    val file = File(path)
    return "…/${file.parentFile?.name ?: ""}/${file.name}"
}

private fun Double.round1(): String = String.format(Locale.US, "%.1f", this)
