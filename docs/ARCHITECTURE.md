# Architecture

One Gradle module, packages by feature, no DI framework. The graph is small enough
that a hand-written [`AppContainer`](../app/src/main/java/com/gemmory/app/AppContainer.kt)
is clearer than annotations and code generation.

```
app/            GemmoryApp, MainActivity, GemmoryNavHost, AppContainer
core/           dispatchers, filesystem, logging
inference/      LocalLlmEngine, LiteRtLlmEngine, EngineController, config, events, errors
modelinstall/   descriptor, storage, downloader, importer, verifier, installer, service
chat/
  data/         Room database, DAOs, entities
  domain/       ChatMessage, ChatSession, ChatRepository, ContextPolicy
  presentation/ ChatViewModel, ChatUiState, ChatScreen, components/
settings/       SettingsRepository, SettingsScreen
privacy/        NetworkAccessAuditor
ui/theme/       Material 3 theme
```

## Boundaries

Interfaces exist only where they buy something real:

| Interface | Why |
|---|---|
| `LocalLlmEngine` | Keeps every LiteRT-LM type out of the ViewModel and Compose layers, and enables `FakeLlmEngine` |
| `ModelInstaller` | Lets a second provisioning source (Play Asset Delivery) be added without touching inference or UI |
| `ModelDownloader` / `ModelFileImporter` | Transport seams for tests |
| `ChatRepository` | Room stays behind it; the ViewModel is tested in-memory |
| `SettingsRepository` | DataStore stays out of unit tests |
| `FileSystem` / `NetworkStatusProvider` | Framework seams for JVM tests |

`ChatViewModel` imports nothing from `com.google.ai.edge.litertlm`. That is
enforced by the fact that the ViewModel unit tests run on the JVM with no LiteRT-LM
classes loaded at all.

## Inference lifecycle

```
Idle ──initialize()──▶ Loading ──▶ Ready(backend, path)
                          │              │
                          │              ├── generate() ──▶ Generating ──▶ Ready
                          │              └── close() ─────▶ Closed
                          └── Failed(InferenceError)
```

Rules enforced by [`LiteRtLlmEngine`](../app/src/main/java/com/gemmory/inference/LiteRtLlmEngine.kt):

* **Never on the main thread.** Every native call runs on `AppDispatchers.inference`,
  a single dedicated thread. Pinning JNI to one thread removes a class of lifetime bugs.
* **At most one engine.** `initialize()` is a no-op if an engine already exists, and
  it is guarded by `lifecycleLock`.
* **At most one generation.** `generationLock.tryLock()` fails fast with
  `InferenceError.AlreadyGenerating` instead of queueing.
* **At most one native conversation.** Switching chats disposes the previous
  `Conversation`, so obsolete KV caches cannot accumulate in native memory.
* **Cooperative cancellation.** `cancel()` calls `Conversation.cancelProcess()`;
  collector-side cancellation does the same from a `NonCancellable` block.
* **Deterministic disposal.** `close()` closes the conversation, then the engine.
* **No reload per message.** The engine lives in `EngineController`, owned by the
  application scope, so rotation and back-stack changes cannot cancel a load or
  trigger a second one.
* **Typed errors only.** Native throwables are reduced to a one-line, non-sensitive
  reason and mapped to `InferenceError`. Stack traces never reach the UI.

### Backend selection and fallback

`BackendPreference` expands to an ordered chain, tried in sequence:

| Preference | Chain |
|---|---|
| `AUTO` (default) | GPU → CPU |
| `GPU_ONLY` | GPU |
| `CPU_ONLY` | CPU |
| `NPU_FIRST` | NPU → GPU → CPU |

Whatever the runtime actually accepted is stored in `EngineDiagnostics.selectedBackend`
together with the failure reason of each rejected backend, and shown in the debug
diagnostics panel. **No acceleration is claimed anywhere without that runtime
evidence.** `NPU_FIRST` requires vendor NPU libraries bundled in the APK; without
them the NPU attempt fails and the chain falls through, which is visible in the panel.

### Generation events

```kotlin
sealed interface GenerationEvent {
    data object Started
    data class Token(val text: String)          // incremental chunk, not always one token
    data class Metrics(timeToFirstTokenMs, tokensPerSecond, …)
    data object Completed
    data object Cancelled
    data class Failed(val error: InferenceError)
}
```

`Metrics` uses LiteRT-LM's `BenchmarkInfo` when `ExperimentalFlags.enableBenchmark`
is on (debug builds only). In release builds throughput is a documented estimate
derived from characters produced.

## Conversation handling and the context policy

Persisted per message: id, conversation id, role, content, status, order index,
timestamp and an optional short error label.

Statuses are `PENDING`, `GENERATING`, `COMPLETE`, `CANCELLED`, `FAILED`.
**A partially generated answer is never stored as `COMPLETE`.** During streaming the
row is checkpointed every two seconds while still marked `GENERATING`, and any row
left in a non-terminal state by a process death is demoted to `CANCELLED` at startup.

When a conversation is opened, or after the engine is reloaded, the native
conversation is rebuilt once from persisted history through
[`ContextPolicy`](../app/src/main/java/com/gemmory/chat/domain/ContextPolicy.kt):

1. The system prompt is owned by the engine and is never trimmed.
2. Only `COMPLETE` messages are replayed.
3. The most recent messages are kept, walking backwards until the estimated token
   cost exceeds `contextBudgetTokens` (2560 of a 4096 window, leaving room to answer).
4. A leading assistant turn is dropped, so replay starts on a user turn.
5. A trailing user turn is dropped, so replay ends on an answered turn.
6. Persisted history therefore cannot produce unbounded runtime context.

When messages are dropped, the chat shows a notice above the history.

Token counts are estimated at ~3.5 characters per token because the tokenizer is not
available before the engine is loaded. The estimate deliberately over-counts.

Native session serialization was **not** used: LiteRT-LM 0.14.0 exposes no documented,
stable API for it, and depending on undocumented behaviour was out of scope.

## Streaming without recomposition storms

`ChatViewModel` exposes streamed text as a **separate** `StateFlow<String>` from
`uiState`, updated at most every 50 ms. `MessageBubble` receives a `() -> String`
lambda rather than a value, so a stream update invalidates only that bubble's text.
Auto-scroll only engages while the user is already near the bottom of the list.

## Top-level UI states

`MODEL_MISSING`, `MODEL_IMPORTING`, `MODEL_DOWNLOADING`, `MODEL_VERIFYING`,
`MODEL_READY_UNLOADED`, `MODEL_LOADING`, `CHAT_READY`, `GENERATING`,
`RECOVERABLE_ERROR`, `UNSUPPORTED_DEVICE`.

Derived in one place, `ChatViewModel.resolveTopLevelState`, from installer state,
engine state and whether a generation is in flight. Input is enabled only in
`CHAT_READY`.

Every error is mapped by `ErrorPresentation` to plain text plus a `RecoveryAction`.
Out-of-memory and unsupported-device deliberately expose **no** retry action, so an
OOM can never drive an automatic retry loop.
