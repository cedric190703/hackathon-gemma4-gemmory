package com.gemmory.inference

import com.gemmory.core.logging.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    /** Idempotent. Loading the same path twice is a no-op. */
    fun ensureLoaded(modelPath: String) {
        if (loadJob?.isActive == true) return
        val state = engine.diagnostics.value.state
        if (loadedPath == modelPath && state is EngineState.Ready) return
        if (state is EngineState.Failed && loadedPath == modelPath) return

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
        ensureLoaded(modelPath)
    }

    fun unload() {
        loadJob?.cancel()
        loadJob = null
        loadedPath = null
        scope.launch { engine.close() }
    }

    private companion object {
        const val TAG = "EngineController"
    }
}
