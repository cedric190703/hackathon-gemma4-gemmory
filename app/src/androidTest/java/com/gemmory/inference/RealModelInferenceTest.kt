package com.gemmory.inference

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemmory.core.dispatchers.DefaultAppDispatchers
import com.gemmory.modelinstall.ModelCatalog
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Milestone 2 inference spike, kept as a repeatable device test.
 *
 * It is skipped automatically unless the real model is present, so ordinary CI
 * never needs the 2.5 GB artefact. See `docs/DEVICE_TESTING.md` for how to push
 * the model and run this class, and where to paste the printed benchmark line.
 *
 *     adb push gemma-4-E2B-it.litertlm /sdcard/Download/
 *     ./gradlew :app:connectedDebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=\
 *         com.gemmory.inference.RealModelInferenceTest
 */
@RunWith(AndroidJUnit4::class)
class RealModelInferenceTest {

    private val tag = "GemmorySpike"

    private fun locateModel(): File? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val candidates = listOf(
            File(context.filesDir, "models/${ModelCatalog.default.fileName}"),
            File("/sdcard/Download/${ModelCatalog.default.fileName}"),
            File("/data/local/tmp/${ModelCatalog.default.fileName}"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    @Test
    fun loadsGeneratesStreamsAndDisposesCleanly() = runBlocking {
        val model = locateModel()
        assumeTrue("real model not present on the device; spike skipped", model != null)
        requireNotNull(model)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = LiteRtLlmEngine(
            context = context,
            configProvider = { InferenceConfig(backendPreference = BackendPreference.AUTO) },
            dispatchers = DefaultAppDispatchers(),
        )

        val runtime = Runtime.getRuntime()
        val memoryBefore = runtime.totalMemory() - runtime.freeMemory()

        val initStart = System.currentTimeMillis()
        engine.initialize(model.absolutePath)
        val initMs = System.currentTimeMillis() - initStart

        val diagnostics = engine.diagnostics.value
        assertTrue(
            "engine failed to initialize: ${diagnostics.state}",
            diagnostics.state is EngineState.Ready,
        )

        val events = withTimeout(TIMEOUT_MS) {
            engine.generate(
                conversationId = "spike",
                prompt = "In one short sentence, what is on-device inference?",
            ).toList()
        }

        val text = events.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text }
        val metrics = events.filterIsInstance<GenerationEvent.Metrics>().lastOrNull()
        val memoryAfter = runtime.totalMemory() - runtime.freeMemory()

        assertTrue("no tokens were streamed", text.isNotBlank())
        assertTrue("generation did not complete", events.contains(GenerationEvent.Completed))
        assertTrue("more than one chunk expected for streaming", events.count { it is GenerationEvent.Token } > 1)

        Log.i(
            tag,
            buildString {
                append("BENCHMARK ")
                append("device=${Build.MANUFACTURER} ${Build.MODEL}; ")
                append("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); ")
                append("backend=${diagnostics.selectedBackend}; ")
                append("attempted=${diagnostics.backendFallback?.attempted}; ")
                append("failures=${diagnostics.backendFallback?.failures}; ")
                append("modelBytes=${model.length()}; ")
                append("initMs=$initMs; ")
                append("ttftMs=${metrics?.timeToFirstTokenMs}; ")
                append("decodeTps=${metrics?.tokensPerSecond}; ")
                append("prefillTps=${metrics?.prefillTokensPerSecond}; ")
                append("contextTokens=${metrics?.contextTokenCount}; ")
                append("javaHeapDeltaMb=${(memoryAfter - memoryBefore) / (1024 * 1024)}; ")
                append("responseChars=${text.length}")
            },
        )

        // Cancellation must be honoured and must leave the engine usable.
        val cancelled = withTimeout(TIMEOUT_MS) {
            val collected = mutableListOf<GenerationEvent>()
            engine.generate("spike", "Write a very long essay about the ocean.")
                .collect { event ->
                    collected += event
                    if (collected.count { it is GenerationEvent.Token } >= 3) engine.cancel()
                }
            collected
        }
        assertTrue(
            "cancellation produced no terminal event",
            cancelled.any { it is GenerationEvent.Cancelled || it is GenerationEvent.Completed },
        )

        engine.resetConversation("spike", emptyList())
        engine.close()
        assertTrue(engine.diagnostics.value.state is EngineState.Closed)
    }

    private companion object {
        const val TIMEOUT_MS = 180_000L
    }
}
