package com.gemmory.vault.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.gemmory.inbox.data.InboxEntryEntity
import com.gemmory.vault.data.entities.KnowledgeChatEntity
import com.gemmory.vault.data.entities.KnowledgeMessageEntity
import com.gemmory.vault.data.entities.VaultChangeSetEntity
import com.gemmory.vault.data.entities.VaultLinkEntity
import com.gemmory.vault.data.entities.VaultNoteEntity
import com.gemmory.vault.data.entities.VaultNoteFtsEntity
import com.gemmory.vault.data.entities.VaultRevisionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInbox(entry: InboxEntryEntity)

    @Update
    suspend fun updateInbox(entry: InboxEntryEntity)

    @Query("DELETE FROM inbox_entries WHERE id IN (:ids)")
    suspend fun deleteInboxEntries(ids: List<String>)

    @Query("SELECT * FROM inbox_entries ORDER BY created_at DESC")
    fun observeInbox(): Flow<List<InboxEntryEntity>>

    @Query("SELECT * FROM inbox_entries WHERE id IN (:ids)")
    suspend fun inboxByIds(ids: List<String>): List<InboxEntryEntity>

    @Query("SELECT * FROM inbox_entries WHERE status IN (:statuses) ORDER BY created_at ASC")
    suspend fun inboxByStatuses(statuses: List<String>): List<InboxEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: VaultNoteEntity)

    @Query("SELECT * FROM vault_notes WHERE id = :id")
    suspend fun noteById(id: String): VaultNoteEntity?

    @Query("SELECT * FROM vault_notes WHERE archived = 0 ORDER BY updated_at DESC")
    fun observeNotes(): Flow<List<VaultNoteEntity>>

    @Query("SELECT * FROM vault_notes WHERE archived = 0 ORDER BY updated_at DESC LIMIT :limit")
    suspend fun recentNotes(limit: Int): List<VaultNoteEntity>

    @Query("SELECT * FROM vault_notes WHERE path = :path LIMIT 1")
    suspend fun noteByPath(path: String): VaultNoteEntity?

    @Query("SELECT * FROM vault_notes WHERE lower(title) = lower(:title)")
    suspend fun notesByTitle(title: String): List<VaultNoteEntity>

    @Query("SELECT * FROM vault_notes WHERE archived = 0")
    suspend fun allActiveNotes(): List<VaultNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFts(row: VaultNoteFtsEntity)

    @Query("DELETE FROM vault_note_fts WHERE noteId = :noteId")
    suspend fun deleteFts(noteId: String)

    @Query(
        """
        SELECT noteId FROM vault_note_fts
        WHERE vault_note_fts MATCH :query
        LIMIT :limit
        """,
    )
    suspend fun searchFtsIds(query: String, limit: Int): List<String>

    @Query("SELECT * FROM vault_notes WHERE id IN (:ids)")
    suspend fun notesByIds(ids: List<String>): List<VaultNoteEntity>

    @Query("DELETE FROM vault_links WHERE source_note_id = :noteId")
    suspend fun deleteLinksFrom(noteId: String)

    @Query("DELETE FROM vault_links WHERE source_note_id = :noteId OR target_note_id = :noteId")
    suspend fun deleteLinksTouching(noteId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLinks(links: List<VaultLinkEntity>)

    @Query("SELECT * FROM vault_links WHERE target_note_id = :noteId ORDER BY raw_target ASC")
    fun observeBacklinks(noteId: String): Flow<List<VaultLinkEntity>>

    @Query("SELECT * FROM vault_links WHERE source_note_id = :noteId ORDER BY start_offset ASC")
    fun observeOutgoingLinks(noteId: String): Flow<List<VaultLinkEntity>>

    @Query("SELECT * FROM vault_links WHERE target_note_id = :noteId ORDER BY raw_target ASC")
    suspend fun backlinks(noteId: String): List<VaultLinkEntity>

    @Query("SELECT * FROM vault_links WHERE source_note_id = :noteId ORDER BY start_offset ASC")
    suspend fun outgoingLinks(noteId: String): List<VaultLinkEntity>

    @Query("SELECT * FROM vault_links ORDER BY source_note_id ASC, start_offset ASC")
    fun observeLinks(): Flow<List<VaultLinkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRevision(revision: VaultRevisionEntity)

    @Query("SELECT * FROM vault_revisions WHERE note_id = :noteId ORDER BY revision DESC")
    fun observeRevisions(noteId: String): Flow<List<VaultRevisionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChangeSet(changeSet: VaultChangeSetEntity)

    @Query("SELECT * FROM vault_change_sets WHERE undone_at IS NULL ORDER BY created_at DESC LIMIT 1")
    suspend fun latestUndoableChangeSet(): VaultChangeSetEntity?

    @Query("UPDATE vault_change_sets SET undone_at = :undoneAt WHERE id = :id")
    suspend fun markChangeSetUndone(id: String, undoneAt: Long)

    @Query("DELETE FROM vault_notes WHERE id = :id")
    suspend fun deleteNote(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKnowledgeChat(chat: KnowledgeChatEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKnowledgeMessage(message: KnowledgeMessageEntity)

    @Query("SELECT * FROM knowledge_messages WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    fun observeKnowledgeMessages(conversationId: String): Flow<List<KnowledgeMessageEntity>>

    @Transaction
    suspend fun replaceLinks(noteId: String, links: List<VaultLinkEntity>) {
        deleteLinksFrom(noteId)
        if (links.isNotEmpty()) insertLinks(links)
    }
}
