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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gemmory.vault.domain.VaultNote

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    state: KnowledgeUiState,
    onSearch: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onOpenGraph: () -> Unit,
    onUndo: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var notePendingRemoval by remember { mutableStateOf<VaultNote?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Vault") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
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
                        state.notes.map { NoteListItem(it.noteId, it.title, it.path) }
                    } else {
                        state.searchResults.map { NoteListItem(it.noteId, it.title, it.path) }
                    }
                    items(entries, key = { it.noteId }) { item ->
                        Card(Modifier.fillMaxWidth().clickable { onOpenNote(item.noteId) }) {
                            Column(Modifier.padding(10.dp)) {
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.path, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(0.58f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.banner?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                val note = state.selectedNote
                if (note == null) {
                    Text("Select a note", style = MaterialTheme.typography.titleMedium)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(note.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton(
                            onClick = { notePendingRemoval = note },
                            enabled = !state.busy,
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove note from vault")
                        }
                    }
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

    notePendingRemoval?.let { note ->
        AlertDialog(
            onDismissRequest = { notePendingRemoval = null },
            title = { Text("Remove note from vault?") },
            text = { Text("This permanently removes ${note.title} from the vault.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        notePendingRemoval = null
                        onDeleteNote(note.id)
                    },
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { notePendingRemoval = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private data class NoteListItem(
    val noteId: String,
    val title: String,
    val path: String,
)
