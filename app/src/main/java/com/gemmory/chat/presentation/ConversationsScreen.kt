package com.gemmory.chat.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.testTag(TAG_CONVERSATIONS),
        topBar = {
            TopAppBar(
                title = { Text("Conversations") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No conversations yet.")
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(sessions, key = { it.id }) { session ->
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
                        IconButton(onClick = { onDelete(session.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete conversation")
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
