package com.gemmory.chat.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gemmory.modelinstall.ModelInstallState
import com.gemmory.ui.theme.GemmaMark
import java.util.Locale

const val TAG_INSTALL_PANEL = "install_panel"
const val TAG_DOWNLOAD_BUTTON = "install_download"
const val TAG_IMPORT_BUTTON = "install_import"
const val TAG_DOWNLOAD_PROGRESS = "install_progress"

@Composable
fun ModelInstallPanel(
    state: ModelInstallState,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onLoad: () -> Unit,
    isLoadingEngine: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(TAG_INSTALL_PANEL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GemmaMark(Modifier.size(84.dp))
        Spacer(Modifier.height(20.dp))
        when (state) {
            is ModelInstallState.NotInstalled -> {
                Text(state.descriptor.displayName, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "The model is about ${state.descriptor.sizeBytes.toGb()} GB. It is " +
                        "downloaded once, verified with a SHA-256 checksum, and then everything " +
                        "runs offline.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDownload, modifier = Modifier.testTag(TAG_DOWNLOAD_BUTTON)) {
                    Text("Download model")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onImport, modifier = Modifier.testTag(TAG_IMPORT_BUTTON)) {
                    Text("Import .litertlm file")
                }
            }

            is ModelInstallState.Downloading -> InstallProgress(
                title = if (state.resumed) "Resuming download" else "Downloading model",
                detail = "${state.downloadedBytes.toGb()} GB of ${state.totalBytes.toGb()} GB" +
                    if (state.bytesPerSecond > 0) "  ·  ${state.bytesPerSecond.toMbPerSecond()} MB/s" else "",
                fraction = state.fraction,
                onCancel = onCancel,
            )

            is ModelInstallState.Importing -> InstallProgress(
                title = "Importing model file",
                detail = "${state.copiedBytes.toGb()} GB copied",
                fraction = state.totalBytes?.takeIf { it > 0 }
                    ?.let { (state.copiedBytes.toFloat() / it).coerceIn(0f, 1f) },
                onCancel = onCancel,
            )

            is ModelInstallState.Verifying -> InstallProgress(
                title = "Verifying checksum",
                detail = "${(state.fraction * 100).toInt()}%",
                fraction = state.fraction,
                onCancel = null,
            )

            is ModelInstallState.Installed -> {
                Text("Model installed", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${state.descriptor.displayName} · ${state.sizeBytes.toGb()} GB",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                if (isLoadingEngine) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Loading the model into memory…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Button(onClick = onLoad) { Text("Load model") }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRemove) { Text("Remove model") }
            }

            is ModelInstallState.Failed -> {
                Text("Installation failed", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDownload, modifier = Modifier.testTag(TAG_DOWNLOAD_BUTTON)) {
                        Text(if (state.resumableBytes > 0) "Resume download" else "Try again")
                    }
                    OutlinedButton(onClick = onImport, modifier = Modifier.testTag(TAG_IMPORT_BUTTON)) {
                        Text("Import file")
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallProgress(
    title: String,
    detail: String,
    fraction: Float?,
    onCancel: (() -> Unit)?,
) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().testTag(TAG_DOWNLOAD_PROGRESS),
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag(TAG_DOWNLOAD_PROGRESS))
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = detail,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (onCancel != null) {
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

internal fun Long.toGb(): String = String.format(Locale.US, "%.2f", this / 1_000_000_000.0)

private fun Long.toMbPerSecond(): String = String.format(Locale.US, "%.1f", this / 1_000_000.0)
