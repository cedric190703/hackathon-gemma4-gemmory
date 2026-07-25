# Benchmarks

## Status

**No measurements have been taken yet.** No physical Android device was available
while this app was written, so this file contains an empty table rather than numbers
that were not observed.

Nothing elsewhere in this repository claims a throughput figure or a selected
accelerator. The app itself only ever reports the backend the LiteRT-LM runtime
actually accepted at load time.

## How to fill this in

Follow [DEVICE_TESTING.md](DEVICE_TESTING.md) step 1, then:

```bash
adb logcat -d -s GemmorySpike | grep BENCHMARK
```

Paste one row per device.

## Measured results

| Device | Android | Backend selected | Backends attempted | Model size | Init time | Peak Java heap Δ | TTFT | Decode tok/s | Prefill tok/s | Warm / throttling |
|---|---|---|---|---|---|---|---|---|---|---|
| _(none yet)_ | | | | | | | | | | |

Notes column conventions:

* **Backend selected** — must come from `EngineDiagnostics.selectedBackend`, not from
  the preference that was requested.
* **Backends attempted** — include the failure reason of each rejected backend.
* **Peak Java heap Δ** — the spike reports the Java heap delta only. Native
  allocation dominates for a 2.5 GB model; use `adb shell dumpsys meminfo <pid>` and
  read `Native Heap` / `TOTAL PSS` for a real figure, and record it here.
* **Warm / throttling** — whether the phone became noticeably warm, and whether
  decode tok/s degraded between the first and the tenth prompt.

## Reference figures published by Google

For orientation only — these were **not** measured here. Source:
<https://ai.google.dev/edge/litert-lm/models/gemma-4>

| Platform | Backend | Prefill tok/s | Decode tok/s | TTFT | Peak CPU memory |
|---|---|---|---|---|---|
| Android (Galaxy S26 Ultra) | CPU | 557 | 47 | 1.8 s | 1733 MB |
| Android (Galaxy S26 Ultra) | GPU | 3808 | 52 | 0.3 s | 676 MB |

Model size is 2.58 GB. Expect materially worse numbers on mid-range hardware, and
expect the GPU backend to be unavailable on some devices.
