package com.gemmory.chat.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gemmory.chat.domain.MessageStatus
import com.gemmory.chat.presentation.components.ChatInputBar
import com.gemmory.chat.presentation.components.ContextTrimmedNotice
import com.gemmory.chat.presentation.components.DiagnosticsPanel
import com.gemmory.chat.presentation.components.EmptyConversationHint
import com.gemmory.chat.presentation.components.ErrorBannerCard
import com.gemmory.chat.presentation.components.MessageBubble
import com.gemmory.chat.presentation.components.ModelInstallPanel
import com.gemmory.modelinstall.ModelInstallState
import kotlinx.coroutines.flow.distinctUntilChanged

const val TAG_MESSAGE_LIST = "message_list"
const val TAG_NEW_CONVERSATION = "new_conversation"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    inputValue: String,
    streamingText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit,
    onDownloadModel: () -> Unit,
    onImportModel: () -> Unit,
    onCancelInstall: () -> Unit,
    onRemoveModel: () -> Unit,
    onLoadModel: () -> Unit,
    onRecoveryAction: (RecoveryAction) -> Unit,
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.title.ifBlank { "Gemmory" },
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onOpenSessions) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Conversations")
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNewConversation,
                        modifier = Modifier.testTag(TAG_NEW_CONVERSATION),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "New conversation")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            if (state.showsChat) {
                ChatInputBar(
                    value = inputValue,
                    onValueChange = onInputChange,
                    onSend = onSend,
                    onStop = onStop,
                    isGenerating = state.isGenerating,
                    enabled = state.canSendPrompt,
                    placeholder = when (state.topLevelState) {
                        TopLevelState.MODEL_LOADING -> "Loading the model…"
                        TopLevelState.GENERATING -> "Generating…"
                        else -> "Message Gemma 4"
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.errorBanner?.let { banner ->
                ErrorBannerCard(
                    banner = banner,
                    onAction = { onRecoveryAction(banner.action) },
                    onDismiss = onDismissBanner,
                )
            }

            if (state.showsChat) {
                if (state.topLevelState == TopLevelState.MODEL_LOADING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        text = "Loading Gemma 4 E2B into memory…",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                DiagnosticsPanel(state.diagnostics)
                MessageList(
                    state = state,
                    streamingText = streamingText,
                    modifier = Modifier.weight(1f),
                )
            } else {
                ModelInstallPanel(
                    state = state.installState ?: ModelInstallState.NotInstalled(
                        com.gemmory.modelinstall.ModelCatalog.default,
                    ),
                    onDownload = onDownloadModel,
                    onImport = onImportModel,
                    onCancel = onCancelInstall,
                    onRemove = onRemoveModel,
                    onLoad = onLoadModel,
                    isLoadingEngine = state.topLevelState == TopLevelState.MODEL_LOADING,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MessageList(
    state: ChatUiState,
    streamingText: String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Auto-scroll only while the user is already near the bottom, so scrolling
    // up to read history is never fought by the incoming stream.
    val stickToBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }

    LaunchedEffect(listState, state.messages.size) {
        if (stickToBottom && state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    LaunchedEffect(state.streamingMessageId) {
        if (state.streamingMessageId == null) return@LaunchedEffect
        snapshotFlow { streamingText.length / 200 }
            .distinctUntilChanged()
            .collect {
                if (stickToBottom && state.messages.isNotEmpty()) {
                    listState.scrollToItem(state.messages.lastIndex)
                }
            }
    }

    if (state.messages.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyConversationHint()
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(TAG_MESSAGE_LIST),
        state = listState,
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (state.droppedContextMessages > 0) {
            item(key = "context-notice") {
                ContextTrimmedNotice(state.droppedContextMessages)
            }
        }
        items(items = state.messages, key = { it.id }) { message ->
            val isStreaming = message.id == state.streamingMessageId &&
                message.status == MessageStatus.GENERATING
            MessageBubble(
                message = message,
                textProvider = { if (isStreaming) streamingText else message.content },
            )
        }
    }
}
