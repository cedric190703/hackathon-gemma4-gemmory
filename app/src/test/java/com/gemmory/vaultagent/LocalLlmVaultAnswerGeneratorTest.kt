package com.gemmory.vaultagent

import com.gemmory.inference.EngineController
import com.gemmory.inference.FakeLlmEngine
import com.gemmory.inference.GenerationOptions
import com.gemmory.vault.domain.VaultFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalLlmVaultAnswerGeneratorTest {

    @Test
    fun `loads every vault file into one direct-answer prompt`() = runTest {
        val engine = FakeLlmEngine(
            tokenResponses = listOf(listOf("The launch code is 1234 [[Launch Plan]].")),
        )
        val controller = EngineController(engine, this)
        engine.initialize("/tmp/model.litertlm")

        val answer = LocalLlmVaultAnswerGenerator(controller).answer(
            question = "What is the launch code?",
            vaultFiles = listOf(
                VaultFile("note-1", "Launch Plan", "plans/launch.md", "# Launch Plan\n\nLaunch code: 1234"),
                VaultFile("note-2", "Checklist", "plans/checklist.md", "# Checklist\n\nConfirm the launch code."),
            ),
        )

        assertEquals("The launch code is 1234 [[Launch Plan]].", answer?.content)
        assertEquals(listOf("note-1"), answer?.citationNoteIds)
        assertEquals(1, engine.resetCallCount)
        assertEquals(GenerationOptions.GroundedVaultAnswer, engine.lastResetOptions)
        assertEquals(listOf(GenerationOptions.GroundedVaultAnswer), engine.optionsReceived)
        assertEquals(1, engine.promptsReceived.size)

        val prompt = engine.promptsReceived.single()
        assertTrue(prompt.contains("MODE: VAULT_CONTEXT_ANSWER"))
        assertTrue(prompt.contains("<vault-file id=\"note-1\" path=\"plans/launch.md\" title=\"Launch Plan\">"))
        assertTrue(prompt.contains("Launch code: 1234"))
        assertTrue(prompt.contains("<vault-file id=\"note-2\" path=\"plans/checklist.md\" title=\"Checklist\">"))
        assertTrue(prompt.contains("Confirm the launch code."))
        assertTrue(prompt.contains("<user-question>\nWhat is the launch code?"))
        assertTrue(!prompt.contains("Available commands:"))
        assertTrue(!prompt.contains("<tool-result"))
    }

    @Test
    fun `returns null without generating when the model is not ready`() = runTest {
        val engine = FakeLlmEngine()

        val answer = LocalLlmVaultAnswerGenerator(EngineController(engine, this)).answer(
            question = "What is the launch code?",
            vaultFiles = listOf(VaultFile("note-1", "Launch Plan", "plans/launch.md", "Launch code: 1234")),
        )

        assertNull(answer)
        assertTrue(engine.promptsReceived.isEmpty())
    }
}
