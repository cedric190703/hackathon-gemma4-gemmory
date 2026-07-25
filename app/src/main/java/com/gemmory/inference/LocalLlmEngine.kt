package com.gemmory.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Role of a replayed turn used to reconstruct a conversation. */
enum class TurnRole { USER, ASSISTANT }

/** One completed turn replayed into a rebuilt native conversation. */
data class ConversationTurn(val role: TurnRole, val text: String)

/** Explicit engine lifecycle state machine. */
sealed interface EngineState {
    data object Idle : EngineState
    data object Loading : EngineState
    data class Ready(val backend: BackendKind, val modelPath: String) : EngineState
    data object Generating : EngineState
    data class Failed(val error: InferenceError) : EngineState
    data object Closed : EngineState
}

/** Everything the debug diagnostics panel needs, with no LiteRT-LM types. */
data class EngineDiagnostics(
    val state: EngineState = EngineState.Idle,
    val selectedBackend: BackendKind? = null,
    val backendFallback: BackendFallback? = null,
    val modelPath: String? = null,
    val modelSizeBytes: Long? = null,
    val initializationTimeMs: Long? = null,
    val lastTimeToFirstTokenMs: Long? = null,
    val lastTokensPerSecond: Double? = null,
    val lastPrefillTokensPerSecond: Double? = null,
    val contextTokenCount: Int? = null,
    val lastError: InferenceError? = null,
    val runtimeVersion: String? = null,
)

/**
 * The only inference surface the ViewModel and Compose layer are allowed to see.
 * No LiteRT-LM class ever crosses this boundary.
 */
interface LocalLlmEngine {

    val diagnostics: StateFlow<EngineDiagnostics>

    /** Loads the model. Must never be called from the main thread. */
    suspend fun initialize(modelPath: String)

    /**
     * Streams a response for [prompt] inside [conversationId].
     *
     * Exactly one generation may run at a time; a concurrent call terminates
     * with [GenerationEvent.Failed] carrying [InferenceError.AlreadyGenerating].
     */
    fun generate(
        conversationId: String,
        prompt: String,
        options: GenerationOptions = GenerationOptions.Default,
    ): Flow<GenerationEvent>

    /** Cooperatively cancels the running generation, if any. */
    suspend fun cancel()

    /**
     * Discards native state for [conversationId] and replays [history].
     *
     * [history] is expected to already be trimmed to the context budget by
     * [com.gemmory.chat.domain.ContextPolicy].
     * [options] are applied when the native conversation is created.
     */
    suspend fun resetConversation(
        conversationId: String,
        history: List<ConversationTurn> = emptyList(),
        options: GenerationOptions = GenerationOptions.Default,
    )

    /** Releases all native resources. The instance is unusable afterwards. */
    suspend fun close()
}
