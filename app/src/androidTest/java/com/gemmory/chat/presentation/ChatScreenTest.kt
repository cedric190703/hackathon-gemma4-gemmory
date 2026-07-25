package com.gemmory.chat.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gemmory.chat.domain.ContextPolicy
import com.gemmory.chat.presentation.components.TAG_DOWNLOAD_BUTTON
import com.gemmory.chat.presentation.components.TAG_DOWNLOAD_PROGRESS
import com.gemmory.chat.presentation.components.TAG_ERROR_BANNER
import com.gemmory.chat.presentation.components.TAG_INPUT_FIELD
import com.gemmory.chat.presentation.components.TAG_INSTALL_PANEL
import com.gemmory.chat.presentation.components.TAG_SEND_BUTTON
import com.gemmory.chat.presentation.components.TAG_STOP_BUTTON
import com.gemmory.inference.EngineController
import com.gemmory.inference.FakeLlmEngine
import com.gemmory.inference.InferenceError
import com.gemmory.modelinstall.ModelCatalog
import com.gemmory.modelinstall.ModelInstallState
import com.gemmory.testing.FakeChatRepository
import com.gemmory.testing.FakeModelInstaller
import com.gemmory.testing.FakeSettingsRepository
import com.gemmory.ui.theme.GemmoryTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test

class ChatScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun viewModel(
        engine: FakeLlmEngine = FakeLlmEngine(),
        installer: FakeModelInstaller = FakeModelInstaller(),
        repository: FakeChatRepository = FakeChatRepository(),
    ) = ChatViewModel(
        repository = repository,
        installer = installer,
        engineController = EngineController(engine, scope),
        settingsRepository = FakeSettingsRepository(),
        contextPolicy = ContextPolicy(2560),
    )

    @Composable
    private fun Host(viewModel: ChatViewModel) {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val input by viewModel.input.collectAsStateWithLifecycle()
        val streaming by viewModel.streamingText.collectAsStateWithLifecycle()
        GemmoryTheme {
            ChatScreen(
                state = state,
                inputValue = input,
                streamingText = streaming,
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onNewConversation = viewModel::newConversation,
                onOpenSessions = {},
                onOpenSettings = {},
                onDownloadModel = { viewModel.downloadModel() },
                onImportModel = {},
                onCancelInstall = viewModel::cancelInstall,
                onRemoveModel = viewModel::removeModel,
                onLoadModel = viewModel::retryLoad,
                onEditMessage = viewModel::editMessage,
                onDeleteMessage = viewModel::deleteMessage,
                onRecoveryAction = viewModel::onRecoveryAction,
                onDismissBanner = viewModel::dismissBanner,
            )
        }
    }

    @Test
    fun modelNotInstalledShowsTheInstallPanel() {
        val viewModel = viewModel(
            installer = FakeModelInstaller(ModelInstallState.NotInstalled(ModelCatalog.default)),
        )
        composeRule.setContent { Host(viewModel) }

        composeRule.onNodeWithTag(TAG_INSTALL_PANEL).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_DOWNLOAD_BUTTON).assertIsDisplayed()
    }

    @Test
    fun downloadProgressIsShown() {
        val installer = FakeModelInstaller(ModelInstallState.NotInstalled(ModelCatalog.default))
        val viewModel = viewModel(installer = installer)
        composeRule.setContent { Host(viewModel) }

        composeRule.runOnIdle {
            installer.emit(
                ModelInstallState.Downloading(
                    descriptor = ModelCatalog.default,
                    downloadedBytes = 1_000_000_000,
                    totalBytes = 2_588_147_712,
                    bytesPerSecond = 5_000_000,
                    resumed = false,
                ),
            )
        }

        composeRule.onNodeWithTag(TAG_DOWNLOAD_PROGRESS).assertIsDisplayed()
        composeRule.onNodeWithText("Downloading model").assertIsDisplayed()
    }

    @Test
    fun sendingAMessageStreamsTheResponse() {
        val viewModel = viewModel(engine = FakeLlmEngine(listOf("Bonjour", " le", " monde")))
        composeRule.setContent { Host(viewModel) }
        composeRule.waitUntil(5_000) { viewModel.uiState.value.canSendPrompt }

        composeRule.onNodeWithTag(TAG_INPUT_FIELD).performTextInput("salut")
        composeRule.onNodeWithTag(TAG_SEND_BUTTON).performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTextSafe("Bonjour le monde")
        }
        composeRule.onNodeWithText("salut").assertIsDisplayed()
    }

    @Test
    fun theStopButtonReplacesSendWhileGenerating() {
        val viewModel = viewModel(engine = FakeLlmEngine(tokenDelayMs = 400))
        composeRule.setContent { Host(viewModel) }
        composeRule.waitUntil(5_000) { viewModel.uiState.value.canSendPrompt }

        composeRule.onNodeWithTag(TAG_INPUT_FIELD).performTextInput("slow please")
        composeRule.onNodeWithTag(TAG_SEND_BUTTON).performClick()

        composeRule.waitUntil(5_000) { viewModel.uiState.value.isGenerating }
        composeRule.onNodeWithTag(TAG_STOP_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_INPUT_FIELD).assertIsNotEnabled()

        composeRule.onNodeWithTag(TAG_STOP_BUTTON).performClick()
        composeRule.waitUntil(5_000) { !viewModel.uiState.value.isGenerating }
        composeRule.onNodeWithTag(TAG_SEND_BUTTON).assertIsDisplayed()
    }

    @Test
    fun initializationFailureShowsAnActionableBanner() {
        val viewModel = viewModel(
            engine = FakeLlmEngine(
                failInitializationWith = InferenceError.InitializationFailed("delegate error"),
            ),
        )
        composeRule.setContent { Host(viewModel) }

        composeRule.waitUntil(5_000) {
            viewModel.uiState.value.topLevelState == TopLevelState.RECOVERABLE_ERROR
        }
        composeRule.onNodeWithTag(TAG_ERROR_BANNER).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test
    fun startingANewConversationClearsTheHistory() {
        val viewModel = viewModel(engine = FakeLlmEngine(listOf("answer")))
        composeRule.setContent { Host(viewModel) }
        composeRule.waitUntil(5_000) { viewModel.uiState.value.canSendPrompt }

        composeRule.onNodeWithTag(TAG_INPUT_FIELD).performTextInput("first question")
        composeRule.onNodeWithTag(TAG_SEND_BUTTON).performClick()
        composeRule.waitUntil(5_000) { viewModel.uiState.value.messages.size == 2 }

        composeRule.onNodeWithTag(TAG_NEW_CONVERSATION).performClick()

        composeRule.waitUntil(5_000) { viewModel.uiState.value.messages.isEmpty() }
        composeRule.onNodeWithText("Everything runs on this device").assertIsDisplayed()
    }
}

/** `waitUntil` needs a boolean, and matcher assertions throw instead of returning one. */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextSafe(
    text: String,
): Boolean = onAllNodes(androidx.compose.ui.test.hasText(text, substring = true))
    .fetchSemanticsNodes()
    .isNotEmpty()
