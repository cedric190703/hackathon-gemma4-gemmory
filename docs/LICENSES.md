# Licences and attribution

## This application

Source code in this repository is provided under the terms in [`../LICENSE`](../LICENSE).

**No model weights are distributed here.** `*.litertlm`, `*.task` and `*.tflite` are
in `.gitignore`, and the APK contains no model data. The application only downloads or
imports weights on the user's device, at the user's request.

## Gemma 4 E2B

| | |
|---|---|
| Model | Gemma 4 E2B, instruction tuned |
| Publisher | Google DeepMind |
| Distribution used | `litert-community/gemma-4-E2B-it-litert-lm`, file `gemma-4-E2B-it.litertlm` |
| Source | <https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm> |
| Upstream model card | <https://ai.google.dev/gemma/docs/core/model_card_4> |
| Licence | **Gemma Terms of Use** — <https://ai.google.dev/gemma/terms> |
| Prohibited use policy | <https://ai.google.dev/gemma/prohibited_use_policy> |

Gemma is provided under the Gemma Terms of Use, **not** an OSI-approved open source
licence. By downloading or importing the model through this app you accept those
terms and the prohibited use policy. Redistribution of the weights, including
bundling them in a published APK, is subject to those terms — which is one more
reason the model is provisioned at runtime rather than packaged.

Attribution, as expected by the Gemma terms:

> Gemma is provided under and subject to the Gemma Terms of Use found at
> ai.google.dev/gemma/terms

## Runtime

| Component | Version | Licence |
|---|---|---|
| LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android`) | 0.14.0 | Apache 2.0 |
| LiteRT-LM project | — | <https://github.com/google-ai-edge/LiteRT-LM> |

The AAR bundles `THIRD_PARTY_NOTICE.txt`; it is redistributed unchanged inside the
dependency.

## Third-party libraries

| Library | Licence |
|---|---|
| AndroidX (Core, Lifecycle, Activity, Navigation, Room, DataStore) | Apache 2.0 |
| Jetpack Compose and Material 3 | Apache 2.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | Apache 2.0 |
| OkHttp / Okio | Apache 2.0 |
| Gson (transitive, via LiteRT-LM) | Apache 2.0 |
| JUnit 4 | Eclipse Public License 1.0 |
| Robolectric | MIT |
| Turbine | Apache 2.0 |

## Implementation references

The inference layer was written against the current official sources, checked at
implementation time rather than copied from blog posts:

* LiteRT-LM Kotlin getting started — <https://ai.google.dev/edge/litert-lm/android>
* LiteRT-LM API overview — <https://ai.google.dev/edge/litert-lm/api_overview>
* Google AI Edge Gallery, `LlmChatModelHelper.kt` — <https://github.com/google-ai-edge/gallery>
* Gemma 4 model page for LiteRT-LM — <https://ai.google.dev/edge/litert-lm/models/gemma-4>

The exact API surface used (`Engine`, `EngineConfig`, `Conversation.sendMessageAsync`,
`Conversation.cancelProcess`, `BenchmarkInfo`, `Backend.CPU/GPU/NPU`) was verified
directly against the published `litertlm-android:0.14.0` artefact.
