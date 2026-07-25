package com.gemmory.vaultagent

import com.gemmory.inference.EngineController
import com.gemmory.inference.FakeLlmEngine
import com.gemmory.inference.GenerationOptions
import com.gemmory.vault.domain.LinkResolutionStatus
import com.gemmory.vault.domain.VaultAnswerTools
import com.gemmory.vault.domain.VaultLink
import com.gemmory.vault.domain.VaultNote
import com.gemmory.vault.domain.VaultNoteSummary
import com.gemmory.vault.domain.VaultReadableNote
import com.gemmory.vault.domain.VaultSearchResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalLlmVaultAnswerGeneratorTest {

    @Test
    fun `lets the model list notes read a note and answer from tool results`() = runTest {
        val engine = FakeLlmEngine(
            tokenResponses = listOf(
                listOf("TOOL: LIST_NOTES limit=20"),
                listOf("TOOL: READ_NOTE id=\"note-1\""),
                listOf("FINAL: The launch code is 1234 [[Launch Plan]]."),
            ),
        )
        val controller = EngineController(engine, this)
        val generator = LocalLlmVaultAnswerGenerator(controller)
        val tools = FakeVaultAnswerTools()
        engine.initialize("/tmp/model.litertlm")

        val answer = generator.answer(
            question = "What is the launch code?",
            tools = tools,
        )

        assertEquals("The launch code is 1234 [[Launch Plan]].", answer?.content)
        assertEquals(listOf("note-1"), answer?.citationNoteIds)
        assertEquals(1, tools.listCallCount)
        assertEquals(listOf("note-1"), tools.readNoteIds)
        assertEquals(1, engine.resetCallCount)
        assertEquals(GenerationOptions.GroundedVaultAnswer, engine.lastResetOptions)
        assertEquals(
            listOf(
                GenerationOptions.GroundedVaultAnswer,
                GenerationOptions.GroundedVaultAnswer,
                GenerationOptions.GroundedVaultAnswer,
            ),
            engine.optionsReceived,
        )

        val initialPrompt = engine.promptsReceived[0]
        assertTrue(initialPrompt.contains("MODE: VAULT_TOOL_AGENT"))
        assertTrue(initialPrompt.contains("TOOL: LIST_NOTES"))
        assertTrue(initialPrompt.contains("TOOL: READ_NOTE"))
        assertTrue(initialPrompt.contains("READ_NOTE returns markdown plus outgoing links and backlinks"))

        val listResultPrompt = engine.promptsReceived[1]
        assertTrue(listResultPrompt.contains("TOOL_RESULT:"))
        assertTrue(listResultPrompt.contains("id=note-1"))
        assertTrue(listResultPrompt.contains("title=\"Launch Plan\""))

        val readResultPrompt = engine.promptsReceived[2]
        assertTrue(readResultPrompt.contains("MARKDOWN:"))
        assertTrue(readResultPrompt.contains("Launch code: 1234"))
        assertTrue(readResultPrompt.contains("OUTGOING_LINKS:"))
        assertTrue(readResultPrompt.contains("targetId=note-2"))
        assertTrue(readResultPrompt.contains("BACKLINKS:"))
        assertTrue(readResultPrompt.contains("sourceId=note-3"))
    }

    @Test
    fun `returns null without touching tools when the model is not ready`() = runTest {
        val engine = FakeLlmEngine()
        val generator = LocalLlmVaultAnswerGenerator(EngineController(engine, this))
        val tools = FakeVaultAnswerTools()

        val answer = generator.answer(
            question = "What is the launch code?",
            tools = tools,
        )

        assertNull(answer)
        assertTrue(engine.promptsReceived.isEmpty())
        assertEquals(0, tools.listCallCount)
        assertTrue(tools.readNoteIds.isEmpty())
    }

    private class FakeVaultAnswerTools : VaultAnswerTools {
        var listCallCount = 0
            private set
        val readNoteIds = mutableListOf<String>()

        override suspend fun listNotes(limit: Int): List<VaultNoteSummary> {
            listCallCount++
            return listOf(
                VaultNoteSummary(
                    noteId = "note-1",
                    title = "Launch Plan",
                    path = "plans/launch.md",
                    tags = listOf("ops"),
                    aliases = emptyList(),
                    outgoingLinkCount = 1,
                    backlinkCount = 1,
                ),
            )
        }

        override suspend fun searchNotes(query: String, limit: Int): List<VaultSearchResult> = emptyList()

        override suspend fun readNote(noteId: String): VaultReadableNote? {
            readNoteIds += noteId
            if (noteId != "note-1") return null
            return VaultReadableNote(
                note = VaultNote(
                    id = "note-1",
                    path = "plans/launch.md",
                    title = "Launch Plan",
                    markdown = "# Launch Plan\n\nLaunch code: 1234\n\nSee [[Checklist]].",
                    createdAt = 1,
                    updatedAt = 2,
                    revision = 1,
                    contentHash = "hash",
                    archived = false,
                    tags = listOf("ops"),
                    aliases = emptyList(),
                    sourceInboxIds = emptyList(),
                ),
                outgoingLinks = listOf(
                    VaultLink(
                        id = "link-1",
                        sourceNoteId = "note-1",
                        targetNoteId = "note-2",
                        rawTarget = "Checklist",
                        label = null,
                        status = LinkResolutionStatus.RESOLVED,
                    ),
                ),
                backlinks = listOf(
                    VaultLink(
                        id = "link-2",
                        sourceNoteId = "note-3",
                        targetNoteId = "note-1",
                        rawTarget = "Launch Plan",
                        label = "plan",
                        status = LinkResolutionStatus.RESOLVED,
                    ),
                ),
            )
        }
    }
}
