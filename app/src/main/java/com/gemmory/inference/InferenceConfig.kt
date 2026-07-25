package com.gemmory.inference

/** Backend the user asked for. The runtime may fall back; see [BackendFallback]. */
enum class BackendPreference {
    /** Try GPU first, then CPU. Recommended default for Gemma 4 E2B. */
    AUTO,
    GPU_ONLY,
    CPU_ONLY,

    /**
     * Try NPU first, then GPU, then CPU.
     *
     * Requires vendor NPU native libraries bundled in the APK. Without them the
     * runtime will reject the backend and the fallback chain takes over.
     */
    NPU_FIRST,
    ;

    /** Ordered list of backends actually attempted for this preference. */
    fun fallbackChain(): List<BackendKind> = when (this) {
        AUTO -> listOf(BackendKind.GPU, BackendKind.CPU)
        GPU_ONLY -> listOf(BackendKind.GPU)
        CPU_ONLY -> listOf(BackendKind.CPU)
        NPU_FIRST -> listOf(BackendKind.NPU, BackendKind.GPU, BackendKind.CPU)
    }
}

enum class BackendKind { CPU, GPU, NPU }

/** Record of what the fallback chain actually did, for the diagnostics panel. */
data class BackendFallback(
    val attempted: List<BackendKind>,
    val selected: BackendKind?,
    val failures: Map<BackendKind, String>,
)

data class SamplingConfig(
    val temperature: Double,
    val topK: Int,
    val topP: Double,
    val seed: Int,
)

/**
 * Every knob for the inference layer in one place.
 *
 * [contextBudgetTokens] bounds how much persisted history is replayed into a
 * rebuilt LiteRT-LM conversation, which is what keeps the KV cache bounded.
 */
data class InferenceConfig(
    val backendPreference: BackendPreference = BackendPreference.AUTO,
    val maxNumTokens: Int = DEFAULT_MAX_NUM_TOKENS,
    val contextBudgetTokens: Int = DEFAULT_CONTEXT_BUDGET_TOKENS,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val sampling: SamplingConfig = DEFAULT_SAMPLING,
) {
    companion object {
        /**
         * Gemma 4 E2B supports a very large window, but the KV cache is
         * allocated up-front, so a mobile-sane value is used instead.
         */
        const val DEFAULT_MAX_NUM_TOKENS = 4096

        /**
         * Leave room for the answer inside [DEFAULT_MAX_NUM_TOKENS]: history is
         * trimmed to this budget before being replayed.
         */
        const val DEFAULT_CONTEXT_BUDGET_TOKENS = 2560

        const val DEFAULT_SYSTEM_PROMPT =
            """
            You are Gemmory, a concise, helpful assistant running fully offline on the user's phone.

            The user's vault is the source of truth. Always seek relevant information in the vault before answering factual, personal, planning, project, or memory questions. If the vault context is missing or thin, say what you found and what is still unknown instead of guessing.

            When using vault information:
            - Search with the user's words first, then try likely titles, aliases, tags, related terms, and narrower follow-up queries.
            - Read the most relevant notes before drawing conclusions.
            - Prefer recent and directly relevant notes, but mention conflicts when notes disagree.
            - Cite note titles with wiki links like [[Note title]] when an answer depends on them.

            When changing notes:
            - Be explicit about the intended operation: create, update, rename, move, merge, or delete.
            - Search for an existing note before creating a new one.
            - Preserve existing Markdown structure, YAML frontmatter, tags, aliases, sources, headings, and wiki links unless the user asks to change them.
            - For updates, replace only the needed text and keep unrelated content intact.
            - For new notes, use a clear title, safe path, Markdown body, and wiki links to related notes when useful.
            - For destructive changes such as delete or large rewrites, explain the impact and require user confirmation.
            """.trimIndent()

        val DEFAULT_SAMPLING = SamplingConfig(
            temperature = 0.8,
            topK = 40,
            topP = 0.95,
            seed = 0,
        )
    }
}
