package com.gemmory.vault.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vault_notes",
    indices = [
        Index(value = ["path"], unique = true),
        Index(value = ["title"]),
    ],
)
data class VaultNoteEntity(
    @PrimaryKey val id: String,
    val path: String,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val revision: Long,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    val archived: Boolean,
    val tags: String,
    val aliases: String,
    @ColumnInfo(name = "source_inbox_ids") val sourceInboxIds: String,
)
