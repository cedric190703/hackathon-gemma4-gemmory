package com.gemmory.vault.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vault_change_sets")
data class VaultChangeSetEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "user_request") val userRequest: String,
    @ColumnInfo(name = "source_inbox_ids") val sourceInboxIds: String,
    @ColumnInfo(name = "reasoning_summary") val reasoningSummary: String,
    val operations: String,
    @ColumnInfo(name = "before_state") val beforeState: String,
    @ColumnInfo(name = "after_state") val afterState: String,
    @ColumnInfo(name = "model_configuration") val modelConfiguration: String,
    @ColumnInfo(name = "approval_status") val approvalStatus: String,
    @ColumnInfo(name = "undone_at") val undoneAt: Long?,
)
