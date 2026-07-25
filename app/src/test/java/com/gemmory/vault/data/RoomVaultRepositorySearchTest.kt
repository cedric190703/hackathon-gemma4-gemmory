package com.gemmory.vault.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gemmory.testing.TestDispatchers
import com.gemmory.vault.domain.VaultOperation
import com.gemmory.vault.storage.MarkdownVaultStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomVaultRepositorySearchTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: KnowledgeDatabase
    private lateinit var repository: RoomVaultRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, KnowledgeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val storage = MarkdownVaultStorage(temporaryFolder.newFolder())
        repository = RoomVaultRepository(
            database = database,
            dao = database.knowledgeDao(),
            storage = storage,
            dispatchers = TestDispatchers(Dispatchers.Unconfined),
        )
    }

    @After
    fun tearDown() = database.close()

    private suspend fun seedNote(title: String, body: String): String {
        val markdown = """
            ---
            id: "pending"
            title: "$title"
            tags:
            aliases:
            ---

            # $title

            $body
        """.trimIndent()
        val changeSet = repository.preview(
            operations = listOf(
                VaultOperation.CreateNote(
                    temporaryId = "tmp",
                    proposedPath = "concepts/${title.lowercase().replace(" ", "-")}.md",
                    title = title,
                    markdown = markdown,
                    sourceInboxIds = listOf("seed"),
                ),
            ),
            request = "seed",
            sourceInboxIds = listOf("seed"),
        )
        val result = repository.apply(changeSet)
        return result.affectedNoteIds.single()
    }

    @Test
    fun searchToleratesPunctuationInTheQuery() = runTest {
        seedNote("Project Deadline", "The deadline for the launch is Monday.")

        val results = repository.search("What's the deadline: Monday?", limit = 5)

        assertTrue("expected a match despite punctuation, got $results", results.isNotEmpty())
    }

    @Test
    fun answerVaultQuestionReturnsCitedContextInsteadOfGenericFallback() = runTest {
        seedNote("Project Deadline", "The deadline for the launch is Monday.")

        val answer = repository.answerVaultQuestion("conversation-1", "What's the deadline: Monday?")

        assertFalse(answer.contains("I could not find this in your vault."))
        assertTrue(answer.contains("Project Deadline"))
    }

    @Test
    fun editingANoteDoesNotLeaveStaleFtsRows() = runTest {
        val noteId = seedNote("Project Deadline", "The deadline for the launch is Monday.")
        val note = requireNotNull(repository.getNote(noteId))

        val changeSet = repository.preview(
            operations = listOf(
                VaultOperation.UpdateNote(
                    noteId = noteId,
                    expectedRevision = note.revision,
                    replacementMarkdown = note.markdown.replace("Monday", "Tuesday"),
                    reason = "reschedule",
                ),
            ),
            request = "update",
            sourceInboxIds = emptyList(),
        )
        repository.apply(changeSet)

        val ftsRowCount = database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM vault_note_fts WHERE noteId = ?",
            arrayOf(noteId),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

        assertTrue("expected exactly one fts row per note after an edit, found $ftsRowCount", ftsRowCount == 1)
    }
}
