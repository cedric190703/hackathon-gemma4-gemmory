package com.gemmory.vault.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    state: KnowledgeUiState,
    onSearch: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenGraph: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Vault") },
                actions = {
                    IconButton(onClick = onOpenGraph) {
                        Icon(Icons.Filled.AccountTree, contentDescription = "Open graph window")
                    }
                    IconButton(onClick = onUndo) {
                        Icon(Icons.Filled.History, contentDescription = "Undo latest change")
                    }
                },
            )
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(0.42f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearch,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search vault") },
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val entries = if (state.searchQuery.isBlank()) {
                        state.notes.map { it.noteId to it.title to it.path }
                    } else {
                        state.searchResults.map { it.noteId to it.title to it.path }
                    }
                    items(entries, key = { it.first.first }) { item ->
                        val noteId = item.first.first
                        val title = item.first.second
                        val path = item.second
                        Card(Modifier.fillMaxWidth().clickable { onOpenNote(noteId) }) {
                            Column(Modifier.padding(10.dp)) {
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(path, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(0.58f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val note = state.selectedNote
                if (note == null) {
                    Text("Select a note", style = MaterialTheme.typography.titleMedium)
                } else {
                    Text(note.title, style = MaterialTheme.typography.titleLarge)
                    Text("${note.path}  rev ${note.revision}", style = MaterialTheme.typography.labelMedium)
                    LazyColumn {
                        item {
                            Text(
                                text = note.markdown,
                                modifier = Modifier.fillMaxWidth(),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
