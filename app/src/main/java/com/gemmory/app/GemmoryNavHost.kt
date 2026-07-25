package com.gemmory.app

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gemmory.chat.presentation.ChatScreen
import com.gemmory.chat.presentation.ChatViewModel
import com.gemmory.chat.presentation.ConversationsScreen
import com.gemmory.inbox.presentation.InboxScreen
import com.gemmory.modelinstall.ModelInstallService
import com.gemmory.modelinstall.isBusy
import com.gemmory.settings.SettingsScreen
import com.gemmory.ui.theme.GemmaBackdrop
import com.gemmory.vault.presentation.KnowledgeViewModel
import com.gemmory.vault.presentation.VaultScreen
import com.gemmory.vaultagent.presentation.AskVaultScreen

private object Routes {
    const val CHAT = "chat"
    const val CONVERSATIONS = "conversations"
    const val INBOX = "inbox"
    const val VAULT = "vault"
    const val ASK = "ask"
    const val SETTINGS = "settings"
}

@Composable
fun GemmoryNavHost(viewModel: ChatViewModel, knowledgeViewModel: KnowledgeViewModel) {
    val navController = rememberNavController()
    val context = LocalContext.current

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val knowledgeState by knowledgeViewModel.uiState.collectAsStateWithLifecycle()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.importModel(uri) }

    // A multi-gigabyte transfer must survive the app going to the background.
    val installBusy = state.installState?.isBusy == true
    LaunchedEffect(installBusy) {
        if (installBusy) ModelInstallService.start(context) else ModelInstallService.stop(context)
    }

    GemmaBackdrop {
        NavHost(
            navController = navController,
            startDestination = Routes.CHAT,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Routes.CHAT) {
                ChatScreen(
                    state = state,
                    inputValue = input,
                    streamingText = streamingText,
                    onInputChange = viewModel::onInputChange,
                    onSend = viewModel::sendVaultQuestion,
                    onStop = viewModel::stop,
                    onNewConversation = viewModel::newConversation,
                    onOpenSessions = { navController.navigate(Routes.CONVERSATIONS) },
                    onOpenInbox = { navController.navigate(Routes.INBOX) },
                    onOpenVault = { navController.navigate(Routes.VAULT) },
                    onOpenAsk = { navController.navigate(Routes.ASK) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onDownloadModel = { viewModel.downloadModel() },
                    onImportModel = { importLauncher.launch(arrayOf("*/*")) },
                    onCancelInstall = viewModel::cancelInstall,
                    onRemoveModel = viewModel::removeModel,
                    onLoadModel = viewModel::retryLoad,
                    onEditMessage = viewModel::editMessage,
                    onDeleteMessage = viewModel::deleteMessage,
                    onRecoveryAction = { action ->
                        if (action == com.gemmory.chat.presentation.RecoveryAction.IMPORT_FILE) {
                            importLauncher.launch(arrayOf("*/*"))
                        } else {
                            viewModel.onRecoveryAction(action)
                        }
                    },
                    onDismissBanner = viewModel::dismissBanner,
                )
            }

            composable(Routes.CONVERSATIONS) {
                ConversationsScreen(
                    sessions = state.sessions,
                    activeId = state.conversationId,
                    onOpen = { id ->
                        viewModel.openConversation(id)
                        navController.popBackStack()
                    },
                    onRename = viewModel::renameConversation,
                    onDelete = viewModel::deleteConversation,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.INBOX) {
                InboxScreen(
                    state = knowledgeState,
                    onCapture = knowledgeViewModel::capture,
                    onToggle = knowledgeViewModel::toggleInboxSelection,
                    onProcessSelected = knowledgeViewModel::processSelected,
                    onProcessAll = knowledgeViewModel::processAll,
                    onApply = knowledgeViewModel::applyPending,
                    onReject = knowledgeViewModel::rejectPending,
                )
            }

            composable(Routes.VAULT) {
                VaultScreen(
                    state = knowledgeState,
                    onSearch = knowledgeViewModel::setSearchQuery,
                    onOpenNote = knowledgeViewModel::openNote,
                    onOpenGraph = { context.startActivity(Intent(context, VaultGraphActivity::class.java)) },
                    onUndo = knowledgeViewModel::undoLatest,
                )
            }

            composable(Routes.ASK) {
                AskVaultScreen(
                    state = knowledgeState,
                    onAsk = knowledgeViewModel::ask,
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    settings = settings,
                    installState = state.installState,
                    onBack = { navController.popBackStack() },
                    onBackendChange = viewModel::setBackendPreference,
                    onDownloadUrlChange = viewModel::setDownloadUrl,
                    onAllowMeteredChange = viewModel::setAllowMetered,
                    onRemoveModel = viewModel::removeModel,
                    onReloadModel = viewModel::reloadEngine,
                )
            }
        }
    }
}
