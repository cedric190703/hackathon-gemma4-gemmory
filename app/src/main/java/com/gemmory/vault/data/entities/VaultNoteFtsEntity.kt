package com.gemmory.vault.data.entities

import androidx.room.Entity
import androidx.room.Fts4

@Fts4
@Entity(tableName = "vault_note_fts")
data class VaultNoteFtsEntity(
    val noteId: String,
    val title: String,
    val path: String,
    val tags: String,
    val aliases: String,
    val body: String,
)
