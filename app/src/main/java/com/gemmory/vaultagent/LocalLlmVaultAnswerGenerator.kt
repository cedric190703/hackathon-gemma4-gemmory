package com.gemmory.vaultagent

import com.gemmory.core.logging.AppLog
import com.gemmory.inference.EngineController
import com.gemmory.inference.EngineState
import com.gemmory.inference.GenerationEvent
import com.gemmory.inference.GenerationOptions
import com.gemmory.vault.domain.VaultAnswerContext
import com.gemmory.vault.domain.VaultAnswerGenerator
import java.util.UUID

class LocalLlmVaultAnswerGenerator(
    private val engineController: EngineController,
    private val maxContextCharacters: Int = DEFAULT_MAX_CONTEXT_CHARACTERS,
) : VaultAnswerGenerator {

    override suspend fun answer(question: String, contexts: List<VaultAnswerContext>): String? {
        if (contexts.isEmpty()) return null
        if (engineController.engine.diagnostics.value.state !is EngineState.Ready) return null

        val conversationId = "ask-vault-${UUID.randomUUID()}"
        engineController.resetConversation(conversationId, emptyList())

        val builder = StringBuilder()
        var completed = false
        var failed = false
        engineController.generate(
            conversationId = conversationId,
            prompt = buildPrompt(question, contexts),
            options = GenerationOptions.Default,
        ).collect { event ->
            when (event) {
                GenerationEvent.Started,
                is GenerationEvent.Metrics,
                -> Unit

                is GenerationEvent.Token -> builder.append(event.text)
                GenerationEvent.Completed -> completed = true
                GenerationEvent.Cancelled -> failed = true
                is GenerationEvent.Failed -> {
                    AppLog.w(TAG, "vault answer generation failed: ${event.error::class.simpleName}")
                    failed = true
                }
            }
        }

        return if (!failed && completed) builder.toString().trim().takeIf { it.isNotBlank() } else null
    }

    private fun buildPrompt(question: String, contexts: List<VaultAnswerContext>): String = buildString {
        appendLine("Answer the user's question using only the vault excerpts below.")
        appendLine("If the excerpts do not contain the answer, say: I could not find this in your vault.")
        appendLine("Cite relevant notes inline with their wiki links, for example [[Note title]].")
        appendLine()
        appendLine("Vault excerpts:")

        var remaining = maxContextCharacters
        contexts.forEachIndexed { index, context ->
            if (remaining <= 0) return@forEachIndexed
            val sourceText = context.markdown.ifBlank { context.snippet }.trim()
            val excerpt = sourceText.take(remaining)
            remaining -= excerpt.length

            appendLine("[${index + 1}] [[${context.title}]] (${context.path})")
            appendLine(excerpt)
            appendLine()
        }

        appendLine("Question: $question")
        appendLine("Answer:")
    }

    private companion object {
        const val TAG = "VaultAnswerGenerator"
        const val DEFAULT_MAX_CONTEXT_CHARACTERS = 12_000
    }
}
