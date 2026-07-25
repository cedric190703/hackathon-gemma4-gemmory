package com.gemmory.chat.presentation

import com.gemmory.chat.domain.ChatMessage
import com.gemmory.chat.domain.ChatSession
import com.gemmory.chat.domain.ContextPolicy
import com.gemmory.chat.domain.MessageRole
import com.gemmory.chat.domain.MessageStatus
import com.gemmory.inference.ConversationTurn
import com.gemmory.inference.EngineController
import com.gemmory.inference.FakeLlmEngine
import com.gemmory.inference.InferenceError
import com.gemmory.inference.TurnRole
import com.gemmory.modelinstall.ModelCatalog
import com.gemmory.modelinstall.ModelInstallState
import com.gemmory.testing.FakeChatRepository
import com.gemmory.testing.FakeModelInstaller
import com.gemmory.testing.FakeSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.buildViewModel(
        repository: FakeChatRepository = FakeChatRepository(),
        engine: FakeLlmEngine = FakeLlmEngine(),
        installer: FakeModelInstaller = FakeModelInstaller(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ): Fixture {
        val controller = EngineController(engine, this)
        val viewModel = ChatViewModel(
            repository = repository,
            installer = installer,
            engineController = controller,
            settingsRepository = settings,
            contextPolicy = ContextPolicy(budgetTokens = 2560),
        )
        // Keep the state flow hot for the duration of the test.
        backgroundScope.launch { viewModel.uiState.collect {} }
        return Fixture(viewModel, repository, engine, installer, controller)
    }

    private data class Fixture(
        val viewModel: ChatViewModel,
        val repository: FakeChatRepository,
        val engine: FakeLlmEngine,
        val installer: FakeModelInstaller,
        val controller: EngineController,
    )

    // ------------------------------------------------------- state transitions

    @Test
    fun `state is model missing when nothing is installed`() = runTest {
        val fixture = buildViewModel(
            installer = FakeModelInstaller(ModelInstallState.NotInstalled(ModelCatalog.default)),
        )
        advanceUntilIdle()

        assertEquals(TopLevelState.MODEL_MISSING, fixture.viewModel.uiState.value.topLevelState)
        assertFalse(fixture.viewModel.uiState.value.canSendPrompt)
    }

    @Test
    fun `installed model is loaded automatically and reaches chat ready`() = runTest {
        val fixture = buildViewModel()
        advanceUntilIdle()

        assertEquals(1, fixture.engine.initializeCallCount)
        assertEquals(TopLevelState.CHAT_READY, fixture.viewModel.uiState.value.topLevelState)
        assertTrue(fixture.viewModel.uiState.value.canSendPrompt)
    }

    @Test
    fun `download progress is surfaced as the downloading state`() = runTest {
        val installer = FakeModelInstaller(ModelInstallState.NotInstalled(ModelCatalog.default))
        val fixture = buildViewModel(installer = installer)
        advanceUntilIdle()

        installer.emit(
            ModelInstallState.Downloading(
                descriptor = ModelCatalog.default,
                downloadedBytes = 500,
                totalBytes = 1000,
                bytesPerSecond = 100,
                resumed = false,
            ),
        )
        advanceUntilIdle()

        assertEquals(TopLevelState.MODEL_DOWNLOADING, fixture.viewModel.uiState.value.topLevelState)
    }

    @Test
    fun `verification is its own state`() = runTest {
        val installer = FakeModelInstaller(ModelInstallState.NotInstalled(ModelCatalog.default))
        val fixture = buildViewModel(installer = installer)
        advanceUntilIdle()

        installer.emit(ModelInstallState.Verifying(ModelCatalog.default, 10, 100))
        advanceUntilIdle()

        assertEquals(TopLevelState.MODEL_VERIFYING, fixture.viewModel.uiState.value.topLevelState)
    }

    @Test
    fun `an initialization failure is a recoverable error with an action`() = runTest {
        val fixture = buildViewModel(
            engine = FakeLlmEngine(
                failInitializationWith = InferenceError.InitializationFailed("delegate error"),
            ),
        )
        advanceUntilIdle()

        val state = fixture.viewModel.uiState.value
        assertEquals(TopLevelState.RECOVERABLE_ERROR, state.topLevelState)
        assertEquals(RecoveryAction.RETRY_LOAD, state.errorBanner?.action)
    }

    @Test
    fun `an unsupported backend produces the unsupported device state and no retry`() = runTest {
        val fixture = buildViewModel(engine = FakeLlmEngine.unsupportedBackend())
        advanceUntilIdle()

        val state = fixture.viewModel.uiState.value
        assertEquals(TopLevelState.UNSUPPORTED_DEVICE, state.topLevelState)
        assertEquals(RecoveryAction.NONE, state.errorBanner?.action)
    }

    @Test
    fun `out of memory never offers an automatic retry`() = runTest {
        val fixture = buildViewModel(
            engine = FakeLlmEngine(failInitializationWith = InferenceError.OutOfMemory("no ram")),
        )
        advanceUntilIdle()

        assertEquals(RecoveryAction.NONE, fixture.viewModel.uiState.value.errorBanner?.action)
    }

    // ------------------------------------------------------------- generation

    @Test
    fun `sending a prompt streams tokens and persists the completed answer`() = runTest {
        val fixture = buildViewModel(engine = FakeLlmEngine(listOf("Hel", "lo", " there")))
        advanceUntilIdle()

        fixture.viewModel.onInputChange("hi")
        fixture.viewModel.send()
        advanceUntilIdle()

        val messages = fixture.repository.snapshot()
        assertEquals(2, messages.size)
        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals("hi", messages[0].content)
        assertEquals(MessageStatus.COMPLETE, messages[1].status)
        assertEquals("Hello there", messages[1].content)
        assertEquals(TopLevelState.CHAT_READY, fixture.viewModel.uiState.value.topLevelState)
    }

    @Test
    fun `the input is cleared as soon as the prompt is submitted`() = runTest {
        val fixture = buildViewModel()
        advanceUntilIdle()

        fixture.viewModel.onInputChange("question")
        fixture.viewModel.send()

        assertEquals("", fixture.viewModel.input.value)
    }

    @Test
    fun `a blank prompt is ignored`() = runTest {
        val fixture = buildViewModel()
        advanceUntilIdle()

        fixture.viewModel.onInputChange("   ")
        fixture.viewModel.send()
        advanceUntilIdle()

        assertTrue(fixture.repository.snapshot().isEmpty())
    }

    @Test
    fun `a second prompt is rejected while one is generating`() = runTest {
        val fixture = buildViewModel(engine = FakeLlmEngine(tokenDelayMs = 100))
        advanceUntilIdle()

        fixture.viewModel.onInputChange("first")
        fixture.viewModel.send()
        // Do not let the first generation finish.
        fixture.viewModel.onInputChange("second")
        fixture.viewModel.send()
        advanceUntilIdle()

        assertEquals(listOf("first"), fixture.engine.promptsReceived)
    }

    @Test
    fun `stopping generation persists the partial answer as cancelled`() = runTest {
        val fixture = buildViewModel(
            engine = FakeLlmEngine(listOf("a", "b", "c"), simulateCancellation = true),
        )
        advanceUntilIdle()

        fixture.viewModel.onInputChange("go")
        fixture.viewModel.send()
        advanceUntilIdle()

        val assistant = fixture.repository.snapshot().last()
        assertEquals(MessageStatus.CANCELLED, assistant.status)
        assertEquals("abc", assistant.content)
        assertFalse(
            "a cancelled answer must never be stored as complete",
            assistant.status == MessageStatus.COMPLETE,
        )
    }

    @Test
    fun `stop cancels the engine and lets a new prompt start immediately`() = runTest {
        val fixture = buildViewModel(engine = FakeLlmEngine(tokenDelayMs = 50))
        advanceUntilIdle()

        fixture.viewModel.onInputChange("first")
        fixture.viewModel.send()
        fixture.viewModel.stop()
        advanceUntilIdle()

        assertTrue(fixture.engine.cancelCallCount > 0)
        assertFalse(fixture.viewModel.uiState.value.isGenerating)

        fixture.viewModel.onInputChange("second")
        fixture.viewModel.send()
        advanceUntilIdle()

        assertTrue(fixture.engine.promptsReceived.contains("second"))
    }

    @Test
    fun `a generation failure is stored as a failed message and surfaced once`() = runTest {
        val fixture = buildViewModel(
            engine = FakeLlmEngine(
                failGenerationWith = InferenceError.GenerationFailed("native error"),
            ),
        )
        advanceUntilIdle()

        fixture.viewModel.onInputChange("boom")
        fixture.viewModel.send()
        advanceUntilIdle()

        val assistant = fixture.repository.snapshot().last()
        assertEquals(MessageStatus.FAILED, assistant.status)
        assertEquals("Generation failed", assistant.errorText)
        assertNotNull(fixture.viewModel.uiState.value.errorBanner)
    }

    @Test
    fun `ten consecutive prompts all complete`() = runTest {
        val fixture = buildViewModel()
        advanceUntilIdle()

        repeat(10) { index ->
            fixture.viewModel.onInputChange("prompt $index")
            fixture.viewModel.send()
            advanceUntilIdle()
        }

        val messages = fixture.repository.snapshot()
        assertEquals(20, messages.size)
        assertTrue(
            messages.filter { it.role == MessageRole.ASSISTANT }
                .all { it.status == MessageStatus.COMPLETE },
        )
        assertEquals(1, fixture.engine.initializeCallCount)
    }

    // -------------------------------------------------- conversation handling

    @Test
    fun `a new conversation resets the native conversation`() = runTest {
        val fixture = buildViewModel()
        advanceUntilIdle()
        val before = fixture.viewModel.uiState.value.conversationId

        fixture.viewModel.newConversation()
        advanceUntilIdle()

        val after = fixture.viewModel.uiState.value.conversationId
        assertTrue(after != before)
        assertTrue(fixture.engine.resetCallCount > 0)
        assertTrue(fixture.engine.lastReplayedHistory.isEmpty())
    }

    @Test
    fun `reopening a conversation replays only complete messages`() = runTest {
        val repository = FakeChatRepository()
        repository.seedSession(ChatSession("old", "Old chat", 1, 1))
        repository.seed(
            ChatMessage("m0", "old", MessageRole.USER, "first question", MessageStatus.COMPLETE, 0, 1),
            ChatMessage("m1", "old", MessageRole.ASSISTANT, "first answer", MessageStatus.COMPLETE, 1, 2),
            ChatMessage("m2", "old", MessageRole.USER, "second question", MessageStatus.COMPLETE, 2, 3),
            ChatMessage("m3", "old", MessageRole.ASSISTANT, "partial", MessageStatus.CANCELLED, 3, 4),
        )
        val fixture = buildViewModel(repository = repository)
        advanceUntilIdle()

        fixture.viewModel.openConversation("old")
        advanceUntilIdle()
        fixture.viewModel.onInputChange("third question")
        fixture.viewModel.send()
        advanceUntilIdle()

        assertEquals(
            listOf(
                ConversationTurn(TurnRole.USER, "first question"),
                ConversationTurn(TurnRole.ASSISTANT, "first answer"),
            ),
            fixture.engine.lastReplayedHistory,
        )
    }

    @Test
    fun `history is not replayed again for the second prompt in the same conversation`() = runTest {
        val fixture = buildViewModel()
        advanceUntilIdle()

        fixture.viewModel.onInputChange("one")
        fixture.viewModel.send()
        advanceUntilIdle()
        val afterFirst = fixture.engine.resetCallCount

        fixture.viewModel.onInputChange("two")
        fixture.viewModel.send()
        advanceUntilIdle()

        assertEquals(
            "the model must not be re-primed for every message",
            afterFirst,
            fixture.engine.resetCallCount,
        )
    }

    @Test
    fun `chat history is replayed after another feature uses the engine`() = runTest {
        val fixture = buildViewModel()
        advanceUntilIdle()

        fixture.viewModel.onInputChange("one")
        fixture.viewModel.send()
        advanceUntilIdle()

        fixture.controller.resetConversation("ask-vault", emptyList())
        val afterExternalUse = fixture.engine.resetCallCount

        fixture.viewModel.onInputChange("two")
        fixture.viewModel.send()
        advanceUntilIdle()

        assertTrue(fixture.engine.resetCallCount > afterExternalUse)
        assertEquals(
            listOf(
                ConversationTurn(TurnRole.USER, "one"),
                ConversationTurn(TurnRole.ASSISTANT, "Hello, world!"),
            ),
            fixture.engine.lastReplayedHistory,
        )
    }

    @Test
    fun `unfinished messages left by a process death are repaired at startup`() = runTest {
        val repository = FakeChatRepository()
        repository.seedSession(ChatSession("old", "Old chat", 1, 1))
        repository.seed(
            ChatMessage("m0", "old", MessageRole.USER, "q", MessageStatus.COMPLETE, 0, 1),
            ChatMessage("m1", "old", MessageRole.ASSISTANT, "half", MessageStatus.GENERATING, 1, 2),
        )

        buildViewModel(repository = repository)
        advanceUntilIdle()

        assertEquals(MessageStatus.CANCELLED, repository.snapshot().last().status)
    }

    @Test
    fun `the context notice reports how many older messages were dropped`() = runTest {
        val repository = FakeChatRepository()
        repository.seedSession(ChatSession("old", "Old chat", 1, 1))
        val long = "x".repeat(4000)
        repository.seed(
            ChatMessage("m0", "old", MessageRole.USER, long, MessageStatus.COMPLETE, 0, 1),
            ChatMessage("m1", "old", MessageRole.ASSISTANT, long, MessageStatus.COMPLETE, 1, 2),
            ChatMessage("m2", "old", MessageRole.USER, long, MessageStatus.COMPLETE, 2, 3),
            ChatMessage("m3", "old", MessageRole.ASSISTANT, long, MessageStatus.COMPLETE, 3, 4),
        )
        val fixture = buildViewModel(repository = repository)
        advanceUntilIdle()

        fixture.viewModel.openConversation("old")
        advanceUntilIdle()
        fixture.viewModel.onInputChange("next")
        fixture.viewModel.send()
        advanceUntilIdle()

        assertTrue(fixture.viewModel.uiState.value.droppedContextMessages > 0)
    }

    @Test
    fun `renaming a note updates the active title`() = runTest {
        val repository = FakeChatRepository()
        repository.seedSession(ChatSession("old", "Old chat", 1, 1))
        val fixture = buildViewModel(repository = repository)
        advanceUntilIdle()

        fixture.viewModel.renameConversation("old", "Renamed note")
        advanceUntilIdle()

        assertEquals("Renamed note", fixture.viewModel.uiState.value.title)
    }

    @Test
    fun `editing a note changes the replayed context for the next answer`() = runTest {
        val repository = FakeChatRepository()
        repository.seedSession(ChatSession("old", "Old chat", 1, 1))
        repository.seed(
            ChatMessage("m0", "old", MessageRole.USER, "old note", MessageStatus.COMPLETE, 0, 1),
            ChatMessage("m1", "old", MessageRole.ASSISTANT, "old answer", MessageStatus.COMPLETE, 1, 2),
        )
        val fixture = buildViewModel(repository = repository)
        advanceUntilIdle()

        fixture.viewModel.editMessage("m0", "old", "updated note")
        advanceUntilIdle()
        fixture.viewModel.onInputChange("next")
        fixture.viewModel.send()
        advanceUntilIdle()

        assertEquals(
            ConversationTurn(TurnRole.USER, "updated note"),
            fixture.engine.lastReplayedHistory.first(),
        )
    }

    @Test
    fun `deleting a note removes it from the current conversation`() = runTest {
        val repository = FakeChatRepository()
        repository.seedSession(ChatSession("old", "Old chat", 1, 1))
        repository.seed(
            ChatMessage("m0", "old", MessageRole.USER, "note", MessageStatus.COMPLETE, 0, 1),
        )
        val fixture = buildViewModel(repository = repository)
        advanceUntilIdle()

        fixture.viewModel.deleteMessage("m0", "old")
        advanceUntilIdle()

        assertTrue(fixture.viewModel.uiState.value.messages.isEmpty())
        assertTrue(repository.snapshot().isEmpty())
    }

    // ----------------------------------------------------- model provisioning

    @Test
    fun `removing the model unloads the engine and returns to the install state`() = runTest {
        val fixture = buildViewModel()
        advanceUntilIdle()

        fixture.viewModel.removeModel()
        advanceUntilIdle()

        assertEquals(1, fixture.engine.closeCallCount)
        assertEquals(1, fixture.installer.removeCount)
        assertEquals(TopLevelState.MODEL_MISSING, fixture.viewModel.uiState.value.topLevelState)
    }

    @Test
    fun `allowing metered data immediately restarts the download`() = runTest {
        val installer = FakeModelInstaller(ModelInstallState.NotInstalled(ModelCatalog.default))
        val settings = FakeSettingsRepository()
        val fixture = buildViewModel(installer = installer, settings = settings)
        advanceUntilIdle()

        fixture.viewModel.onRecoveryAction(RecoveryAction.ALLOW_METERED)
        advanceUntilIdle()

        assertEquals(1, installer.downloadCount)
        assertTrue(settings.settings.first().allowMeteredDownload)
    }
}
