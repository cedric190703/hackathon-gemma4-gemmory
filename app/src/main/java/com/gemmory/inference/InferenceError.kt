package com.gemmory.inference

/**
 * Typed domain errors. Native stack traces never reach the UI; only these
 * values plus a short, non-sensitive [technicalDetail] do.
 */
sealed class InferenceError(
    open val technicalDetail: String? = null,
) {
    /** The configured model file no longer exists on disk. */
    data class ModelFileMissing(val path: String) : InferenceError("missing model file")

    /** No LiteRT-LM backend could be created on this hardware. */
    data class UnsupportedDevice(
        override val technicalDetail: String?,
        val attemptedBackends: List<String>,
    ) : InferenceError(technicalDetail)

    /** The engine could not be initialized for a reason other than hardware support. */
    data class InitializationFailed(override val technicalDetail: String?) :
        InferenceError(technicalDetail)

    /** The device ran out of memory. Never retried automatically. */
    data class OutOfMemory(override val technicalDetail: String?) : InferenceError(technicalDetail)

    /** Generation failed mid-flight. */
    data class GenerationFailed(override val technicalDetail: String?) :
        InferenceError(technicalDetail)

    /** A prompt was submitted while the engine was not ready to accept one. */
    data object EngineNotReady : InferenceError("engine not ready")

    /** A second prompt was submitted while one was already generating. */
    data object AlreadyGenerating : InferenceError("generation already in progress")

    val isFatalForSession: Boolean
        get() = this is UnsupportedDevice || this is OutOfMemory
}
