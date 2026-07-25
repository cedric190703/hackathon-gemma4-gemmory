package com.gemmory.testing

import android.net.Uri
import com.gemmory.chat.domain.ChatMessage
import com.gemmory.chat.domain.ChatRepository
import com.gemmory.chat.domain.ChatSession
import com.gemmory.chat.domain.MessageRole
import com.gemmory.chat.domain.MessageStatus
import com.gemmory.core.dispatchers.AppDispatchers
import com.gemmory.core.filesystem.FileSystem
import com.gemmory.core.filesystem.RealFileSystem
import com.gemmory.inbox.domain.InboxEntry
import com.gemmory.inbox.domain.InboxEntryStatus
import com.gemmory.inference.BackendPreference
import com.gemmory.modelinstall.ModelCatalog
import com.gemmory.modelinstall.ModelInstallState
import com.gemmory.modelinstall.ModelInstaller
import com.gemmory.modelinstall.NetworkStatusProvider
import com.gemmory.settings.AppSettings
import com.gemmory.settings.SettingsRepository
import com.gemmory.vault.domain.ApplyResult
import com.gemmory.vault.domain.ProposedVaultChangeSet
import com.gemmory.vault.domain.UndoResult
import com.gemmory.vault.domain.VaultEntry
import com.gemmory.vault.domain.VaultGraph
import com.gemmory.vault.domain.VaultLink
import com.gemmory.vault.domain.VaultNote
import com.gemmory.vault.domain.VaultOperation
import com.gemmory.vault.domain.VaultOperationPreview
import com.gemmory.vault.domain.VaultRepository
import com.gemmory.vault.domain.VaultSearchResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.io.File

class TestDispatchers(private val dispatcher: CoroutineDispatcher) : AppDispatchers {
    override val main: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val inference: CoroutineDispatcher = dispatcher
}

/** In-memory [ChatRepository] with the same ordering guarantees as the Room one. */
class FakeChatRepository : ChatRepository {

    private val sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    private var idCounter = 0
    private var clock = 1_000L

    override fun observeSessions(): Flow<List<ChatSession>> = sessions

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        messages.map { all -> all.filter { it.conversationId == conversationId }.sortedBy { it.orderIndex } }

    override suspend fun createSession(title: String): ChatSession {
        val session = ChatSession(
            id = "session-${idCounter++}",
            title = title,
            createdAt = clock,
            updatedAt = clock++,
        )
        sessions.value = listOf(session) + sessions.value
        return session
    }

    override suspend fun mostRecentSessionId(): String? = sessions.value.firstOrNull()?.id

    override suspend fun listMessages(conversationId: String): List<ChatMessage> =
        messages.value.filter { it.conversationId == conversationId }.sortedBy { it.orderIndex }

    override suspend fun renameSession(conversationId: String, title: String) {
        sessions.value = sessions.value.map { session ->
            if (session.id == conversationId) {
                session.copy(title = title, updatedAt = clock++)
            } else {
                session
            }
        }
    }

    override suspend fun deleteSession(conversationId: String) {
        sessions.value = sessions.value.filterNot { it.id == conversationId }
        messages.value = messages.value.filterNot { it.conversationId == conversationId }
    }

    override suspend fun deleteMessage(conversationId: String, messageId: String) {
        messages.value = messages.value.filterNot { it.id == messageId && it.conversationId == conversationId }
        sessions.value = sessions.value.map { session ->
            if (session.id == conversationId) session.copy(updatedAt = clock++) else session
        }
    }

    override suspend fun appendMessage(
        conversationId: String,
        role: MessageRole,
        content: String,
        status: MessageStatus,
    ): ChatMessage {
        val next = (messages.value.filter { it.conversationId == conversationId }
            .maxOfOrNull { it.orderIndex } ?: -1L) + 1
        val message = ChatMessage(
            id = "message-${idCounter++}",
            conversationId = conversationId,
            role = role,
            content = content,
            status = status,
            orderIndex = next,
            createdAt = clock++,
        )
        messages.value = messages.value + message
        return message
    }

    override suspend fun updateMessage(
        messageId: String,
        content: String,
        status: MessageStatus,
        errorText: String?,
    ) {
        messages.value = messages.value.map { message ->
            if (message.id == messageId) {
                message.copy(content = content, status = status, errorText = errorText)
            } else {
                message
            }
        }
    }

    override suspend fun updateMessageContent(
        conversationId: String,
        messageId: String,
        content: String,
    ) {
        messages.value = messages.value.map { message ->
            if (message.id == messageId && message.conversationId == conversationId) {
                message.copy(content = content)
            } else {
                message
            }
        }
        sessions.value = sessions.value.map { session ->
            if (session.id == conversationId) session.copy(updatedAt = clock++) else session
        }
    }

    override suspend fun repairUnfinishedMessages(): Int {
        var repaired = 0
        messages.value = messages.value.map { message ->
            if (!message.status.isTerminal) {
                repaired++
                message.copy(status = MessageStatus.CANCELLED)
            } else {
                message
            }
        }
        return repaired
    }

    fun seed(vararg seeded: ChatMessage) {
        messages.value = messages.value + seeded
    }

    fun seedSession(session: ChatSession) {
        sessions.value = listOf(session) + sessions.value
    }

    fun snapshot(): List<ChatMessage> = messages.value.sortedBy { it.orderIndex }
}

class FakeModelInstaller(
    initial: ModelInstallState = ModelInstallState.Installed(
        ModelCatalog.default,
        "/tmp/model.litertlm",
        ModelCatalog.default.sizeBytes,
    ),
) : ModelInstaller {

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<ModelInstallState> = _state

    var refreshCount = 0
    var downloadCount = 0
    var importCount = 0
    var cancelCount = 0
    var removeCount = 0

    override suspend fun refresh() {
        refreshCount++
    }

    override fun startDownload(allowMeteredNetwork: Boolean) {
        downloadCount++
    }

    override fun startImport(uri: Uri) {
        importCount++
    }

    override fun cancel() {
        cancelCount++
    }

    override suspend fun remove() {
        removeCount++
        _state.value = ModelInstallState.NotInstalled(ModelCatalog.default)
    }

    fun emit(state: ModelInstallState) {
        _state.value = state
    }
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val _settings = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = _settings

    override suspend fun setBackendPreference(preference: BackendPreference) {
        _settings.value = _settings.value.copy(backendPreference = preference)
    }

    override suspend fun setModelDownloadUrl(url: String) {
        _settings.value = _settings.value.copy(modelDownloadUrl = url)
    }

    override suspend fun setAllowMeteredDownload(allow: Boolean) {
        _settings.value = _settings.value.copy(allowMeteredDownload = allow)
    }
}

class FakeVaultRepository(
    private val answerDelayMs: Long = 0L,
    private val answerProvider: (String, String) -> String = { _, question -> "Vault answer: $question" },
) : VaultRepository {

    val askedQuestions = mutableListOf<Pair<String, String>>()

    override fun observeInbox(): Flow<List<InboxEntry>> = flowOf(emptyList())

    override fun observeNotes(): Flow<List<VaultEntry>> = flowOf(emptyList())

    override fun observeAllLinks(): Flow<List<VaultLink>> = flowOf(emptyList())

    override fun observeGraph(): Flow<VaultGraph> = flowOf(VaultGraph())

    override fun observeBacklinks(noteId: String): Flow<List<VaultLink>> = flowOf(emptyList())

    override fun observeOutgoingLinks(noteId: String): Flow<List<VaultLink>> = flowOf(emptyList())

    override suspend fun captureInbox(text: String): InboxEntry =
        InboxEntry(
            id = "inbox-0",
            text = text,
            status = InboxEntryStatus.DRAFT,
            createdAt = 1L,
            updatedAt = 1L,
            processedAt = null,
            lastError = null,
            resultNoteIds = emptyList(),
        )

    override suspend fun updateInboxText(id: String, text: String) = Unit

    override suspend fun deleteInboxEntries(ids: List<String>) = Unit

    override suspend fun proposeProcessing(entryIds: List<String>): ProposedVaultChangeSet =
        emptyChangeSet("process")

    override suspend fun proposeAllUnprocessed(): ProposedVaultChangeSet =
        emptyChangeSet("process all")

    override suspend fun preview(
        operations: List<VaultOperation>,
        request: String,
        sourceInboxIds: List<String>,
    ): ProposedVaultChangeSet =
        ProposedVaultChangeSet("change-0", request, sourceInboxIds, operations, emptyList(), emptyList())

    override suspend fun apply(changeSet: ProposedVaultChangeSet): ApplyResult =
        ApplyResult(changeSet.id, emptyList())

    override suspend fun undoLatest(): UndoResult? = null

    override suspend fun getNote(noteId: String): VaultNote? = null

    override suspend fun deleteNote(noteId: String): Boolean = true

    override suspend fun search(query: String, limit: Int): List<VaultSearchResult> = emptyList()

    override suspend fun answerVaultQuestion(conversationId: String, question: String): String {
        if (answerDelayMs > 0L) delay(answerDelayMs)
        askedQuestions += conversationId to question
        return answerProvider(conversationId, question)
    }

    private fun emptyChangeSet(request: String): ProposedVaultChangeSet =
        ProposedVaultChangeSet(
            id = "change-0",
            userRequest = request,
            sourceInboxIds = emptyList(),
            operations = emptyList(),
            validationErrors = emptyList(),
            previews = emptyList<VaultOperationPreview>(),
        )
}

class FakeNetworkStatusProvider(
    var connected: Boolean = true,
    var metered: Boolean = false,
) : NetworkStatusProvider {
    override fun isConnected(): Boolean = connected
    override fun isMetered(): Boolean = metered
}

/** Real file system with an overridable free-space value. */
class TestFileSystem(private var freeSpace: Long = Long.MAX_VALUE) : FileSystem {
    private val delegate = RealFileSystem()

    fun setUsableSpace(bytes: Long) {
        freeSpace = bytes
    }

    override fun usableSpaceBytes(directory: File): Long {
        delegate.ensureDirectory(directory)
        return freeSpace
    }

    override fun exists(file: File): Boolean = delegate.exists(file)
    override fun sizeBytes(file: File): Long = delegate.sizeBytes(file)
    override fun delete(file: File): Boolean = delegate.delete(file)
    override fun ensureDirectory(directory: File) = delegate.ensureDirectory(directory)
    override fun atomicMove(source: File, target: File) = delegate.atomicMove(source, target)
}
