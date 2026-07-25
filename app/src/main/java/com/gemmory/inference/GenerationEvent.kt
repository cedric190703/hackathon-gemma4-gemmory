package com.gemmory.inference

/** Streamed output of a single generation request. */
sealed interface GenerationEvent {
    data object Started : GenerationEvent

    /** An incremental chunk of decoded text. Not necessarily a single token. */
    data class Token(val text: String) : GenerationEvent

    data class Metrics(
        val timeToFirstTokenMs: Long?,
        val tokensPerSecond: Double?,
        val prefillTokensPerSecond: Double? = null,
        val contextTokenCount: Int? = null,
    ) : GenerationEvent

    data object Completed : GenerationEvent

    data object Cancelled : GenerationEvent

    data class Failed(val error: InferenceError) : GenerationEvent
}
