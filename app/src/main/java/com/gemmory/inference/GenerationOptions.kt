package com.gemmory.inference

/**
 * Sampling settings for one generation. Centralised defaults live in
 * [InferenceConfig.DEFAULT_SAMPLING] so no call site invents its own values.
 */
data class GenerationOptions(
    val temperature: Double = InferenceConfig.DEFAULT_SAMPLING.temperature,
    val topK: Int = InferenceConfig.DEFAULT_SAMPLING.topK,
    val topP: Double = InferenceConfig.DEFAULT_SAMPLING.topP,
    val seed: Int = InferenceConfig.DEFAULT_SAMPLING.seed,
) {
    companion object {
        val Default = GenerationOptions()
        val GroundedVaultAnswer = GenerationOptions(
            temperature = 0.1,
            topK = 10,
            topP = 0.7,
            seed = InferenceConfig.DEFAULT_SAMPLING.seed,
        )
    }
}
