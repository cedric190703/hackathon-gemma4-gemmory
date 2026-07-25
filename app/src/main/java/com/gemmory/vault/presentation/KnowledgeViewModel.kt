package com.gemmory.vault.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gemmory.inbox.domain.InboxEntry
import com.gemmory.vault.domain.ProposedVaultChangeSet
import com.gemmory.vault.domain.VaultEntry
import com.gemmory.vault.domain.VaultGraph
import com.gemmory.vault.domain.VaultNote
import com.gemmory.vault.domain.VaultRepository
import com.gemmory.vault.domain.VaultSearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class KnowledgeUiState(
    val inbox: List<InboxEntry> = emptyList(),
    val notes: List<VaultEntry> = emptyList(),
    val graph: VaultGraph = VaultGraph(),
    val selectedNote: VaultNote? = null,
    val searchQuery: String = "",
    val searchResults: List<VaultSearchResult> = emptyList(),
    val selectedInboxIds: Set<String> = emptySet(),
    val pendingChangeSet: ProposedVaultChangeSet? = null,
    val busy: Boolean = false,
    val banner: String? = null,
)

class KnowledgeViewModel(
    private val repository: VaultRepository,
) : ViewModel() {

    private val selectedNote = MutableStateFlow<VaultNote?>(null)
    private val searchQuery = MutableStateFlow("")
    private val searchResults = MutableStateFlow<List<VaultSearchResult>>(emptyList())
    private val selectedInboxIds = MutableStateFlow<Set<String>>(emptySet())
    private val pendingChangeSet = MutableStateFlow<ProposedVaultChangeSet?>(null)
    private val busy = MutableStateFlow(false)
    private val banner = MutableStateFlow<String?>(null)

    private val inbox = repository.observeInbox()
    private val notes = repository.observeNotes()
    private val graph = repository.observeGraph()

    val uiState: StateFlow<KnowledgeUiState> = combine(
        combine(inbox, notes, graph, selectedNote, ::VaultSnapshot),
        combine(searchQuery, searchResults, selectedInboxIds, ::Triple),
        combine(pendingChangeSet, busy, ::Pair),
        banner,
    ) { first, second, third, message ->
        KnowledgeUiState(
            inbox = first.inbox,
            notes = first.notes,
            graph = first.graph,
            selectedNote = first.selectedNote,
            searchQuery = second.first,
            searchResults = second.second,
            selectedInboxIds = second.third,
            pendingChangeSet = third.first,
            busy = third.second,
            banner = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KnowledgeUiState())

    fun capture(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            busy.value = true
            runCatching { repository.captureInbox(text) }
                .onFailure { banner.value = it.message ?: "Unable to save inbox entry" }
            busy.value = false
        }
    }

    fun toggleInboxSelection(id: String) {
        selectedInboxIds.value = selectedInboxIds.value.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
    }

    fun processSelected() {
        val ids = selectedInboxIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            busy.value = true
            pendingChangeSet.value = repository.proposeProcessing(ids)
            busy.value = false
        }
    }

    fun processSelectedAndApply(onComplete: () -> Unit) {
        val ids = selectedInboxIds.value.toList()
        if (ids.isEmpty()) return
        processAndApply(
            onComplete = onComplete,
            deleteInboxIds = { changeSet -> (ids + changeSet.sourceInboxIds).distinct() },
        ) {
            repository.proposeProcessing(ids)
        }
    }

    fun processAll() {
        viewModelScope.launch {
            busy.value = true
            pendingChangeSet.value = repository.proposeAllUnprocessed()
            busy.value = false
        }
    }

    fun processAllAndApply(onComplete: () -> Unit) {
        val visibleInboxIds = uiState.value.inbox.map { it.id }
        processAndApply(
            onComplete = onComplete,
            deleteInboxIds = { changeSet -> (visibleInboxIds + changeSet.sourceInboxIds).distinct() },
        ) {
            repository.proposeAllUnprocessed()
        }
    }

    private fun processAndApply(
        onComplete: () -> Unit,
        deleteInboxIds: (ProposedVaultChangeSet) -> List<String>,
        propose: suspend () -> ProposedVaultChangeSet,
    ) {
        viewModelScope.launch {
            busy.value = true
            banner.value = "Processing inbox notes. Please wait until it is done."
            runCatching {
                val changeSet = propose()
                if (!changeSet.canApply) {
                    pendingChangeSet.value = changeSet.takeIf { it.validationErrors.isNotEmpty() }
                    error(
                        changeSet.validationErrors.firstOrNull()
                            ?: "No inbox notes to process.",
                    )
                }
                val result = repository.apply(changeSet)
                repository.deleteInboxEntries(deleteInboxIds(changeSet))
                result
            }.onSuccess {
                banner.value = "Processed ${it.affectedNoteIds.size} vault note(s)."
                pendingChangeSet.value = null
                selectedInboxIds.value = emptySet()
                onComplete()
            }.onFailure {
                banner.value = it.message ?: "Unable to process inbox notes"
            }
            busy.value = false
        }
    }

    fun applyPending() {
        val changeSet = pendingChangeSet.value ?: return
        viewModelScope.launch {
            busy.value = true
            runCatching { repository.apply(changeSet) }
                .onSuccess {
                    banner.value = "Applied ${it.affectedNoteIds.size} vault change(s)."
                    pendingChangeSet.value = null
                    selectedInboxIds.value = emptySet()
                }
                .onFailure { banner.value = it.message ?: "Unable to apply change set" }
            busy.value = false
        }
    }

    fun rejectPending() {
        pendingChangeSet.value = null
    }

    fun undoLatest() {
        viewModelScope.launch {
            busy.value = true
            val result = repository.undoLatest()
            banner.value = if (result == null) "No change set to undo." else "Undid latest change set."
            busy.value = false
        }
    }

    fun openNote(noteId: String) {
        viewModelScope.launch {
            selectedNote.value = repository.getNote(noteId)
        }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch {
            busy.value = true
            runCatching { repository.deleteNote(noteId) }
                .onSuccess { deleted ->
                    banner.value = if (deleted) "Removed note from vault." else "Note was already removed."
                    if (selectedNote.value?.id == noteId) selectedNote.value = null
                }
                .onFailure { banner.value = it.message ?: "Unable to remove note" }
            busy.value = false
        }
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
        viewModelScope.launch {
            searchResults.value = repository.search(query, limit = 20)
        }
    }

    fun clearBanner() {
        banner.value = null
    }

    companion object {
        fun factory(repository: VaultRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                KnowledgeViewModel(repository) as T
        }
    }
}

private data class VaultSnapshot(
    val inbox: List<InboxEntry>,
    val notes: List<VaultEntry>,
    val graph: VaultGraph,
    val selectedNote: VaultNote?,
)
