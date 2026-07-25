package com.gemmory.vaultagent

import com.gemmory.inference.EngineController
import com.gemmory.inference.FakeLlmEngine
import com.gemmory.inference.GenerationOptions
import com.gemmory.inference.InferenceError
import com.gemmory.vault.domain.VaultProcessingExistingNote
import com.gemmory.vault.domain.VaultProcessingInboxEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmVaultNoteProcessorTest {

    @Test
    fun `uses the loaded model to rewrite and link inbox notes`() = runTest {
        val response = """
            Here is the JSON:
            {
              "notes": [
                {
                  "title": "Launch Follow-up",
                  "sourceInboxIds": ["inbox-1"],
                  "tags": ["launch", "planning"],
                  "aliases": ["launch tasks"],
                  "bodyMarkdown": "## Summary\nFollow up with Ada about the launch checklist.\n\n## Connections\n- [[Launch Plan]]: depends on the checklist."
                }
              ]
            }
        """.trimIndent()
        val engine = FakeLlmEngine(listOf(response))
        val controller = EngineController(engine, this)
        val processor = LocalLlmVaultNoteProcessor(controller)
        engine.initialize("/tmp/model.litertlm")

        val drafts = processor.processInbox(
            entries = listOf(VaultProcessingInboxEntry("inbox-1", "ada asked me to follow up on launch checklist")),
            existingNotes = listOf(
                VaultProcessingExistingNote(
                    noteId = "note-1",
                    title = "Launch Plan",
                    path = "projects/launch-plan.md",
                    tags = listOf("project"),
                    aliases = listOf("go-live"),
                ),
            ),
        )

        assertEquals(1, engine.resetCallCount)
        assertEquals(GenerationOptions.VaultNoteProcessing, engine.lastResetOptions)
        assertEquals(listOf(GenerationOptions.VaultNoteProcessing), engine.optionsReceived)

        val prompt = engine.promptsReceived.single()
        assertTrue(prompt.contains("MODE: PROCESS_INBOX_NOTES"))
        assertTrue(prompt.contains("[[Launch Plan]]"))
        assertTrue(prompt.contains("Rewrite fragmented thoughts"))

        assertEquals(1, drafts?.size)
        val draft = drafts!!.single()
        assertEquals("Launch Follow-up", draft.title)
        assertEquals(listOf("inbox-1"), draft.sourceInboxIds)
        assertEquals(listOf("launch", "planning"), draft.tags)
        assertEquals(listOf("launch tasks"), draft.aliases)
        assertTrue(draft.bodyMarkdown.contains("[[Launch Plan]]"))
    }

    @Test
    fun `waits for an in flight model load before processing inbox notes`() = runTest {
        val engine = FakeLlmEngine(
            tokens = listOf(
                """
                {"notes":[{"title":"Loaded Note","sourceInboxIds":["inbox-1"],"bodyMarkdown":"## Summary\nReady after load."}]}
                """.trimIndent(),
            ),
            initializationDelayMs = 1_000,
        )
        val controller = EngineController(engine, this)
        val processor = LocalLlmVaultNoteProcessor(controller)

        controller.ensureLoaded("/tmp/model.litertlm")

        val drafts = processor.processInbox(
            entries = listOf(VaultProcessingInboxEntry("inbox-1", "rough thought")),
            existingNotes = emptyList(),
        )

        assertEquals(1, engine.initializeCallCount)
        assertEquals(1, drafts?.size)
        assertEquals("Loaded Note", drafts!!.single().title)
    }

    @Test
    fun `returns null without generation when the model is not ready`() = runTest {
        val engine = FakeLlmEngine()
        val processor = LocalLlmVaultNoteProcessor(EngineController(engine, this))

        val result = processor.processInbox(
            entries = listOf(VaultProcessingInboxEntry("inbox-1", "rough thought")),
            existingNotes = emptyList(),
        )

        assertNull(result)
        assertEquals(emptyList<String>(), engine.promptsReceived)
    }

    @Test
    fun `returns an empty proposal when model output is not valid JSON`() = runTest {
        val engine = FakeLlmEngine(listOf("I processed the note but did not return JSON."))
        val controller = EngineController(engine, this)
        val processor = LocalLlmVaultNoteProcessor(controller)
        engine.initialize("/tmp/model.litertlm")

        val result = processor.processInbox(
            entries = listOf(VaultProcessingInboxEntry("inbox-1", "rough thought")),
            existingNotes = emptyList(),
        )

        assertTrue(result!!.isEmpty())
        assertEquals(1, engine.promptsReceived.size)
    }

    @Test
    fun `returns an empty proposal when generation fails after the model is ready`() = runTest {
        val engine = FakeLlmEngine(failGenerationWith = InferenceError.GenerationFailed("native error"))
        val controller = EngineController(engine, this)
        val processor = LocalLlmVaultNoteProcessor(controller)
        engine.initialize("/tmp/model.litertlm")

        val result = processor.processInbox(
            entries = listOf(VaultProcessingInboxEntry("inbox-1", "rough thought")),
            existingNotes = emptyList(),
        )

        assertTrue(result!!.isEmpty())
    }

    @Test
    fun `drops notes with source ids that were not requested`() = runTest {
        val engine = FakeLlmEngine(
            listOf(
                """
                {"notes":[{"title":"Ignored","sourceInboxIds":["other"],"bodyMarkdown":"## Summary\nNo source."}]}
                """.trimIndent(),
            ),
        )
        val controller = EngineController(engine, this)
        val processor = LocalLlmVaultNoteProcessor(controller)
        engine.initialize("/tmp/model.litertlm")

        val result = processor.processInbox(
            entries = listOf(VaultProcessingInboxEntry("inbox-1", "rough thought")),
            existingNotes = emptyList(),
        )

        assertTrue(result!!.isEmpty())
    }
}
