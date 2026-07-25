package com.gemmory.chat.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.gemmory.chat.domain.ChatSession
import java.text.DateFormat
import java.util.Date

const val TAG_CONVERSATIONS = "conversations_screen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    sessions: List<ChatSession>,
    activeId: String?,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.testTag(TAG_CONVERSATIONS),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Vault notes") },
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
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No notes yet.")
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(sessions, key = { it.id }) { session ->
                var renaming by remember(session.id) { mutableStateOf(false) }
                var deleting by remember(session.id) { mutableStateOf(false) }
                var title by remember(session.id, session.title) { mutableStateOf(session.title) }

                ListItem(
                    modifier = Modifier.clickable { onOpen(session.id) },
                    headlineContent = { Text(session.title) },
                    supportingContent = {
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(session.updatedAt)) +
                                if (session.id == activeId) "  ·  open" else "",
                        )
                    },
                    trailingContent = {
                        Row {
                            IconButton(
                                onClick = { renaming = true },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = "Rename note")
                            }
                            IconButton(
                                onClick = { deleting = true },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete note")
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        headlineColor = MaterialTheme.colorScheme.onSurface,
                        supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        trailingIconColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

                if (renaming) {
                    AlertDialog(
                        onDismissRequest = { renaming = false },
                        title = { Text("Rename note") },
                        text = {
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                singleLine = true,
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    onRename(session.id, title)
                                    renaming = false
                                },
                                enabled = title.isNotBlank(),
                            ) {
                                Text("Save")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    title = session.title
                                    renaming = false
                                },
                            ) {
                                Text("Cancel")
                            }
                        },
                    )
                }

                if (deleting) {
                    AlertDialog(
                        onDismissRequest = { deleting = false },
                        title = { Text("Delete note?") },
                        text = { Text("This removes the note and its messages from the vault.") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    deleting = false
                                    onDelete(session.id)
                                },
                            ) {
                                Text("Delete")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { deleting = false }) {
                                Text("Cancel")
                            }
                        },
                    )
                }
            }
        }
    }
}
