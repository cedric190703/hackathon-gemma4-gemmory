# Known limitations

## Devices and backends

* **arm64-v8a only in practice.** `x86_64` is packaged so the app installs on
  emulators, but LiteRT-LM 0.14.0 ships native libraries for those two ABIs only, and
  emulator inference is CPU-bound and very slow. `armeabi-v7a` is not supported.
* **Memory.** Gemma 4 E2B is a 2.58 GB artefact. Devices with 6 GB RAM or less may
  fail to initialize, or be killed by the low-memory killer while another heavy app is
  in the foreground. The app requests `largeHeap` and reports
  `InferenceError.OutOfMemory` with **no automatic retry**.
* **GPU backend availability varies.** The manifest declares `libOpenCL.so` and
  `libvndksupport.so` as optional native libraries, but some vendors ship no usable
  OpenCL driver. With the default `AUTO` preference the app falls back to CPU and the
  diagnostics panel shows both the attempt and the failure reason.
* **NPU is opt-in and usually unavailable.** `NPU_FIRST` passes
  `applicationInfo.nativeLibraryDir` to LiteRT-LM, but this build bundles **no vendor
  NPU libraries**, so the NPU attempt will normally fail and fall through to GPU/CPU.
  Qualcomm and Google Tensor also require *compiled* model variants
  (`gemma-4-E2B-it_qualcomm_sm8750.litertlm`, `..._Google_Tensor_G5.litertlm`) which
  this build does not install. Treat `NPU_FIRST` as a hook, not a working feature.
* **No hardware-acceleration claims without evidence.** The only source of truth is
  `EngineDiagnostics.selectedBackend`, populated by whichever backend the runtime
  actually accepted.

## Functional scope

* Text only. Gemma 4 E2B supports images and audio and LiteRT-LM exposes
  `visionBackend` / `audioBackend`, but multimodal input is deliberately out of scope.
* No function calling, even though LiteRT-LM supports tools.
* Answers are rendered as plain text. No Markdown, code highlighting or LaTeX.
* No conversation search, export, or renaming.
* Single conversation loaded in native memory at a time. Switching chats rebuilds the
  native conversation from persisted history, which costs a prefill pass.

## Context and history

* Token counts for trimming are **estimated** at ~3.5 characters per token, because
  the tokenizer is not reachable before the engine is loaded. The estimate
  over-counts, so the real context stays inside the budget.
* Only `COMPLETE` messages are replayed. A cancelled or failed answer is visible in
  the transcript but is never fed back to the model, so the model's view of an
  interrupted conversation differs from what is on screen.
* The context budget is 2560 tokens of a 4096-token window. Long conversations lose
  their oldest turns; the UI says so, but the model genuinely cannot see them.
* LiteRT-LM 0.14.0 exposes no documented, stable native session serialization, so
  reopening a chat always replays history rather than restoring a saved KV cache.

## Installation

* Resuming relies on the server honouring `Range`. Hugging Face does; an arbitrary
  mirror might not, in which case the transfer restarts from zero.
* Gated or private model repositories are not supported: no `Authorization` header is
  ever sent, and no tokens are stored.
* A model download interrupted by the process being killed resumes only on the next
  explicit user action; there is no automatic background retry.

## Testing

* Benchmarks and the device checklist in [DEVICE_TESTING.md](DEVICE_TESTING.md) are
  **not yet executed** — no phone was available. See [BENCHMARKS.md](BENCHMARKS.md).
* CI runs unit tests, lint and `assembleDebug` only. Instrumented tests require a
  device or emulator and are not run in CI.
* `RealModelInferenceTest` skips itself when the model is absent, so a green CI run
  says nothing about real inference.

## Platform behaviour

* `POST_NOTIFICATIONS` is declared but never requested at runtime. On Android 13+ the
  download foreground service still runs; its notification is simply not shown until
  the user grants notifications from system settings.
* Process death during generation is handled by demoting the row to `CANCELLED` at
  next start. Generation itself does not survive process death, by design.
