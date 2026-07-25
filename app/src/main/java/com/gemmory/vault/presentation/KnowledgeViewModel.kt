package com.gemmory.vault.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gemmory.inbox.domain.InboxEntry
import com.gemmory.vault.domain.ProposedVaultChangeSet
import com.gemmory.vault.domain.VaultEntry
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

    val uiState: StateFlow<KnowledgeUiState> = combine(
        combine(inbox, notes, selectedNote, ::Triple),
        combine(searchQuery, searchResults, selectedInboxIds, ::Triple),
        combine(pendingChangeSet, busy, ::Pair),
        banner,
    ) { first, second, third, message ->
        KnowledgeUiState(
            inbox = first.first,
            notes = first.second,
            selectedNote = first.third,
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

    fun processAll() {
        viewModelScope.launch {
            busy.value = true
            pendingChangeSet.value = repository.proposeAllUnprocessed()
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
