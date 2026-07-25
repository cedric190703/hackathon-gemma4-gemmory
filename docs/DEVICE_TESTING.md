# Physical device testing

This procedure is manual but repeatable. Run it on at least one arm64 phone before
claiming the app works.

## 0. Prepare

```bash
adb devices                      # exactly one device, authorised
adb shell getprop ro.product.cpu.abi     # must be arm64-v8a
adb shell getprop ro.build.version.release

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Record the device model and Android version — they go into
[BENCHMARKS.md](BENCHMARKS.md).

## 1. Inference spike (automated)

Push the model somewhere the test can find it, then run the spike. It loads the real
model, streams a response, cancels a second generation, resets the conversation and
disposes the engine.

```bash
adb push gemma-4-E2B-it.litertlm /sdcard/Download/

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.gemmory.inference.RealModelInferenceTest
```

Capture the benchmark line:

```bash
adb logcat -d -s GemmorySpike | grep BENCHMARK
```

It prints device, Android version, **the backend the runtime actually selected**,
which backends were attempted and why they failed, model size, initialization time,
time to first token, decode and prefill tokens per second, context tokens and the
Java heap delta. Paste it into [BENCHMARKS.md](BENCHMARKS.md).

If the model is not on the device the test **skips** rather than fails, so this same
command is safe in CI.

## 2. Remaining instrumented tests

```bash
./gradlew connectedDebugAndroidTest
```

Covers the Room DAOs and the Compose flows (send, stream, stop, model-not-installed,
download progress, initialization failure, new conversation) using the fake engine.

## 3. Manual checklist

Tick every line. Each maps to an acceptance criterion.

### Installation

- [ ] Fresh install (`adb uninstall com.gemmory.debug` first) opens on **Model missing**.
- [ ] *Download model* on Wi-Fi shows progress in GB and MB/s, and a foreground notification.
- [ ] Backgrounding the app during the download does not stop it.
- [ ] Killing the app mid-download and reopening offers to **resume**; progress is kept.
- [ ] Turning networking off mid-download produces a *download was interrupted* banner with a **Resume** action.
- [ ] On mobile data, download is refused with a *metered connection* banner until *Use mobile data* is tapped.
- [ ] With < 3 GB free, download is refused with the required and available sizes shown.
- [ ] *Import .litertlm file* installs a file picked from Downloads.
- [ ] Importing a truncated or unrelated file is **rejected**, the temp file is gone, and the message is actionable.
- [ ] Verification progress is visible for the checksum pass.
- [ ] After verification the app moves to **Model ready / loading** without a restart.

### Inference

- [ ] The model loads; the diagnostics panel shows a **selected backend** and an initialization time.
- [ ] The diagnostics panel's *Backend attempts* line matches the Settings preference.
- [ ] Sending a prompt streams the answer incrementally, not in one block.
- [ ] The UI stays responsive while generating (scrolling, typing indicator, no ANR).
- [ ] *Stop* halts generation within a second; the partial answer is marked **Stopped**.
- [ ] A new prompt can be sent immediately after stopping.
- [ ] A follow-up question demonstrates the model remembers the previous turn.
- [ ] **Ten consecutive prompts** all complete without restarting the app.
- [ ] Rotating the screen mid-generation does **not** duplicate the answer or reload the model (initialization time in the panel is unchanged).
- [ ] Backgrounding during generation and returning does not corrupt the answer.

### Persistence

- [ ] Force-stopping and reopening the app restores the conversation.
- [ ] A conversation interrupted by force-stop shows its partial answer as **Stopped**, never as a normal answer.
- [ ] *New conversation* starts empty; the old one is still in the conversations list.
- [ ] Reopening an old conversation and asking a follow-up shows the model has the earlier context.
- [ ] A very long conversation eventually shows the *older messages are no longer part of the context* notice.

### Offline and privacy

- [ ] Enable **airplane mode**. Chat still works end to end.
- [ ] `adb logcat | grep Gemmory` shows no prompt or response text.
- [ ] During a full chat session, `adb logcat -s Gemmory | grep NetworkAudit` shows no unexpected outbound request.

### Recovery

- [ ] Deleting the model file under the app (`adb shell run-as com.gemmory.debug rm files/models/*.litertlm`) then reloading gives *model file is missing* with a **Reinstall** action.
- [ ] *Remove model* returns the app to the installation state.
- [ ] Forcing `GPU_ONLY` on a device without a usable GPU delegate gives the **unsupported device** state listing the attempted backends, with no retry loop.
- [ ] Clearing app data leaves the app in a clean **Model missing** state.

### Thermals

- [ ] Note whether the phone becomes noticeably warm after ten prompts.
- [ ] Note whether tokens/second in the diagnostics panel degrades between prompt 1 and prompt 10 (thermal throttling).

Record the last two in [BENCHMARKS.md](BENCHMARKS.md).
