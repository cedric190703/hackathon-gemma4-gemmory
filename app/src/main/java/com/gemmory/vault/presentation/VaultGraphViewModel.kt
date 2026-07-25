package com.gemmory.vault.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gemmory.vault.domain.LinkResolutionStatus
import com.gemmory.vault.domain.VaultRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class VaultGraphUiState(
    val nodes: List<VaultGraphNode> = emptyList(),
    val edges: List<VaultGraphEdge> = emptyList(),
)

data class VaultGraphNode(
    val id: String,
    val title: String,
    val path: String,
)

data class VaultGraphEdge(
    val sourceNoteId: String,
    val targetNoteId: String?,
    val targetLabel: String,
    val status: LinkResolutionStatus,
)

class VaultGraphViewModel(
    repository: VaultRepository,
) : ViewModel() {

    val uiState: StateFlow<VaultGraphUiState> = combine(
        repository.observeNotes(),
        repository.observeAllLinks(),
    ) { notes, links ->
        val activeNoteIds = notes.mapTo(mutableSetOf()) { it.noteId }
        VaultGraphUiState(
            nodes = notes.map { note ->
                VaultGraphNode(
                    id = note.noteId,
                    title = note.title,
                    path = note.path,
                )
            },
            edges = links
                .filter { link -> link.sourceNoteId in activeNoteIds }
                .map { link ->
                    VaultGraphEdge(
                        sourceNoteId = link.sourceNoteId,
                        targetNoteId = link.targetNoteId?.takeIf { it in activeNoteIds },
                        targetLabel = link.label ?: link.rawTarget,
                        status = link.status,
                    )
                },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultGraphUiState())

    companion object {
        fun factory(repository: VaultRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                VaultGraphViewModel(repository) as T
        }
    }
}
