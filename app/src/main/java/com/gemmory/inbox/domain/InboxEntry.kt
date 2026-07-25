package com.gemmory.inbox.domain

enum class InboxEntryStatus {
    DRAFT,
    READY,
    PROCESSING,
    PROCESSED,
    PARTIALLY_PROCESSED,
    FAILED,
    ARCHIVED,
}

data class InboxEntry(
    val id: String,
    val text: String,
    val status: InboxEntryStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val processedAt: Long?,
    val lastError: String?,
    val resultNoteIds: List<String>,
)
