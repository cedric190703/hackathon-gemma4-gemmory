package com.gemmory.vaultagent

import com.gemmory.inference.EngineController
import com.gemmory.inference.FakeLlmEngine
import com.gemmory.vault.domain.VaultAnswerContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalLlmVaultAnswerGeneratorTest {

    @Test
    fun `uses the loaded model to answer from vault context`() = runTest {
        val engine = FakeLlmEngine(listOf("The launch code is 1234 [[Launch Plan]]."))
        val controller = EngineController(engine, this)
        val generator = LocalLlmVaultAnswerGenerator(controller)
        engine.initialize("/tmp/model.litertlm")

        val answer = generator.answer(
            question = "What is the launch code?",
            contexts = listOf(
                VaultAnswerContext(
                    noteId = "note-1",
                    title = "Launch Plan",
                    path = "plans/launch.md",
                    snippet = "Launch code: 1234",
                    markdown = "# Launch Plan\n\nLaunch code: 1234",
                ),
            ),
        )

        assertEquals("The launch code is 1234 [[Launch Plan]].", answer)
        assertEquals(1, engine.resetCallCount)
        assertTrue(engine.promptsReceived.single().contains("Launch code: 1234"))
    }

    @Test
    fun `returns null without touching generation when the model is not ready`() = runTest {
        val engine = FakeLlmEngine()
        val generator = LocalLlmVaultAnswerGenerator(EngineController(engine, this))

        val answer = generator.answer(
            question = "What is the launch code?",
            contexts = listOf(
                VaultAnswerContext(
                    noteId = "note-1",
                    title = "Launch Plan",
                    path = "plans/launch.md",
                    snippet = "Launch code: 1234",
                    markdown = "# Launch Plan\n\nLaunch code: 1234",
                ),
            ),
        )

        assertNull(answer)
        assertTrue(engine.promptsReceived.isEmpty())
    }
}
