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
    fun `lets the model search then read a relevant note before answering`() = runTest {
        val engine = FakeLlmEngine(
            tokenResponses = listOf(
                listOf("{\"tool\":\"search_notes\",\"query\":\"launch code\"}"),
                listOf("{\"tool\":\"read_note\",\"noteId\":\"note-1\"}"),
                listOf("{\"final\":\"The launch code is 1234 [[Launch Plan]].\"}"),
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
        assertEquals(0, tools.listCallCount)
        assertEquals(listOf("launch code"), tools.searchQueries)
        assertEquals(listOf("note-1"), tools.readNoteIds)
        assertEquals(1, engine.resetCallCount)
        assertEquals(GenerationOptions.GroundedVaultAnswer, engine.lastResetOptions)
        assertEquals(
            List(3) { GenerationOptions.GroundedVaultAnswer },
            engine.optionsReceived,
        )

        assertEquals(3, engine.promptsReceived.size)
        assertTrue(engine.promptsReceived[0].contains("Available commands:"))
        assertTrue(engine.promptsReceived[0].contains("<user-question>"))
        assertTrue(!engine.promptsReceived[0].contains("Launch code: 1234"))
        assertTrue(engine.promptsReceived[1].contains("<tool-result name=\"search_notes\">"))
        assertTrue(engine.promptsReceived[1].contains("id: note-1"))
        assertTrue(engine.promptsReceived[2].contains("<tool-result name=\"read_note\">"))
        assertTrue(engine.promptsReceived[2].contains("title: Launch Plan"))
        assertTrue(engine.promptsReceived[2].contains("Launch code: 1234"))
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

    @Test
    fun `does not accept a final answer until the vault has been inspected`() = runTest {
        val engine = FakeLlmEngine(
            tokenResponses = listOf(
                listOf("{\"final\":\"The launch code is 9999.\"}"),
                listOf("{\"tool\":\"search_notes\",\"query\":\"launch code\"}"),
                listOf("{\"tool\":\"read_note\",\"noteId\":\"note-1\"}"),
                listOf("{\"final\":\"The launch code is 1234 [[Launch Plan]].\"}"),
            ),
        )
        val tools = FakeVaultAnswerTools()
        engine.initialize("/tmp/model.litertlm")

        val answer = LocalLlmVaultAnswerGenerator(EngineController(engine, this)).answer(
            question = "What is the launch code?",
            tools = tools,
        )

        assertEquals("The launch code is 1234 [[Launch Plan]].", answer?.content)
        assertEquals(listOf("launch code"), tools.searchQueries)
        assertEquals(listOf("note-1"), tools.readNoteIds)
        assertTrue(engine.promptsReceived[1].contains("<tool-error name=\"final\">"))
    }

    private class FakeVaultAnswerTools : VaultAnswerTools {
        var listCallCount = 0
            private set
        val readNoteIds = mutableListOf<String>()
        val searchQueries = mutableListOf<String>()

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

        override suspend fun searchNotes(query: String, limit: Int): List<VaultSearchResult> {
            searchQueries += query
            return listOf(
                VaultSearchResult(
                    noteId = "note-1",
                    title = "Launch Plan",
                    path = "plans/launch.md",
                    snippet = "Launch code: 1234",
                    score = 100,
                ),
            )
        }

        override suspend fun readNote(noteId: String): VaultReadableNote? {
            readNoteIds += noteId
            return when (noteId) {
                "note-1" -> VaultReadableNote(
                    note = note("note-1", "plans/launch.md", "Launch Plan", "# Launch Plan\n\nLaunch code: 1234\n\nSee [[Checklist]]."),
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

                "note-2" -> VaultReadableNote(
                    note = note("note-2", "plans/checklist.md", "Checklist", "# Checklist\n\nConfirm the launch code before release."),
                    outgoingLinks = emptyList(),
                    backlinks = emptyList(),
                )

                "note-3" -> VaultReadableNote(
                    note = note("note-3", "plans/review.md", "Release review", "# Release review\n\nThe launch plan must be approved."),
                    outgoingLinks = emptyList(),
                    backlinks = emptyList(),
                )

                else -> null
            }
        }

        private fun note(id: String, path: String, title: String, markdown: String) =
            VaultNote(
                id = id,
                path = path,
                title = title,
                markdown = markdown,
                createdAt = 1,
                updatedAt = 2,
                revision = 1,
                contentHash = "hash",
                archived = false,
                tags = listOf("ops"),
                aliases = emptyList(),
                sourceInboxIds = emptyList(),
            )
    }
}
