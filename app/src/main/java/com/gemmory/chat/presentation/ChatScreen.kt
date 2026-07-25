package com.gemmory.chat.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    onOpenInbox: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenSettings: () -> Unit,
    onDownloadModel: () -> Unit,
    onImportModel: () -> Unit,
    onCancelInstall: () -> Unit,
    onRemoveModel: () -> Unit,
    onLoadModel: () -> Unit,
    onEditMessage: (String, String, String) -> Unit,
    onDeleteMessage: (String, String) -> Unit,
    onRecoveryAction: (RecoveryAction) -> Unit,
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocksWorkflow = state.topLevelState == TopLevelState.MODEL_LOADING

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.title.ifBlank { "New conversation" },
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = state.workflowLabel(),
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            ChatBottomBar(
                showInput = state.showsChat && !blocksWorkflow,
                inputValue = inputValue,
                onInputChange = onInputChange,
                onSend = onSend,
                onStop = onStop,
                isGenerating = state.isGenerating,
                canSendPrompt = state.canSendPrompt,
                placeholder = when (state.topLevelState) {
                    TopLevelState.MODEL_LOADING -> "Loading the model…"
                    TopLevelState.GENERATING -> "Generating…"
                    else -> "Message Gemmory"
                },
                blocksWorkflow = blocksWorkflow,
                onOpenSessions = onOpenSessions,
                onOpenInbox = onOpenInbox,
                onOpenVault = onOpenVault,
                onNewConversation = onNewConversation,
                onOpenSettings = onOpenSettings,
            )
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

            if (blocksWorkflow) {
                ModelLoadingPanel(Modifier.weight(1f))
            } else if (state.showsChat) {
                DiagnosticsPanel(state.diagnostics)
                MessageList(
                    state = state,
                    streamingText = streamingText,
                    onEditMessage = onEditMessage,
                    onDeleteMessage = onDeleteMessage,
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
private fun ChatBottomBar(
    showInput: Boolean,
    inputValue: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isGenerating: Boolean,
    canSendPrompt: Boolean,
    placeholder: String,
    blocksWorkflow: Boolean,
    onOpenSessions: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenVault: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (showInput) {
                ChatInputBar(
                    value = inputValue,
                    onValueChange = onInputChange,
                    onSend = onSend,
                    onStop = onStop,
                    isGenerating = isGenerating,
                    enabled = canSendPrompt,
                    placeholder = placeholder,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onOpenSessions,
                    enabled = !blocksWorkflow,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = "Notes",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = onOpenInbox,
                    enabled = !blocksWorkflow,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        Icons.Filled.Email,
                        contentDescription = "Inbox",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = onOpenVault,
                    enabled = !blocksWorkflow,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = "Vault",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = onOpenAsk,
                    enabled = !blocksWorkflow,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = "Ask Vault",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = onNewConversation,
                    enabled = !blocksWorkflow,
                    modifier = Modifier.size(52.dp).testTag(TAG_NEW_CONVERSATION),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "New note",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = onOpenSettings,
                    enabled = !blocksWorkflow,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelLoadingPanel(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                text = "Loading Gemma 4 E2B",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text(
                text = "The vault unlocks when the local model is ready.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun MessageList(
    state: ChatUiState,
    streamingText: String,
    onEditMessage: (String, String, String) -> Unit,
    onDeleteMessage: (String, String) -> Unit,
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
            val canManageNotes = state.topLevelState == TopLevelState.CHAT_READY
            MessageBubble(
                message = message,
                textProvider = { if (isStreaming) streamingText else message.content },
                onEdit = if (canManageNotes) {
                    { content -> onEditMessage(message.id, message.conversationId, content) }
                } else {
                    null
                },
                onDelete = if (canManageNotes) {
                    { onDeleteMessage(message.id, message.conversationId) }
                } else {
                    null
                },
            )
        }
    }
}

private fun ChatUiState.workflowLabel(): String = when (topLevelState) {
    TopLevelState.MODEL_MISSING -> "Install model"
    TopLevelState.MODEL_IMPORTING -> "Importing model"
    TopLevelState.MODEL_DOWNLOADING -> "Downloading model"
    TopLevelState.MODEL_VERIFYING -> "Verifying model"
    TopLevelState.MODEL_READY_UNLOADED,
    TopLevelState.MODEL_LOADING,
    -> "Preparing local model"
    TopLevelState.CHAT_READY -> "Notes and questions"
    TopLevelState.GENERATING -> "Answering"
    TopLevelState.RECOVERABLE_ERROR -> "Action needed"
    TopLevelState.UNSUPPORTED_DEVICE -> "Unsupported device"
}
