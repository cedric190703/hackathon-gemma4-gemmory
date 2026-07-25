package com.gemmory.inference

import com.gemmory.core.logging.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Application-scoped owner of the single [LocalLlmEngine].
 *
 * Loading lives here rather than in a ViewModel so a configuration change or a
 * screen leaving the back stack can never cancel initialization or trigger a
 * second load of a 2.5 GB model.
 */
class EngineController(
    val engine: LocalLlmEngine,
    private val scope: CoroutineScope,
) {
    private var loadJob: Job? = null
    private var loadedPath: String? = null

    @Volatile
    private var preparedConversationId: String? = null

    /** Idempotent. Loading the same path twice is a no-op. */
    fun ensureLoaded(modelPath: String) {
        if (loadJob?.isActive == true) return
        val state = engine.diagnostics.value.state
        if (loadedPath == modelPath && state is EngineState.Ready) return
        if (state is EngineState.Failed && loadedPath == modelPath) return

        preparedConversationId = null
        loadedPath = modelPath
        loadJob = scope.launch {
            AppLog.i(TAG, "loading model")
            engine.initialize(modelPath)
        }
    }

    /** Clears a previous failure so the user can explicitly retry. */
    fun retry(modelPath: String) {
        loadJob?.cancel()
        loadJob = null
        loadedPath = null
        preparedConversationId = null
        ensureLoaded(modelPath)
    }

    fun unload() {
        loadJob?.cancel()
        loadJob = null
        loadedPath = null
        preparedConversationId = null
        scope.launch { engine.close() }
    }

    fun isConversationPrepared(conversationId: String): Boolean =
        preparedConversationId == conversationId

    fun invalidatePreparedConversation(conversationId: String? = null) {
        if (conversationId == null || preparedConversationId == conversationId) {
            preparedConversationId = null
        }
    }

    suspend fun resetConversation(
        conversationId: String,
        history: List<ConversationTurn> = emptyList(),
    ) {
        engine.resetConversation(conversationId, history)
        preparedConversationId = when (engine.diagnostics.value.state) {
            is EngineState.Ready,
            EngineState.Generating,
            -> conversationId

            EngineState.Closed,
            is EngineState.Failed,
            EngineState.Idle,
            EngineState.Loading,
            -> null
        }
    }

    fun generate(
        conversationId: String,
        prompt: String,
        options: GenerationOptions = GenerationOptions.Default,
    ): Flow<GenerationEvent> = engine.generate(conversationId, prompt, options)

    suspend fun cancel() {
        engine.cancel()
    }

    private companion object {
        const val TAG = "EngineController"
    }
}
