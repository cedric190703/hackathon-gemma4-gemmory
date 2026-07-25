# Gemmory — fully local Gemma 4 E2B chat for Android

Gemmory is a native Android chat app that runs **Gemma 4 E2B entirely on the device**
with [LiteRT-LM](https://ai.google.dev/edge/litert-lm). After the model is installed
there is no network access at all: prompts, conversations and generated tokens never
leave the phone, and the app works in airplane mode.

There is no backend, no cloud inference and no hidden fallback API.

---

## What it does

* Install the model by **downloading** it from a configurable URL or **importing**
  a `.litertlm` file through the Storage Access Framework.
* Verify the exact file size and SHA-256 digest before accepting the model.
* Load Gemma 4 E2B once, off the main thread, and keep it loaded.
* Multi-turn chat with **incremental token streaming**.
* **Stop** a running generation and immediately start another.
* Persist conversations in Room, in app-private storage.
* Explicit, actionable states for every failure mode.
* Debug-only diagnostics panel with the backend actually selected at runtime.

Deliberately **out of scope** for this version: images, audio, function calling,
accounts, cloud sync, RAG, voice input, and rich Markdown rendering.

---

## Requirements

| | |
|---|---|
| Android Studio | Otter (2025.2.x) or newer |
| JDK | 17+ (Android Studio's bundled JBR 21 works) |
| Android SDK | compile/target SDK 36, build-tools 36.0.0 |
| Device | **arm64-v8a**, Android 12 (API 31) or newer, ≥ 8 GB RAM recommended |
| Free storage | ≈ 2.9 GB (2.58 GB model + temporary copy + margin) |

`minSdk` is 31. LiteRT-LM itself declares `minSdkVersion 23`, but the GPU backend
and the memory headroom Gemma 4 E2B needs make anything older impractical.

The `x86_64` ABI is included so the app installs on emulators, but only CPU
inference is realistic there and it is extremely slow.

---

## Getting the model

The app expects `gemma-4-E2B-it.litertlm` from the LiteRT community repository:

<https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm>

| | |
|---|---|
| File | `gemma-4-E2B-it.litertlm` |
| Size | `2 588 147 712` bytes |
| SHA-256 | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |

These values live in one place, [`ModelCatalog`](app/src/main/java/com/gemmory/modelinstall/ModelDescriptor.kt),
and are enforced on device.

You have three options.

**1. Download inside the app (simplest).**
Launch the app and tap *Download model*. Wi-Fi is required unless you explicitly
allow mobile data in Settings. The download resumes if it is interrupted.

**2. Import a file you already have.**
Tap *Import .litertlm file* and pick the file. Any copy is accepted as long as its
size and digest match.

**3. Fetch it on your machine, then import or push it.**

```bash
# Requires a Hugging Face account (the Gemma licence must be accepted once).
huggingface-cli download litert-community/gemma-4-E2B-it-litert-lm \
    gemma-4-E2B-it.litertlm --local-dir ./models

shasum -a 256 ./models/gemma-4-E2B-it.litertlm
# 181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c

adb push ./models/gemma-4-E2B-it.litertlm /sdcard/Download/
```

Then import it from *Download* inside the app.

> **Model weights are never committed to this repository**, and `*.litertlm` is
> in `.gitignore`. See [docs/LICENSES.md](docs/LICENSES.md) for licence and
> attribution.

---

## Build, install, run

```bash
git clone <this repository>
cd hackathon-gemma4-gemmory

# Point Gradle at your SDK (only needed if ANDROID_HOME is unset).
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. It contains no model
weights; it is a few tens of megabytes.

For a release build, configure your own signing config and run:

```bash
./gradlew assembleRelease
```

---

## Testing

```bash
./gradlew test          # JVM unit tests, no model required
./gradlew lint          # Android Lint
./gradlew assembleDebug # packaging
```

Instrumented tests need a connected device or emulator:

```bash
./gradlew connectedDebugAndroidTest
```

The real-model spike (`RealModelInferenceTest`) **skips itself automatically**
unless the model is present on the device, so normal CI never needs the 2.5 GB
artefact. See [docs/DEVICE_TESTING.md](docs/DEVICE_TESTING.md) for the physical
device procedure and the manual checklist.

---

## Privacy

* Inference is 100 % local. Verify it yourself: install the model, enable airplane
  mode, and keep chatting.
* The **only** network access in the app is downloading the model from the URL shown
  in Settings. That is why `INTERNET` is declared in the manifest.
* No analytics SDKs, no crash reporters, no telemetry.
* Conversation contents are never written to logcat. Only states, sizes, durations
  and error classes are logged, and only in debug builds.
* In debug builds, `NetworkAccessAuditor` logs a loud warning for any outbound
  OkHttp request to an unexpected host.
* Conversations live in app-private storage and are excluded from cloud backup and
  device-to-device transfer.

---

## Documentation

| Document | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Module layout, inference lifecycle, context policy |
| [docs/MODEL_PROVISIONING.md](docs/MODEL_PROVISIONING.md) | Install pipeline, integrity, resuming, adding sources |
| [docs/DEVICE_TESTING.md](docs/DEVICE_TESTING.md) | Physical device checklist and spike procedure |
| [docs/BENCHMARKS.md](docs/BENCHMARKS.md) | Measured results and how to reproduce them |
| [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) | Device and backend limitations |
| [docs/LICENSES.md](docs/LICENSES.md) | Model licence and third-party attribution |

---

## Project status

Everything in this repository builds, passes its test suite, and is wired to the
real LiteRT-LM runtime — there is no mocked inference path in the shipped code.

**Device verification is still outstanding**: no Android phone was available while
this was written, so [docs/BENCHMARKS.md](docs/BENCHMARKS.md) contains an empty
results table rather than invented numbers, and no claim is made here about
achieved throughput or which accelerator a given phone selects. Run
`RealModelInferenceTest` on a physical device and paste the printed `BENCHMARK`
line into that file.
