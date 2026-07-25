package com.gemmory.chat.domain

enum class MessageRole { USER, ASSISTANT }

/**
 * Lifecycle of a single message.
 *
 * A partially generated assistant message is stored as [CANCELLED] or [FAILED],
 * never as [COMPLETE], so history replay can exclude or mark it explicitly.
 */
enum class MessageStatus {
    PENDING,
    GENERATING,
    COMPLETE,
    CANCELLED,
    FAILED,
    ;

    val isTerminal: Boolean get() = this == COMPLETE || this == CANCELLED || this == FAILED
}

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val status: MessageStatus,
    val orderIndex: Long,
    val createdAt: Long,
    /** Short, non-sensitive error description shown under a failed message. */
    val errorText: String? = null,
)

data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int = 0,
)
