package com.gemmory.chat.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemmory.chat.data.entities.ConversationEntity
import com.gemmory.chat.data.entities.MessageEntity
import com.gemmory.chat.domain.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatDatabaseTest {

    private lateinit var database: ChatDatabase
    private lateinit var conversations: ConversationDao
    private lateinit var messages: MessageDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        conversations = database.conversationDao()
        messages = database.messageDao()
    }

    @After
    fun tearDown() = database.close()

    private suspend fun seedConversation(id: String = "c1") {
        conversations.upsert(ConversationEntity(id, "Title", 1, 1))
    }

    private fun message(
        id: String,
        order: Long,
        status: MessageStatus = MessageStatus.COMPLETE,
        conversationId: String = "c1",
    ) = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = "USER",
        content = "content-$id",
        status = status.name,
        orderIndex = order,
        createdAt = order,
    )

    @Test
    fun messagesAreReturnedInOrder() = runTest {
        seedConversation()
        messages.upsert(message("b", 2))
        messages.upsert(message("a", 1))
        messages.upsert(message("c", 3))

        val stored = messages.listForConversation("c1")

        assertEquals(listOf("a", "b", "c"), stored.map { it.id })
        assertEquals(3L, messages.maxOrderIndex("c1"))
    }

    @Test
    fun contentAndStatusAreUpdatedInPlace() = runTest {
        seedConversation()
        messages.upsert(message("a", 1, MessageStatus.GENERATING))

        messages.updateContentAndStatus("a", "final answer", MessageStatus.COMPLETE.name, null)

        val stored = messages.listForConversation("c1").single()
        assertEquals("final answer", stored.content)
        assertEquals(MessageStatus.COMPLETE.name, stored.status)
        assertNull(stored.errorText)
    }

    @Test
    fun unfinishedMessagesAreDemotedAfterProcessDeath() = runTest {
        seedConversation()
        messages.upsert(message("pending", 1, MessageStatus.PENDING))
        messages.upsert(message("generating", 2, MessageStatus.GENERATING))
        messages.upsert(message("complete", 3, MessageStatus.COMPLETE))

        val repaired = messages.demoteUnfinished(
            cancelled = MessageStatus.CANCELLED.name,
            pending = MessageStatus.PENDING.name,
            generating = MessageStatus.GENERATING.name,
        )

        assertEquals(2, repaired)
        val stored = messages.listForConversation("c1").associateBy { it.id }
        assertEquals(MessageStatus.CANCELLED.name, stored.getValue("pending").status)
        assertEquals(MessageStatus.CANCELLED.name, stored.getValue("generating").status)
        assertEquals(MessageStatus.COMPLETE.name, stored.getValue("complete").status)
    }

    @Test
    fun deletingAConversationCascadesToItsMessages() = runTest {
        seedConversation()
        messages.upsert(message("a", 1))

        conversations.delete("c1")

        assertEquals(0, messages.countForConversation("c1"))
    }

    @Test
    fun conversationsAreObservedMostRecentlyUpdatedFirst() = runTest {
        conversations.upsert(ConversationEntity("old", "Old", 1, 10))
        conversations.upsert(ConversationEntity("new", "New", 2, 20))

        val observed = conversations.observeAll().first()

        assertEquals(listOf("new", "old"), observed.map { it.id })
        assertEquals("new", conversations.mostRecent()?.id)
    }

    @Test
    fun conversationsSurviveReopeningTheDatabase() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("restart-test.db")
        val first = Room.databaseBuilder(context, ChatDatabase::class.java, "restart-test.db").build()
        first.conversationDao().upsert(ConversationEntity("c1", "Persisted", 1, 1))
        first.messageDao().upsert(message("a", 1))
        first.close()

        val second = Room.databaseBuilder(context, ChatDatabase::class.java, "restart-test.db").build()
        try {
            assertEquals("Persisted", second.conversationDao().findById("c1")?.title)
            assertEquals(1, second.messageDao().countForConversation("c1"))
        } finally {
            second.close()
            context.deleteDatabase("restart-test.db")
        }
    }
}
