package com.gemmory.chat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gemmory.chat.data.entities.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY order_index ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY order_index ASC")
    suspend fun listForConversation(conversationId: String): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(order_index), -1) FROM messages WHERE conversation_id = :conversationId")
    suspend fun maxOrderIndex(conversationId: String): Long

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    suspend fun countForConversation(conversationId: String): Int

    @Query(
        """
        UPDATE messages
        SET content = :content, status = :status, error_text = :errorText
        WHERE id = :id
        """,
    )
    suspend fun updateContentAndStatus(
        id: String,
        content: String,
        status: String,
        errorText: String?,
    )

    @Query("UPDATE messages SET content = :content WHERE id = :id AND conversation_id = :conversationId")
    suspend fun updateContent(id: String, conversationId: String, content: String)

    /**
     * Any message left mid-flight by a process death is demoted to `CANCELLED`,
     * so a restart can never present a partial answer as complete.
     */
    @Query(
        """
        UPDATE messages
        SET status = :cancelled
        WHERE status IN (:pending, :generating)
        """,
    )
    suspend fun demoteUnfinished(cancelled: String, pending: String, generating: String): Int

    @Query("DELETE FROM messages WHERE id = :id AND conversation_id = :conversationId")
    suspend fun delete(id: String, conversationId: String)
}
