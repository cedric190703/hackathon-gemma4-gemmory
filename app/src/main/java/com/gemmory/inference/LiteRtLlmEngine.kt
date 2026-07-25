package com.gemmory.inference

import android.content.Context
import com.gemmory.BuildConfig
import com.gemmory.core.dispatchers.AppDispatchers
import com.gemmory.core.logging.AppLog
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Real LiteRT-LM backed engine.
 *
 * Threading contract:
 *  * every native call runs on [AppDispatchers.inference] (one dedicated thread);
 *  * at most one [Engine] and one [Conversation] exist at any time;
 *  * [generationLock] guarantees a single in-flight generation.
 */
class LiteRtLlmEngine(
    private val context: Context,
    /**
     * Resolved on a background dispatcher at load time, so user settings can be
     * read from disk without ever blocking the main thread.
     */
    private val configProvider: suspend () -> InferenceConfig,
    private val dispatchers: AppDispatchers,
) : LocalLlmEngine {

    /** Config captured by the most recent successful [initialize]. */
    @Volatile
    private var config: InferenceConfig = InferenceConfig()

    private val lifecycleLock = Mutex()
    private val generationLock = Mutex()

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var activeConversationId: String? = null

    private val _diagnostics = MutableStateFlow(
        EngineDiagnostics(runtimeVersion = LITERT_LM_VERSION),
    )
    override val diagnostics: StateFlow<EngineDiagnostics> = _diagnostics.asStateFlow()

    @OptIn(ExperimentalApi::class)
    override suspend fun initialize(modelPath: String) = lifecycleLock.withLock {
        withContext(dispatchers.inference) {
            if (engine != null) {
                AppLog.d(TAG, "initialize() ignored: engine already loaded")
                return@withContext
            }

            config = configProvider()

            val modelFile = File(modelPath)
            if (!modelFile.isFile) {
                fail(InferenceError.ModelFileMissing(modelPath))
                return@withContext
            }

            _diagnostics.update {
                it.copy(
                    state = EngineState.Loading,
                    modelPath = modelPath,
                    modelSizeBytes = modelFile.length(),
                    lastError = null,
                )
            }

            ExperimentalFlags.enableBenchmark = BuildConfig.DEBUG

            val chain = config.backendPreference.fallbackChain()
            val failures = LinkedHashMap<BackendKind, String>()
            val startedAt = System.currentTimeMillis()

            for (kind in chain) {
                ExperimentalFlags.enableSpeculativeDecoding = kind == BackendKind.GPU
                val candidate = try {
                    Engine(
                        EngineConfig(
                            modelPath = modelPath,
                            backend = kind.toLiteRtBackend(context),
                            maxNumTokens = config.maxNumTokens,
                            cacheDir = context.cacheDir.absolutePath,
                        ),
                    )
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    failures[kind] = t.shortReason()
                    continue
                }

                try {
                    candidate.initialize()
                } catch (oom: OutOfMemoryError) {
                    candidate.closeQuietly()
                    fail(
                        InferenceError.OutOfMemory(
                            "not enough memory to load the model on the ${kind.name} backend",
                        ),
                    )
                    return@withContext
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    candidate.closeQuietly()
                    failures[kind] = t.shortReason()
                    AppLog.w(TAG, "backend ${kind.name} unavailable: ${t.shortReason()}")
                    continue
                }

                val elapsed = System.currentTimeMillis() - startedAt
                engine = candidate
                val fallback = BackendFallback(chain, kind, failures)
                _diagnostics.update {
                    it.copy(
                        state = EngineState.Ready(kind, modelPath),
                        selectedBackend = kind,
                        backendFallback = fallback,
                        initializationTimeMs = elapsed,
                        lastError = null,
                    )
                }
                AppLog.perf(
                    TAG,
                    "engine ready backend=${kind.name} initMs=$elapsed " +
                        "modelBytes=${modelFile.length()} attempted=${chain.joinToString(",")}",
                )
                return@withContext
            }

            _diagnostics.update {
                it.copy(backendFallback = BackendFallback(chain, null, failures))
            }
            fail(
                InferenceError.UnsupportedDevice(
                    technicalDetail = failures.entries.joinToString("; ") { "${it.key}: ${it.value}" }
                        .ifBlank { "no backend could be created" },
                    attemptedBackends = chain.map { it.name },
                ),
            )
        }
    }

    override fun generate(
        conversationId: String,
        prompt: String,
        options: GenerationOptions,
    ): Flow<GenerationEvent> = channelFlow {
        if (!generationLock.tryLock()) {
            send(GenerationEvent.Failed(InferenceError.AlreadyGenerating))
            return@channelFlow
        }

        var startedGeneration = false
        try {
            val conv = withContext(dispatchers.inference) {
                ensureConversation(conversationId, options, replay = emptyList())
            }
            if (conv == null) {
                send(GenerationEvent.Failed(currentErrorOrNotReady()))
                return@channelFlow
            }

            _diagnostics.update { it.copy(state = EngineState.Generating) }
            startedGeneration = true
            send(GenerationEvent.Started)

            val startNs = System.nanoTime()
            var firstTokenNs = 0L
            var producedChars = 0
            val completion = CompletableDeferred<Throwable?>()

            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    if (firstTokenNs == 0L) firstTokenNs = System.nanoTime()
                    val text = message.textOrEmpty()
                    if (text.isNotEmpty()) {
                        producedChars += text.length
                        trySend(GenerationEvent.Token(text))
                    }
                }

                override fun onDone() {
                    completion.complete(null)
                }

                override fun onError(throwable: Throwable) {
                    completion.complete(throwable)
                }
            }

            withContext(dispatchers.inference) { conv.sendMessageAsync(prompt, callback) }

            val failure = try {
                completion.await()
            } catch (ce: CancellationException) {
                withContext(NonCancellable) { stopNative(conv) }
                throw ce
            }

            val ttftMs = if (firstTokenNs == 0L) null else (firstTokenNs - startNs) / 1_000_000
            val totalMs = (System.nanoTime() - startNs) / 1_000_000

            when {
                failure == null -> {
                    val metrics = readMetrics(conv, ttftMs, totalMs, producedChars)
                    send(metrics)
                    _diagnostics.update {
                        it.copy(
                            lastTimeToFirstTokenMs = metrics.timeToFirstTokenMs,
                            lastTokensPerSecond = metrics.tokensPerSecond,
                            lastPrefillTokensPerSecond = metrics.prefillTokensPerSecond,
                            contextTokenCount = metrics.contextTokenCount,
                        )
                    }
                    AppLog.perf(
                        TAG,
                        "generation ok ttftMs=${metrics.timeToFirstTokenMs} " +
                            "tps=${metrics.tokensPerSecond} totalMs=$totalMs chars=$producedChars",
                    )
                    send(GenerationEvent.Completed)
                }

                failure is CancellationException -> send(GenerationEvent.Cancelled)

                failure is OutOfMemoryError -> {
                    val error = InferenceError.OutOfMemory(failure.shortReason())
                    _diagnostics.update { it.copy(lastError = error) }
                    send(GenerationEvent.Failed(error))
                }

                else -> {
                    val error = InferenceError.GenerationFailed(failure.shortReason())
                    _diagnostics.update { it.copy(lastError = error) }
                    AppLog.w(TAG, "generation failed: ${failure.shortReason()}")
                    send(GenerationEvent.Failed(error))
                }
            }
        } finally {
            if (startedGeneration) {
                _diagnostics.update { current ->
                    val path = current.modelPath
                    val backend = current.selectedBackend
                    if (current.state is EngineState.Generating && backend != null && path != null) {
                        current.copy(state = EngineState.Ready(backend, path))
                    } else {
                        current
                    }
                }
            }
            generationLock.unlock()
        }
    }

    override suspend fun cancel() {
        val conv = conversation ?: return
        withContext(NonCancellable) { stopNative(conv) }
    }

    override suspend fun resetConversation(
        conversationId: String,
        history: List<ConversationTurn>,
    ) = lifecycleLock.withLock {
        withContext(dispatchers.inference) {
            conversation?.closeQuietly()
            conversation = null
            activeConversationId = null
            ensureConversation(conversationId, GenerationOptions.Default, history)
            Unit
        }
    }

    override suspend fun close() = lifecycleLock.withLock {
        withContext(dispatchers.inference) {
            conversation?.closeQuietly()
            conversation = null
            activeConversationId = null
            engine?.closeQuietly()
            engine = null
            _diagnostics.update { it.copy(state = EngineState.Closed, selectedBackend = null) }
            AppLog.i(TAG, "engine closed")
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * Returns the native conversation for [conversationId], creating it on first
     * use. Only one native conversation is retained: switching chats disposes the
     * previous one so obsolete KV caches never accumulate.
     */
    private fun ensureConversation(
        conversationId: String,
        options: GenerationOptions,
        replay: List<ConversationTurn>,
    ): Conversation? {
        val currentEngine = engine ?: return null
        val existing = conversation
        if (existing != null && activeConversationId == conversationId && existing.isAlive) {
            return existing
        }

        existing?.closeQuietly()
        conversation = null
        activeConversationId = null

        val created = try {
            currentEngine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(config.systemPrompt),
                    initialMessages = replay.map { turn ->
                        when (turn.role) {
                            TurnRole.USER -> Message.user(turn.text)
                            TurnRole.ASSISTANT -> Message.model(turn.text)
                        }
                    },
                    samplerConfig = SamplerConfig(
                        topK = options.topK,
                        topP = options.topP,
                        temperature = options.temperature,
                        seed = options.seed,
                    ),
                ),
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            fail(InferenceError.InitializationFailed(t.shortReason()))
            return null
        }

        conversation = created
        activeConversationId = conversationId
        AppLog.d(TAG, "native conversation created (replayed turns=${replay.size})")
        return created
    }

    private fun stopNative(conv: Conversation) {
        runCatching { conv.cancelProcess() }
            .onFailure { AppLog.w(TAG, "cancelProcess failed: ${it.shortReason()}") }
    }

    @OptIn(ExperimentalApi::class)
    private fun readMetrics(
        conv: Conversation,
        ttftMs: Long?,
        totalMs: Long,
        producedChars: Int,
    ): GenerationEvent.Metrics {
        val benchmark = if (BuildConfig.DEBUG) {
            runCatching { conv.getBenchmarkInfo() }.getOrNull()
        } else {
            null
        }
        val contextTokens = runCatching { conv.getTokenCount() }.getOrNull()
        val decodeTps = benchmark?.lastDecodeTokensPerSecond?.takeIf { it > 0.0 }
            ?: estimateTokensPerSecond(producedChars, ttftMs, totalMs)
        return GenerationEvent.Metrics(
            timeToFirstTokenMs = benchmark?.timeToFirstTokenInSecond
                ?.takeIf { it > 0.0 }
                ?.let { (it * 1000).toLong() }
                ?: ttftMs,
            tokensPerSecond = decodeTps,
            prefillTokensPerSecond = benchmark?.lastPrefillTokensPerSecond?.takeIf { it > 0.0 },
            contextTokenCount = contextTokens,
        )
    }

    private fun currentErrorOrNotReady(): InferenceError =
        _diagnostics.value.lastError ?: InferenceError.EngineNotReady

    private fun fail(error: InferenceError) {
        _diagnostics.update { it.copy(state = EngineState.Failed(error), lastError = error) }
        AppLog.w(TAG, "engine failure: ${error::class.simpleName} ${error.technicalDetail.orEmpty()}")
    }

    private companion object {
        const val TAG = "LiteRtLlmEngine"

        /** Kept in sync with the `litertlm` version in gradle/libs.versions.toml. */
        const val LITERT_LM_VERSION = "0.14.0"
    }
}

private fun BackendKind.toLiteRtBackend(context: Context): Backend = when (this) {
    BackendKind.CPU -> Backend.CPU()
    BackendKind.GPU -> Backend.GPU()
    BackendKind.NPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
}

private fun Message.textOrEmpty(): String =
    contents.contents.filterIsInstance<Content.Text>().joinToString(separator = "") { it.text }

private fun Engine.closeQuietly() = runCatching { close() }

private fun Conversation.closeQuietly() = runCatching { close() }

/** Short, non-sensitive description of a throwable. Never contains prompt text. */
private fun Throwable.shortReason(): String {
    val raw = message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    val text = if (raw.isBlank()) this::class.java.simpleName else raw
    return if (text.length > 200) text.take(200) + "…" else text
}

/**
 * Estimates decode throughput when the benchmark flag is off (release builds).
 * ~4 characters per token is the usual Gemma tokenizer approximation.
 */
private fun estimateTokensPerSecond(chars: Int, ttftMs: Long?, totalMs: Long): Double? {
    val decodeMs = totalMs - (ttftMs ?: 0L)
    if (decodeMs <= 0 || chars <= 0) return null
    return (chars / 4.0) / (decodeMs / 1000.0)
}
