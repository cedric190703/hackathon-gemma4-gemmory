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
    fun `puts matching files and links directly in the model prompt`() = runTest {
        val engine = FakeLlmEngine(
            tokenResponses = listOf(
                listOf("The launch code is 1234 [[Launch Plan]]."),
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
        assertEquals(listOf("note-1", "note-2", "note-3"), answer?.citationNoteIds)
        assertEquals(1, tools.listCallCount)
        assertEquals(listOf("note-1", "note-2", "note-3"), tools.readNoteIds)
        assertEquals(1, engine.resetCallCount)
        assertEquals(GenerationOptions.GroundedVaultAnswer, engine.lastResetOptions)
        assertEquals(
            listOf(GenerationOptions.GroundedVaultAnswer),
            engine.optionsReceived,
        )

        val prompt = engine.promptsReceived.single()
        assertTrue(prompt.contains("<user-question>"))
        assertTrue(prompt.contains("<vault-files>"))
        assertTrue(prompt.contains("title: Launch Plan"))
        assertTrue(prompt.contains("path: plans/launch.md"))
        assertTrue(prompt.contains("Launch code: 1234"))
        assertTrue(prompt.contains("outgoing-links: Checklist (file id: note-2, resolved)"))
        assertTrue(prompt.contains("backlinks: Launch Plan (file id: note-3, resolved)"))
        assertTrue(prompt.contains("# Checklist\n\nConfirm the launch code before release."))
        assertTrue(prompt.contains("# Release review\n\nThe launch plan must be approved."))
        assertTrue(!prompt.contains("TOOL:"))
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
