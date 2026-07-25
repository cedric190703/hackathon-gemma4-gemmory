package com.gemmory.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gemmory.inference.BackendPreference
import com.gemmory.modelinstall.ModelCatalog
import com.gemmory.modelinstall.ModelInstallState

const val TAG_SETTINGS_SCREEN = "settings_screen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    installState: ModelInstallState?,
    onBack: () -> Unit,
    onBackendChange: (BackendPreference) -> Unit,
    onDownloadUrlChange: (String) -> Unit,
    onAllowMeteredChange: (Boolean) -> Unit,
    onRemoveModel: () -> Unit,
    onReloadModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var urlDraft by remember(settings.modelDownloadUrl) { mutableStateOf(settings.modelDownloadUrl) }

    Scaffold(
        modifier = modifier.testTag(TAG_SETTINGS_SCREEN),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SectionTitle("Inference backend")
            Text(
                text = "The runtime falls back automatically when a backend is unavailable. " +
                    "The backend that was actually selected is shown in the debug diagnostics panel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            BackendPreference.entries.forEach { preference ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = settings.backendPreference == preference,
                            onClick = { onBackendChange(preference) },
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.backendPreference == preference,
                        onClick = { onBackendChange(preference) },
                    )
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(preference.label(), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = preference.fallbackChain().joinToString(" → ") { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = "Changing the backend takes effect the next time the model is loaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(
                Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
            )

            SectionTitle("Model source")
            OutlinedTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it },
                label = { Text("Download URL") },
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                TextButton(onClick = { onDownloadUrlChange(urlDraft) }) { Text("Save URL") }
                TextButton(
                    onClick = {
                        urlDraft = ModelCatalog.default.downloadUrl
                        onDownloadUrlChange(ModelCatalog.default.downloadUrl)
                    },
                ) { Text("Reset to default") }
            }
            Text(
                text = "The downloaded file is always checked against the expected size " +
                    "(${ModelCatalog.default.sizeBytes} bytes) and SHA-256 digest, whatever the URL.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.allowMeteredDownload,
                    onCheckedChange = onAllowMeteredChange,
                )
                Text(
                    text = "Allow downloading over mobile data (about 2.6 GB)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            HorizontalDivider(
                Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
            )

            SectionTitle("Installed model")
            when (val state = installState) {
                is ModelInstallState.Installed -> {
                    Text(
                        text = "${state.descriptor.displayName} · ${state.sizeBytes} bytes",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row {
                        OutlinedButton(onClick = onReloadModel) { Text("Reload model") }
                        TextButton(onClick = onRemoveModel) { Text("Remove model") }
                    }
                }

                else -> Text("No model installed.", style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider(
                Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
            )

            SectionTitle("Privacy")
            Text(
                text = "Prompts and responses are processed entirely on this device and are never " +
                    "sent anywhere. The only network access this app performs is downloading the " +
                    "model file from the URL above. After installation the app works in airplane mode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(
                Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
            )

            SectionTitle("Model licence")
            Text(
                text = "${ModelCatalog.default.displayName} is distributed under the " +
                    "${ModelCatalog.default.licenseName}. Source: ${ModelCatalog.default.sourceUrl}. " +
                    "Licence: ${ModelCatalog.default.licenseUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

private fun BackendPreference.label(): String = when (this) {
    BackendPreference.AUTO -> "Automatic (recommended)"
    BackendPreference.GPU_ONLY -> "GPU only"
    BackendPreference.CPU_ONLY -> "CPU only"
    BackendPreference.NPU_FIRST -> "Prefer NPU"
}
