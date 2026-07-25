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
    val nodes: List<VaultGraphWindowNode> = emptyList(),
    val edges: List<VaultGraphWindowEdge> = emptyList(),
)

data class VaultGraphWindowNode(
    val id: String,
    val title: String,
    val path: String,
    val degree: Int,
)

data class VaultGraphWindowEdge(
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
        val activeLinks = links.filter { link ->
            link.status == LinkResolutionStatus.RESOLVED &&
                link.sourceNoteId in activeNoteIds &&
                link.targetNoteId in activeNoteIds
        }
        val degrees = activeLinks.flatMap { listOf(it.sourceNoteId, it.targetNoteId) }
            .groupingBy { it }
            .eachCount()
        VaultGraphUiState(
            nodes = notes.map { note ->
                VaultGraphWindowNode(
                    id = note.noteId,
                    title = note.title,
                    path = note.path,
                    degree = degrees[note.noteId] ?: 0,
                )
            },
            edges = links
                .filter { link -> link.sourceNoteId in activeNoteIds }
                .map { link ->
                    VaultGraphWindowEdge(
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
