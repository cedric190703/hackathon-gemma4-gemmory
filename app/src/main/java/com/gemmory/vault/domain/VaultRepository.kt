package com.gemmory.vault.domain

import com.gemmory.inbox.domain.InboxEntry
import kotlinx.coroutines.flow.Flow

interface VaultRepository {
    fun observeInbox(): Flow<List<InboxEntry>>
    fun observeNotes(): Flow<List<VaultEntry>>
    fun observeGraph(): Flow<VaultGraph>
    fun observeBacklinks(noteId: String): Flow<List<VaultLink>>
    fun observeOutgoingLinks(noteId: String): Flow<List<VaultLink>>
    suspend fun captureInbox(text: String): InboxEntry
    suspend fun updateInboxText(id: String, text: String)
    suspend fun proposeProcessing(entryIds: List<String>): ProposedVaultChangeSet
    suspend fun proposeAllUnprocessed(): ProposedVaultChangeSet
    suspend fun preview(operations: List<VaultOperation>, request: String, sourceInboxIds: List<String>): ProposedVaultChangeSet
    suspend fun apply(changeSet: ProposedVaultChangeSet): ApplyResult
    suspend fun undoLatest(): UndoResult?
    suspend fun getNote(noteId: String): VaultNote?
    suspend fun search(query: String, limit: Int): List<VaultSearchResult>
    suspend fun answerVaultQuestion(conversationId: String, question: String): String
}
