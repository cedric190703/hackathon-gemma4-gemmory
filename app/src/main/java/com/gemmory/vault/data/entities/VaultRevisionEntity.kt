package com.gemmory.vault.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vault_revisions",
    indices = [Index(value = ["note_id", "revision"], unique = true)],
)
data class VaultRevisionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    val path: String,
    val title: String,
    val markdown: String,
    val revision: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "change_set_id") val changeSetId: String?,
)
