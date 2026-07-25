package com.gemmory.chat.domain

import com.gemmory.chat.data.ConversationDao
import com.gemmory.chat.data.MessageDao
import com.gemmory.chat.data.entities.ConversationEntity
import com.gemmory.chat.data.entities.MessageEntity
import com.gemmory.core.dispatchers.AppDispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/** Persistence boundary for conversations and messages. */
interface ChatRepository {
    fun observeSessions(): Flow<List<ChatSession>>
    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>

    suspend fun createSession(title: String = DEFAULT_TITLE): ChatSession
    suspend fun mostRecentSessionId(): String?
    suspend fun listMessages(conversationId: String): List<ChatMessage>
    suspend fun deleteSession(conversationId: String)

    suspend fun appendMessage(
        conversationId: String,
        role: MessageRole,
        content: String,
        status: MessageStatus,
    ): ChatMessage

    suspend fun updateMessage(
        messageId: String,
        content: String,
        status: MessageStatus,
        errorText: String? = null,
    )

    /** Demotes messages left mid-generation by a process death. */
    suspend fun repairUnfinishedMessages(): Int

    companion object {
        const val DEFAULT_TITLE = "New conversation"
    }
}

class RoomChatRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val dispatchers: AppDispatchers,
    private val now: () -> Long = System::currentTimeMillis,
) : ChatRepository {

    override fun observeSessions(): Flow<List<ChatSession>> =
        conversationDao.observeAll()
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(dispatchers.io)

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        messageDao.observeForConversation(conversationId)
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(dispatchers.io)

    override suspend fun createSession(title: String): ChatSession = withContext(dispatchers.io) {
        val timestamp = now()
        val entity = ConversationEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        conversationDao.upsert(entity)
        entity.toDomain()
    }

    override suspend fun mostRecentSessionId(): String? = withContext(dispatchers.io) {
        conversationDao.mostRecent()?.id
    }

    override suspend fun listMessages(conversationId: String): List<ChatMessage> =
        withContext(dispatchers.io) {
            messageDao.listForConversation(conversationId).map { it.toDomain() }
        }

    override suspend fun deleteSession(conversationId: String) = withContext(dispatchers.io) {
        conversationDao.delete(conversationId)
    }

    override suspend fun appendMessage(
        conversationId: String,
        role: MessageRole,
        content: String,
        status: MessageStatus,
    ): ChatMessage = withContext(dispatchers.io) {
        val timestamp = now()
        val entity = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = role.name,
            content = content,
            status = status.name,
            orderIndex = messageDao.maxOrderIndex(conversationId) + 1,
            createdAt = timestamp,
        )
        messageDao.upsert(entity)
        conversationDao.touch(conversationId, timestamp)

        // The first user message becomes the conversation title.
        if (role == MessageRole.USER) {
            val existing = conversationDao.findById(conversationId)
            if (existing != null && existing.title == ChatRepository.DEFAULT_TITLE) {
                conversationDao.updateTitle(conversationId, content.toTitle(), timestamp)
            }
        }
        entity.toDomain()
    }

    override suspend fun updateMessage(
        messageId: String,
        content: String,
        status: MessageStatus,
        errorText: String?,
    ) = withContext(dispatchers.io) {
        messageDao.updateContentAndStatus(messageId, content, status.name, errorText)
    }

    override suspend fun repairUnfinishedMessages(): Int = withContext(dispatchers.io) {
        messageDao.demoteUnfinished(
            cancelled = MessageStatus.CANCELLED.name,
            pending = MessageStatus.PENDING.name,
            generating = MessageStatus.GENERATING.name,
        )
    }
}

internal fun ConversationEntity.toDomain() = ChatSession(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun MessageEntity.toDomain() = ChatMessage(
    id = id,
    conversationId = conversationId,
    role = enumValueOrDefault(role, MessageRole.ASSISTANT),
    content = content,
    status = enumValueOrDefault(status, MessageStatus.COMPLETE),
    orderIndex = orderIndex,
    createdAt = createdAt,
    errorText = errorText,
)

private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback

private fun String.toTitle(): String {
    val single = trim().replace(Regex("\\s+"), " ")
    return if (single.length <= 48) single else single.take(45) + "…"
}
