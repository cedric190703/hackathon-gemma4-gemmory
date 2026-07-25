package com.gemmory.inference

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex

/**
 * Deterministic [LocalLlmEngine] for tests.
 *
 * Lives in a shared test source set so unit tests and Compose instrumented tests
 * can use it without packaging it into any app build.
 */
class FakeLlmEngine(
    private val tokens: List<String> = DEFAULT_TOKENS,
    private val tokenDelayMs: Long = 0,
    private val initializationDelayMs: Long = 0,
    private val failInitializationWith: InferenceError? = null,
    private val failGenerationWith: InferenceError? = null,
    /** When true, generation emits [GenerationEvent.Cancelled] instead of completing. */
    private val simulateCancellation: Boolean = false,
) : LocalLlmEngine {

    private val _diagnostics = MutableStateFlow(EngineDiagnostics(runtimeVersion = "fake"))
    override val diagnostics: StateFlow<EngineDiagnostics> = _diagnostics.asStateFlow()

    private val generationLock = Mutex()

    var initializeCallCount: Int = 0
        private set
    var closeCallCount: Int = 0
        private set
    var cancelCallCount: Int = 0
        private set
    var lastReplayedHistory: List<ConversationTurn> = emptyList()
        private set
    var resetCallCount: Int = 0
        private set
    val promptsReceived: MutableList<String> = mutableListOf()

    override suspend fun initialize(modelPath: String) {
        initializeCallCount++
        _diagnostics.value = _diagnostics.value.copy(
            state = EngineState.Loading,
            modelPath = modelPath,
        )
        if (initializationDelayMs > 0) delay(initializationDelayMs)

        val error = failInitializationWith
        _diagnostics.value = if (error != null) {
            _diagnostics.value.copy(state = EngineState.Failed(error), lastError = error)
        } else {
            _diagnostics.value.copy(
                state = EngineState.Ready(BackendKind.CPU, modelPath),
                selectedBackend = BackendKind.CPU,
                initializationTimeMs = 12,
            )
        }
    }

    override fun generate(
        conversationId: String,
        prompt: String,
        options: GenerationOptions,
    ): Flow<GenerationEvent> = flow {
        if (!generationLock.tryLock()) {
            emit(GenerationEvent.Failed(InferenceError.AlreadyGenerating))
            return@flow
        }
        try {
            if (_diagnostics.value.state !is EngineState.Ready) {
                emit(GenerationEvent.Failed(InferenceError.EngineNotReady))
                return@flow
            }
            promptsReceived += prompt
            emit(GenerationEvent.Started)

            failGenerationWith?.let { error ->
                emit(GenerationEvent.Failed(error))
                return@flow
            }

            for (token in tokens) {
                if (tokenDelayMs > 0) delay(tokenDelayMs)
                emit(GenerationEvent.Token(token))
            }

            if (simulateCancellation) {
                emit(GenerationEvent.Cancelled)
                return@flow
            }

            emit(GenerationEvent.Metrics(timeToFirstTokenMs = 10, tokensPerSecond = 42.0))
            emit(GenerationEvent.Completed)
        } finally {
            if (generationLock.isLocked) generationLock.unlock()
        }
    }

    override suspend fun cancel() {
        cancelCallCount++
    }

    override suspend fun resetConversation(
        conversationId: String,
        history: List<ConversationTurn>,
    ) {
        resetCallCount++
        lastReplayedHistory = history
    }

    override suspend fun close() {
        closeCallCount++
        _diagnostics.value = _diagnostics.value.copy(state = EngineState.Closed)
    }

    companion object {
        val DEFAULT_TOKENS = listOf("Hello", ", ", "world", "!")

        fun unsupportedBackend(): FakeLlmEngine = FakeLlmEngine(
            failInitializationWith = InferenceError.UnsupportedDevice(
                technicalDetail = "no GPU delegate",
                attemptedBackends = listOf("GPU", "CPU"),
            ),
        )
    }
}
