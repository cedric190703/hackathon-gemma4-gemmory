package com.gemmory.core.dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Explicit dispatcher container so every layer can be driven by a test dispatcher.
 */
interface AppDispatchers {
    val main: CoroutineDispatcher
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher

    /**
     * Single dedicated thread used for every LiteRT-LM native call.
     *
     * The native engine is not documented as thread-safe, and pinning all
     * interactions to one thread removes an entire class of JNI lifetime bugs.
     */
    val inference: CoroutineDispatcher
}

class DefaultAppDispatchers : AppDispatchers {
    override val main: CoroutineDispatcher = Dispatchers.Main.immediate
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val io: CoroutineDispatcher = Dispatchers.IO

    private val inferenceExecutor: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "gemmory-inference").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    override val inference: CoroutineDispatcher = inferenceExecutor
}
