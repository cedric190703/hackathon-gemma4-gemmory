package com.gemmory.vault.domain

import com.gemmory.inbox.domain.InboxEntry
import kotlinx.coroutines.flow.Flow

data class VaultAnswerContext(
    val noteId: String,
    val title: String,
    val path: String,
    val snippet: String,
    val markdown: String,
)

data class VaultProcessingInboxEntry(
    val id: String,
    val text: String,
)

data class VaultProcessingExistingNote(
    val noteId: String,
    val title: String,
    val path: String,
    val tags: List<String>,
    val aliases: List<String>,
)

data class ProcessedVaultNoteDraft(
    val title: String,
    val sourceInboxIds: List<String>,
    val bodyMarkdown: String,
    val tags: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
)

interface VaultAnswerGenerator {
    /**
     * Returns null when the local model is unavailable or declines to answer, so
     * callers can preserve the deterministic search-only fallback.
     */
    suspend fun answer(question: String, contexts: List<VaultAnswerContext>): String?
}

interface VaultNoteProcessor {
    /**
     * Returns null when the local model is unavailable or generation fails. Note
     * processing intentionally has no deterministic fallback because it is
     * expected to rewrite and connect thoughts.
     */
    suspend fun processInbox(
        entries: List<VaultProcessingInboxEntry>,
        existingNotes: List<VaultProcessingExistingNote>,
    ): List<ProcessedVaultNoteDraft>?
}

interface VaultRepository {
    fun observeInbox(): Flow<List<InboxEntry>>
    fun observeNotes(): Flow<List<VaultEntry>>
    fun observeAllLinks(): Flow<List<VaultLink>>
    fun observeGraph(): Flow<VaultGraph>
    fun observeBacklinks(noteId: String): Flow<List<VaultLink>>
    fun observeOutgoingLinks(noteId: String): Flow<List<VaultLink>>
    suspend fun captureInbox(text: String): InboxEntry
    suspend fun updateInboxText(id: String, text: String)
    suspend fun deleteInboxEntries(ids: List<String>)
    suspend fun proposeProcessing(entryIds: List<String>): ProposedVaultChangeSet
    suspend fun proposeAllUnprocessed(): ProposedVaultChangeSet
    suspend fun preview(operations: List<VaultOperation>, request: String, sourceInboxIds: List<String>): ProposedVaultChangeSet
    suspend fun apply(changeSet: ProposedVaultChangeSet): ApplyResult
    suspend fun undoLatest(): UndoResult?
    suspend fun getNote(noteId: String): VaultNote?
    suspend fun deleteNote(noteId: String): Boolean
    suspend fun search(query: String, limit: Int): List<VaultSearchResult>
    suspend fun answerVaultQuestion(conversationId: String, question: String): String
}
