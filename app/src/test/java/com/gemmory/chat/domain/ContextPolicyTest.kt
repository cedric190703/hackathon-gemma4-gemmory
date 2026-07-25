package com.gemmory.chat.domain

import com.gemmory.inference.TurnRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPolicyTest {

    private fun message(
        index: Long,
        role: MessageRole,
        content: String,
        status: MessageStatus = MessageStatus.COMPLETE,
    ) = ChatMessage(
        id = "m$index",
        conversationId = "c",
        role = role,
        content = content,
        status = status,
        orderIndex = index,
        createdAt = index,
    )

    @Test
    fun `keeps whole history when it fits the budget`() {
        val policy = ContextPolicy(budgetTokens = 1000)

        val bounded = policy.bound(
            listOf(
                message(0, MessageRole.USER, "hello"),
                message(1, MessageRole.ASSISTANT, "hi"),
                message(2, MessageRole.USER, "how are you"),
                message(3, MessageRole.ASSISTANT, "fine"),
            ),
        )

        assertEquals(4, bounded.turns.size)
        assertFalse(bounded.isTruncated)
        assertEquals(TurnRole.USER, bounded.turns.first().role)
    }

    @Test
    fun `drops oldest messages when the budget is exceeded`() {
        // 40 characters ~ 12 tokens per message at 3.5 chars/token.
        val policy = ContextPolicy(budgetTokens = 30)
        val history = (0L until 10L).map { index ->
            message(
                index,
                if (index % 2 == 0L) MessageRole.USER else MessageRole.ASSISTANT,
                "x".repeat(40),
            )
        }

        val bounded = policy.bound(history)

        assertTrue(bounded.isTruncated)
        assertTrue(bounded.turns.size < history.size)
        assertEquals(history.size - bounded.turns.size, bounded.droppedMessageCount)
    }

    @Test
    fun `replay is always a whole number of user then assistant turns`() {
        val history = listOf(
            message(0, MessageRole.USER, "a".repeat(40)),
            message(1, MessageRole.ASSISTANT, "b".repeat(40)),
            message(2, MessageRole.USER, "c".repeat(40)),
            message(3, MessageRole.ASSISTANT, "d".repeat(40)),
            message(4, MessageRole.USER, "e".repeat(10)),
        )

        for (budget in listOf(12, 30, 60, 1000)) {
            val bounded = ContextPolicy(budgetTokens = budget).bound(history)
            if (bounded.turns.isEmpty()) continue
            assertEquals(TurnRole.USER, bounded.turns.first().role)
            assertEquals(TurnRole.ASSISTANT, bounded.turns.last().role)
            assertEquals(0, bounded.turns.size % 2)
        }
    }

    @Test
    fun `an unanswered trailing prompt is never replayed`() {
        val policy = ContextPolicy(budgetTokens = 1000)

        val bounded = policy.bound(
            listOf(
                message(0, MessageRole.USER, "answered"),
                message(1, MessageRole.ASSISTANT, "answer"),
                message(2, MessageRole.USER, "not answered yet"),
            ),
        )

        assertEquals(listOf("answered", "answer"), bounded.turns.map { it.text })
    }

    @Test
    fun `never replays cancelled failed pending or generating messages`() {
        val policy = ContextPolicy(budgetTokens = 1000)

        val bounded = policy.bound(
            listOf(
                message(0, MessageRole.USER, "question"),
                message(1, MessageRole.ASSISTANT, "partial", MessageStatus.CANCELLED),
                message(2, MessageRole.USER, "again"),
                message(3, MessageRole.ASSISTANT, "broken", MessageStatus.FAILED),
                message(4, MessageRole.USER, "third"),
                message(5, MessageRole.ASSISTANT, "real answer"),
                message(6, MessageRole.USER, "fourth"),
                message(7, MessageRole.ASSISTANT, "", MessageStatus.GENERATING),
            ),
        )

        assertEquals(listOf("question", "again", "third", "real answer"), bounded.turns.map { it.text })
        assertEquals(TurnRole.ASSISTANT, bounded.turns.last().role)
    }

    @Test
    fun `persisted history can never produce unbounded context`() {
        val policy = ContextPolicy(budgetTokens = 100)
        val huge = (0L until 500L).map { index ->
            message(
                index,
                if (index % 2 == 0L) MessageRole.USER else MessageRole.ASSISTANT,
                "y".repeat(500),
            )
        }

        val bounded = policy.bound(huge)
        val estimated = bounded.turns.sumOf { policy.estimateTokens(it.text) }

        // One oversized final message is always kept, so allow a single overflow.
        assertTrue(bounded.turns.size <= 1 || estimated <= 100)
        assertTrue(bounded.isTruncated)
    }
}
