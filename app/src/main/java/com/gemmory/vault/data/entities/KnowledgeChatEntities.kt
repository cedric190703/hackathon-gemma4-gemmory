package com.gemmory.vault.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "knowledge_chats")
data class KnowledgeChatEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "knowledge_messages",
    indices = [Index(value = ["conversation_id"])],
)
data class KnowledgeMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val role: String,
    val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "citation_note_ids") val citationNoteIds: String,
    @ColumnInfo(name = "fully_grounded") val fullyGrounded: Boolean,
)
