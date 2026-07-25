package com.gemmory.chat.domain

import com.gemmory.inference.ConversationTurn
import com.gemmory.inference.TurnRole

/**
 * Result of bounding a persisted conversation to the runtime context budget.
 *
 * @param turns turns that will be replayed into the native conversation.
 * @param droppedMessageCount how many older messages were left out.
 */
data class BoundedContext(
    val turns: List<ConversationTurn>,
    val droppedMessageCount: Int,
) {
    val isTruncated: Boolean get() = droppedMessageCount > 0
}

/**
 * Context policy (documented, deliberately simple):
 *
 *  1. The system prompt is owned by the engine and always preserved; it is not
 *     part of the replayed turns and is not subject to trimming.
 *  2. Only messages with [MessageStatus.COMPLETE] are replayed. Pending,
 *     generating, cancelled and failed messages are never fed back to the model.
 *  3. The most recent messages are kept, walking backwards until the estimated
 *     token cost exceeds the budget.
 *  4. A leading assistant turn is dropped so replay always starts with a user
 *     turn, which is what Gemma's chat template expects.
 *  5. A trailing user turn is dropped as well: it has no answer yet, and
 *     replaying it would leave the model mid-turn before the new prompt.
 *  6. Persisted history can therefore never produce unbounded runtime context.
 *
 * Token counts are estimated, since the tokenizer is not exposed before the
 * engine is loaded. The estimate deliberately over-counts slightly.
 */
class ContextPolicy(
    private val budgetTokens: Int,
    private val charsPerToken: Double = DEFAULT_CHARS_PER_TOKEN,
) {

    fun bound(messages: List<ChatMessage>): BoundedContext {
        val replayable = messages.filter { it.status == MessageStatus.COMPLETE && it.content.isNotBlank() }

        val kept = ArrayDeque<ChatMessage>()
        var used = 0

        for (message in replayable.asReversed()) {
            val cost = estimateTokens(message.content)
            if (used + cost > budgetTokens && kept.isNotEmpty()) break
            kept.addFirst(message)
            used += cost
            if (used >= budgetTokens) break
        }

        while (kept.isNotEmpty() && kept.last().role != MessageRole.ASSISTANT) {
            kept.removeLast()
        }
        while (kept.isNotEmpty() && kept.first().role != MessageRole.USER) {
            kept.removeFirst()
        }

        val droppedOlder = replayable.size - kept.size
        return BoundedContext(
            turns = kept.map { message ->
                ConversationTurn(
                    role = when (message.role) {
                        MessageRole.USER -> TurnRole.USER
                        MessageRole.ASSISTANT -> TurnRole.ASSISTANT
                    },
                    text = message.content,
                )
            },
            droppedMessageCount = droppedOlder,
        )
    }

    fun estimateTokens(text: String): Int =
        Math.ceil(text.length / charsPerToken).toInt().coerceAtLeast(1)

    companion object {
        /** Gemma tokenizers average slightly under 4 characters per token. */
        const val DEFAULT_CHARS_PER_TOKEN = 3.5
    }
}
