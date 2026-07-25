package com.gemmory.inbox.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gemmory.vault.presentation.KnowledgeUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    state: KnowledgeUiState,
    onCapture: (String) -> Unit,
    onToggle: (String) -> Unit,
    onProcessSelected: () -> Unit,
    onProcessAll: () -> Unit,
    onApply: () -> Unit,
    onReject: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Inbox") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("Capture note") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onCapture(text)
                        text = ""
                    },
                    enabled = text.isNotBlank() && !state.busy,
                ) { Text("Save") }
                OutlinedButton(onClick = onProcessSelected, enabled = state.selectedInboxIds.isNotEmpty() && !state.busy) {
                    Text(if (state.busy) "Processing..." else "Process selected")
                }
                OutlinedButton(onClick = onProcessAll, enabled = state.inbox.isNotEmpty() && !state.busy) {
                    Text(if (state.busy) "Processing..." else "Process all")
                }
            }

            if (state.busy) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Processing notes. Please wait until it is done.", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }

            state.banner?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            state.pendingChangeSet?.let { changeSet ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Change preview", style = MaterialTheme.typography.titleMedium)
                        changeSet.validationErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                        changeSet.previews.forEach { preview ->
                            Text("${preview.operationLabel}: ${preview.path}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onApply, enabled = changeSet.canApply) { Text("Apply all") }
                            OutlinedButton(onClick = onReject) { Text("Reject") }
                        }
                    }
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.inbox, key = { it.id }) { entry ->
                    val selected = state.selectedInboxIds.contains(entry.id)
                    Card(
                        Modifier.fillMaxWidth().clickable { onToggle(entry.id) },
                    ) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(
                                if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = null,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(entry.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(entry.status.name, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
