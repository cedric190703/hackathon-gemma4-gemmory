package com.gemmory.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gemmory.chat.presentation.ChatScreen
import com.gemmory.chat.presentation.ChatViewModel
import com.gemmory.chat.presentation.ConversationsScreen
import com.gemmory.modelinstall.ModelInstallService
import com.gemmory.modelinstall.isBusy
import com.gemmory.settings.SettingsScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

private object Routes {
    const val CHAT = "chat"
    const val CONVERSATIONS = "conversations"
    const val SETTINGS = "settings"
}

@Composable
fun GemmoryNavHost(viewModel: ChatViewModel) {
    val navController = rememberNavController()
    val context = LocalContext.current

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.importModel(uri) }

    // A multi-gigabyte transfer must survive the app going to the background.
    val installBusy = state.installState?.isBusy == true
    LaunchedEffect(installBusy) {
        if (installBusy) ModelInstallService.start(context) else ModelInstallService.stop(context)
    }

    NavHost(navController = navController, startDestination = Routes.CHAT) {
        composable(Routes.CHAT) {
            ChatScreen(
                state = state,
                inputValue = input,
                streamingText = streamingText,
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onNewConversation = viewModel::newConversation,
                onOpenSessions = { navController.navigate(Routes.CONVERSATIONS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onDownloadModel = { viewModel.downloadModel() },
                onImportModel = { importLauncher.launch(arrayOf("*/*")) },
                onCancelInstall = viewModel::cancelInstall,
                onRemoveModel = viewModel::removeModel,
                onLoadModel = viewModel::retryLoad,
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
                onDelete = viewModel::deleteConversation,
                onBack = { navController.popBackStack() },
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
