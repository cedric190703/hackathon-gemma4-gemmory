package com.gemmory.chat.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gemmory.chat.domain.ChatMessage
import com.gemmory.chat.domain.ChatRepository
import com.gemmory.chat.domain.ContextPolicy
import com.gemmory.chat.domain.MessageRole
import com.gemmory.chat.domain.MessageStatus
import com.gemmory.core.logging.AppLog
import com.gemmory.inference.EngineController
import com.gemmory.inference.EngineState
import com.gemmory.inference.GenerationEvent
import com.gemmory.inference.GenerationOptions
import com.gemmory.inference.InferenceError
import com.gemmory.modelinstall.ModelInstallState
import com.gemmory.modelinstall.ModelInstaller
import com.gemmory.settings.AppSettings
import com.gemmory.settings.SettingsRepository
import com.gemmory.vault.domain.VaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Single coordinator for model installation, engine lifecycle and chat.
 *
 * It never touches LiteRT-LM types: everything goes through [EngineController]
 * and [com.gemmory.inference.LocalLlmEngine].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val repository: ChatRepository,
    private val vaultRepository: VaultRepository,
    private val installer: ModelInstaller,
    private val engineController: EngineController,
    private val settingsRepository: SettingsRepository,
    private val contextPolicy: ContextPolicy,
) : ViewModel() {

    private val engine = engineController.engine

    private val conversationId = MutableStateFlow<String?>(null)
    private val droppedContext = MutableStateFlow(0)
    private val banner = MutableStateFlow<ErrorBanner?>(null)
    private val generating = MutableStateFlow(false)
    private val streamingMessageId = MutableStateFlow<String?>(null)

    /**
     * Streamed text is exposed separately from [uiState] and updated at most
     * every [STREAM_UPDATE_INTERVAL_MS], so a fast token stream cannot force the
     * whole screen to recompose per token.
     */
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private var generationJob: Job? = null

    /** Conversation id whose history has already been replayed into the engine. */
    private var preparedConversationId: String? = null

    private val messagesFlow = conversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.observeMessages(id)
    }

    val uiState: StateFlow<ChatUiState> = combine(
        combine(installer.state, engine.diagnostics, generating, ::Triple),
        combine(messagesFlow, repository.observeSessions(), conversationId, ::Triple),
        combine(banner, droppedContext, streamingMessageId, ::Triple),
    ) { engineTriple, chatTriple, uiTriple ->
        val (installState, diagnostics, isGenerating) = engineTriple
        val (messages, sessions, activeId) = chatTriple
        val (errorBanner, dropped, streamId) = uiTriple

        val topLevel = resolveTopLevelState(installState, diagnostics.state, isGenerating)
        ChatUiState(
            topLevelState = topLevel,
            installState = installState,
            engineState = diagnostics.state,
            conversationId = activeId,
            title = sessions.firstOrNull { it.id == activeId }?.title.orEmpty(),
            messages = messages,
            streamingMessageId = streamId,
            sessions = sessions,
            droppedContextMessages = dropped,
            errorBanner = errorBanner ?: implicitBanner(installState, diagnostics.state),
            diagnostics = diagnostics,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch {
            installer.refresh()
            repository.repairUnfinishedMessages()
            openMostRecentOrNew()
        }
        viewModelScope.launch {
            settingsRepository.settings.collectLatest { _settings.value = it }
        }
        viewModelScope.launch {
            // Load the engine as soon as a verified model exists.
            installer.state.collectLatest { state ->
                if (state is ModelInstallState.Installed) engineController.ensureLoaded(state.path)
            }
        }
    }

    // ------------------------------------------------------------------ chat

    fun onInputChange(value: String) {
        _input.value = value
    }

    fun send() {
        val text = _input.value.trim()
        if (text.isEmpty()) return
        if (!uiState.value.canSendPrompt) {
            banner.value = ErrorBanner("Wait until the model is ready before sending a prompt.")
            return
        }
        if (generating.value) {
            banner.value = ErrorPresentation.forInference(InferenceError.AlreadyGenerating)
            return
        }

        _input.value = ""
        banner.value = null
        generating.value = true

        generationJob = viewModelScope.launch {
            val id = conversationId.value ?: repository.createSession().also {
                conversationId.value = it.id
            }.id

            repository.appendMessage(id, MessageRole.USER, text, MessageStatus.COMPLETE)
            val assistant = repository.appendMessage(
                conversationId = id,
                role = MessageRole.ASSISTANT,
                content = "",
                status = MessageStatus.GENERATING,
            )
            streamingMessageId.value = assistant.id
            _streamingText.value = ""

            try {
                prepareEngineConversation(id, excludeMessageId = assistant.id)
                streamInto(assistant.id, id, text)
            } catch (ce: CancellationException) {
                persistPartial(assistant.id, MessageStatus.CANCELLED, "Stopped")
                throw ce
            } finally {
                streamingMessageId.value = null
                _streamingText.value = ""
                generating.value = false
            }
        }
    }

    fun sendVaultQuestion() {
        val text = _input.value.trim()
        if (text.isEmpty()) return
        if (!uiState.value.canSendPrompt) {
            banner.value = ErrorBanner("Wait until the vault agent is ready before sending a question.")
            return
        }
        if (generating.value) {
            banner.value = ErrorPresentation.forInference(InferenceError.AlreadyGenerating)
            return
        }

        _input.value = ""
        banner.value = null
        generating.value = true

        generationJob = viewModelScope.launch {
            val id = conversationId.value ?: repository.createSession().also {
                conversationId.value = it.id
            }.id

            repository.appendMessage(id, MessageRole.USER, text, MessageStatus.COMPLETE)
            val assistant = repository.appendMessage(
                conversationId = id,
                role = MessageRole.ASSISTANT,
                content = "",
                status = MessageStatus.GENERATING,
            )
            streamingMessageId.value = assistant.id
            _streamingText.value = ""

            try {
                val answer = vaultRepository.answerVaultQuestion(id, text)
                _streamingText.value = answer
                repository.updateMessage(
                    messageId = assistant.id,
                    content = answer,
                    status = MessageStatus.COMPLETE,
                )
            } catch (ce: CancellationException) {
                persistPartial(assistant.id, MessageStatus.CANCELLED, "Stopped")
                throw ce
            } catch (throwable: Throwable) {
                banner.value = ErrorBanner(throwable.message ?: "Unable to answer from the vault.")
                repository.updateMessage(
                    messageId = assistant.id,
                    content = "",
                    status = MessageStatus.FAILED,
                    errorText = "Vault answer failed",
                )
            } finally {
                streamingMessageId.value = null
                _streamingText.value = ""
                generating.value = false
            }
        }
    }

    private suspend fun streamInto(assistantMessageId: String, conversationId: String, prompt: String) {
        val builder = StringBuilder()
        var lastUiUpdate = 0L
        var lastCheckpoint = System.currentTimeMillis()
        var finished = false

        engine.generate(conversationId, prompt, GenerationOptions.Default).collect { event ->
            when (event) {
                GenerationEvent.Started -> Unit

                is GenerationEvent.Token -> {
                    builder.append(event.text)
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdate >= STREAM_UPDATE_INTERVAL_MS) {
                        lastUiUpdate = now
                        _streamingText.value = builder.toString()
                    }
                    // Periodic checkpoint so process death cannot lose everything,
                    // while the row stays marked GENERATING (never COMPLETE).
                    if (now - lastCheckpoint >= CHECKPOINT_INTERVAL_MS) {
                        lastCheckpoint = now
                        repository.updateMessage(
                            messageId = assistantMessageId,
                            content = builder.toString(),
                            status = MessageStatus.GENERATING,
                        )
                    }
                }

                is GenerationEvent.Metrics -> Unit

                GenerationEvent.Completed -> {
                    finished = true
                    _streamingText.value = builder.toString()
                    repository.updateMessage(
                        messageId = assistantMessageId,
                        content = builder.toString(),
                        status = MessageStatus.COMPLETE,
                    )
                }

                GenerationEvent.Cancelled -> {
                    finished = true
                    repository.updateMessage(
                        messageId = assistantMessageId,
                        content = builder.toString(),
                        status = MessageStatus.CANCELLED,
                        errorText = "Stopped",
                    )
                }

                is GenerationEvent.Failed -> {
                    finished = true
                    banner.value = ErrorPresentation.forInference(event.error)
                    repository.updateMessage(
                        messageId = assistantMessageId,
                        content = builder.toString(),
                        status = MessageStatus.FAILED,
                        errorText = ErrorPresentation.shortLabel(event.error),
                    )
                    if (event.error.isFatalForSession) preparedConversationId = null
                }
            }
        }

        if (!finished) {
            // The flow ended without a terminal event (for example the engine was
            // torn down). Never leave a row stuck in GENERATING.
            persistPartial(assistantMessageId, MessageStatus.CANCELLED, "Interrupted")
        }
    }

    private suspend fun persistPartial(messageId: String, status: MessageStatus, label: String) {
        repository.updateMessage(
            messageId = messageId,
            content = _streamingText.value,
            status = status,
            errorText = label,
        )
    }

    fun stop() {
        viewModelScope.launch {
            engine.cancel()
            generationJob?.cancel()
        }
    }

    fun newConversation() {
        viewModelScope.launch {
            generationJob?.cancel()
            engine.cancel()
            val session = repository.createSession()
            conversationId.value = session.id
            droppedContext.value = 0
            banner.value = null
            preparedConversationId = null
            engine.resetConversation(session.id, emptyList())
            preparedConversationId = session.id
        }
    }

    fun openConversation(id: String) {
        if (conversationId.value == id) return
        viewModelScope.launch {
            generationJob?.cancel()
            engine.cancel()
            conversationId.value = id
            preparedConversationId = null
            banner.value = null
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            repository.deleteSession(id)
            if (conversationId.value == id) {
                preparedConversationId = null
                openMostRecentOrNew()
            }
        }
    }

    fun renameConversation(id: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            repository.renameSession(id, clean)
        }
    }

    fun editMessage(messageId: String, messageConversationId: String, content: String) {
        val clean = content.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            repository.updateMessageContent(messageConversationId, messageId, clean)
            if (conversationId.value == messageConversationId) {
                preparedConversationId = null
            }
        }
    }

    fun deleteMessage(messageId: String, messageConversationId: String) {
        viewModelScope.launch {
            repository.deleteMessage(messageConversationId, messageId)
            if (conversationId.value == messageConversationId) {
                preparedConversationId = null
            }
        }
    }

    fun dismissBanner() {
        banner.value = null
    }

    // --------------------------------------------------------- model install

    fun downloadModel(allowMetered: Boolean = _settings.value.allowMeteredDownload) {
        banner.value = null
        installer.startDownload(allowMetered)
    }

    fun importModel(uri: Uri) {
        banner.value = null
        installer.startImport(uri)
    }

    fun cancelInstall() {
        installer.cancel()
    }

    fun removeModel() {
        viewModelScope.launch {
            generationJob?.cancel()
            engineController.unload()
            preparedConversationId = null
            installer.remove()
        }
    }

    fun retryLoad() {
        val state = installer.state.value
        if (state is ModelInstallState.Installed) {
            banner.value = null
            engineController.retry(state.path)
        }
    }

    fun setAllowMetered(allow: Boolean) {
        viewModelScope.launch { settingsRepository.setAllowMeteredDownload(allow) }
    }

    fun setDownloadUrl(url: String) {
        viewModelScope.launch { settingsRepository.setModelDownloadUrl(url) }
    }

    /**
     * Persists the backend choice. The running engine is intentionally left
     * alone: the new preference is applied on the next explicit reload, so a
     * settings tap can never tear down an in-flight generation.
     */
    fun setBackendPreference(preference: com.gemmory.inference.BackendPreference) {
        viewModelScope.launch { settingsRepository.setBackendPreference(preference) }
    }

    /** Unloads and reloads the engine, picking up the current backend preference. */
    fun reloadEngine() {
        val state = installer.state.value
        if (state !is ModelInstallState.Installed) return
        viewModelScope.launch {
            generationJob?.cancel()
            preparedConversationId = null
            engineController.unload()
            engineController.retry(state.path)
        }
    }

    fun onRecoveryAction(action: RecoveryAction) {
        when (action) {
            RecoveryAction.RETRY_DOWNLOAD -> downloadModel()
            RecoveryAction.RETRY_LOAD -> retryLoad()
            RecoveryAction.ALLOW_METERED -> {
                setAllowMetered(true)
                downloadModel(allowMetered = true)
            }

            RecoveryAction.REINSTALL_MODEL -> removeModel()
            RecoveryAction.IMPORT_FILE,
            RecoveryAction.FREE_SPACE,
            RecoveryAction.NONE,
            -> Unit
        }
    }

    // ---------------------------------------------------------------- internals

    private suspend fun openMostRecentOrNew() {
        val existing = repository.mostRecentSessionId()
        conversationId.value = existing ?: repository.createSession().id
        preparedConversationId = null
    }

    /**
     * Rebuilds the native conversation from persisted history the first time a
     * chat is used after opening it or after the engine was reloaded.
     */
    private suspend fun prepareEngineConversation(id: String, excludeMessageId: String?) {
        if (preparedConversationId == id) return
        val history: List<ChatMessage> = repository.listMessages(id)
            .filter { it.id != excludeMessageId }
            .dropLastWhile { it.role == MessageRole.USER && it.status == MessageStatus.COMPLETE }
        val bounded = contextPolicy.bound(history)
        droppedContext.value = bounded.droppedMessageCount
        engine.resetConversation(id, bounded.turns)
        preparedConversationId = id
        AppLog.d(TAG, "conversation prepared turns=${bounded.turns.size} dropped=${bounded.droppedMessageCount}")
    }

    private fun implicitBanner(
        installState: ModelInstallState,
        engineState: EngineState,
    ): ErrorBanner? = when {
        installState is ModelInstallState.Failed -> ErrorPresentation.forInstall(installState.error)
        engineState is EngineState.Failed -> ErrorPresentation.forInference(engineState.error)
        else -> null
    }

    private fun resolveTopLevelState(
        installState: ModelInstallState,
        engineState: EngineState,
        isGenerating: Boolean,
    ): TopLevelState = when (installState) {
        is ModelInstallState.NotInstalled -> TopLevelState.MODEL_MISSING
        is ModelInstallState.Importing -> TopLevelState.MODEL_IMPORTING
        is ModelInstallState.Downloading -> TopLevelState.MODEL_DOWNLOADING
        is ModelInstallState.Verifying -> TopLevelState.MODEL_VERIFYING
        is ModelInstallState.Failed -> TopLevelState.RECOVERABLE_ERROR
        is ModelInstallState.Installed -> when (engineState) {
            EngineState.Idle, EngineState.Closed -> TopLevelState.MODEL_LOADING
            EngineState.Loading -> TopLevelState.MODEL_LOADING
            is EngineState.Failed -> when (engineState.error) {
                is InferenceError.UnsupportedDevice -> TopLevelState.UNSUPPORTED_DEVICE
                else -> TopLevelState.RECOVERABLE_ERROR
            }

            is EngineState.Ready, EngineState.Generating ->
                if (isGenerating) TopLevelState.GENERATING else TopLevelState.CHAT_READY
        }
    }

    override fun onCleared() {
        generationJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TAG = "ChatViewModel"
        internal const val STREAM_UPDATE_INTERVAL_MS = 50L
        internal const val CHECKPOINT_INTERVAL_MS = 2_000L

        fun factory(
            repository: ChatRepository,
            vaultRepository: VaultRepository,
            installer: ModelInstaller,
            engineController: EngineController,
            settingsRepository: SettingsRepository,
            contextPolicy: ContextPolicy,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(
                    repository,
                    vaultRepository,
                    installer,
                    engineController,
                    settingsRepository,
                    contextPolicy,
                ) as T
        }
    }
}
