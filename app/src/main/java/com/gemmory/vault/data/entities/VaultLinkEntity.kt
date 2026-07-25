package com.gemmory.vault.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vault_links",
    indices = [
        Index(value = ["source_note_id"]),
        Index(value = ["target_note_id"]),
        Index(value = ["raw_target"]),
    ],
)
data class VaultLinkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_note_id") val sourceNoteId: String,
    @ColumnInfo(name = "target_note_id") val targetNoteId: String?,
    @ColumnInfo(name = "raw_target") val rawTarget: String,
    val label: String?,
    @ColumnInfo(name = "start_offset") val startOffset: Int,
    @ColumnInfo(name = "end_offset") val endOffset: Int,
    val status: String,
)
