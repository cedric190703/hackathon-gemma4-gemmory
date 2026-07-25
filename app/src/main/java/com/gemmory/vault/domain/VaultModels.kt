package com.gemmory.vault.domain

data class VaultNote(
    val id: String,
    val path: String,
    val title: String,
    val markdown: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long,
    val contentHash: String,
    val archived: Boolean,
    val tags: List<String>,
    val aliases: List<String>,
    val sourceInboxIds: List<String>,
)

data class VaultEntry(
    val noteId: String,
    val path: String,
    val title: String,
    val isArchived: Boolean,
)

data class VaultLink(
    val id: String,
    val sourceNoteId: String,
    val targetNoteId: String?,
    val rawTarget: String,
    val label: String?,
    val status: LinkResolutionStatus,
)

enum class LinkResolutionStatus {
    RESOLVED,
    UNRESOLVED,
    AMBIGUOUS,
}

data class VaultSearchResult(
    val noteId: String,
    val title: String,
    val path: String,
    val snippet: String,
    val score: Int,
)

sealed interface VaultOperation {
    data class CreateNote(
        val temporaryId: String,
        val proposedPath: String,
        val title: String,
        val markdown: String,
        val sourceInboxIds: List<String>,
    ) : VaultOperation

    data class UpdateNote(
        val noteId: String,
        val expectedRevision: Long,
        val replacementMarkdown: String,
        val reason: String,
    ) : VaultOperation

    data class MoveNote(
        val noteId: String,
        val expectedRevision: Long,
        val destinationPath: String,
    ) : VaultOperation

    data class RenameNote(
        val noteId: String,
        val expectedRevision: Long,
        val newTitle: String,
    ) : VaultOperation

    data class DeleteNote(
        val noteId: String,
        val expectedRevision: Long,
        val reason: String,
    ) : VaultOperation

    data class MergeNotes(
        val sourceNoteIds: List<String>,
        val destinationNoteId: String?,
        val mergedTitle: String,
        val mergedMarkdown: String,
    ) : VaultOperation
}

data class ProposedVaultChangeSet(
    val id: String,
    val userRequest: String,
    val sourceInboxIds: List<String>,
    val operations: List<VaultOperation>,
    val validationErrors: List<String>,
    val previews: List<VaultOperationPreview>,
) {
    val canApply: Boolean = validationErrors.isEmpty() && operations.isNotEmpty()
}

data class VaultOperationPreview(
    val operationLabel: String,
    val noteId: String?,
    val path: String,
    val title: String,
    val beforeMarkdown: String?,
    val afterMarkdown: String?,
)

data class ApplyResult(
    val changeSetId: String,
    val affectedNoteIds: List<String>,
)

data class UndoResult(
    val changeSetId: String,
    val restoredNoteIds: List<String>,
)
