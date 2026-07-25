package com.gemmory.chat.presentation

import com.gemmory.chat.domain.ChatMessage
import com.gemmory.chat.domain.ChatSession
import com.gemmory.inference.EngineDiagnostics
import com.gemmory.inference.EngineState
import com.gemmory.modelinstall.ModelInstallState

/** The explicit top-level states of the application. */
enum class TopLevelState {
    MODEL_MISSING,
    MODEL_IMPORTING,
    MODEL_DOWNLOADING,
    MODEL_VERIFYING,
    MODEL_READY_UNLOADED,
    MODEL_LOADING,
    CHAT_READY,
    GENERATING,
    RECOVERABLE_ERROR,
    UNSUPPORTED_DEVICE,
}

/** What the user can do to get out of an error state. */
enum class RecoveryAction {
    NONE,
    RETRY_DOWNLOAD,
    RETRY_LOAD,
    IMPORT_FILE,
    REINSTALL_MODEL,
    FREE_SPACE,
    ALLOW_METERED,
}

data class ErrorBanner(
    val message: String,
    val actionLabel: String? = null,
    val action: RecoveryAction = RecoveryAction.NONE,
)

data class ChatUiState(
    val topLevelState: TopLevelState = TopLevelState.MODEL_LOADING,
    val installState: ModelInstallState? = null,
    val engineState: EngineState = EngineState.Idle,
    val conversationId: String? = null,
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val streamingMessageId: String? = null,
    val sessions: List<ChatSession> = emptyList(),
    val droppedContextMessages: Int = 0,
    val errorBanner: ErrorBanner? = null,
    val diagnostics: EngineDiagnostics = EngineDiagnostics(),
) {
    val isGenerating: Boolean get() = topLevelState == TopLevelState.GENERATING

    /** Input is only enabled when the engine can actually accept a prompt. */
    val canSendPrompt: Boolean get() = topLevelState == TopLevelState.CHAT_READY

    val showsChat: Boolean
        get() = topLevelState == TopLevelState.CHAT_READY ||
            topLevelState == TopLevelState.GENERATING ||
            topLevelState == TopLevelState.MODEL_LOADING
}
