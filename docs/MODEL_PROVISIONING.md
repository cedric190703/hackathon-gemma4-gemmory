# Model provisioning

The model is **never** packaged in the APK. It is provisioned at runtime behind the
[`ModelInstaller`](../app/src/main/java/com/gemmory/modelinstall/ModelInstaller.kt)
interface, which currently has two sources: HTTP download and Storage Access
Framework import.

## Configuration

All model constants live in one place,
[`ModelCatalog`](../app/src/main/java/com/gemmory/modelinstall/ModelDescriptor.kt):

```kotlin
ModelDescriptor(
    id        = "gemma-4-e2b-it",
    fileName  = "gemma-4-E2B-it.litertlm",
    sizeBytes = 2_588_147_712L,
    sha256    = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
    downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
    …
)
```

The **URL is user-configurable** in Settings (useful for an internal mirror). The
**size and digest are not**: whatever URL you point at, the artefact must still match
byte for byte. There are no secrets, tokens or developer machine paths anywhere in
the configuration.

## On-disk layout

```
<filesDir>/models/gemma-4-E2B-it.litertlm            installed, verified
<filesDir>/models/tmp/gemma-4-E2B-it.litertlm.part   in-flight download or import
```

Both are app-private and excluded from backup and device transfer.

## Pipeline

```
NotInstalled
   │  startDownload()                       startImport(uri)
   ▼                                            ▼
 connectivity check                       storage check
 metered-network consent                        │
 storage check                                  │
   ▼                                            ▼
Downloading ──resume via Range──▶          Importing
   │                                            │
   └──────────────▶  Verifying  ◀───────────────┘
                        │
        size ✗ or sha ✗ │ size ✓ and sha ✓
                        ▼            ▼
                     Failed      atomic move
                  (.part deleted)     ▼
                                  Installed
```

### Storage check

Before any byte is transferred the installer requires
`sizeBytes + 256 MB` of usable space, minus whatever a resumable `.part` file already
holds. Failing this yields `InsufficientStorage(required, available)` with both
numbers shown to the user.

### Metered networks

`startDownload(allowMeteredNetwork = false)` is the default. On a metered connection
the installer stops immediately with `MeteredNetworkNotAllowed`, and the UI offers an
explicit *Use mobile data* action. **2.6 GB is never spent on mobile data silently.**

### Resuming

The downloader writes into the `.part` file and sends
`Range: bytes=<already on disk>-` when one exists. A `206` response appends; a server
that ignores `Range` and answers `200` causes a clean restart from zero rather than a
corrupted file. An oversized leftover is discarded before the request is made.

A failed download **keeps** its partial file so the user can resume. A failed
*integrity check* **deletes** it, because those bytes are known to be wrong.

### Integrity

[`ModelIntegrityVerifier`](../app/src/main/java/com/gemmory/modelinstall/ModelIntegrityVerifier.kt)
checks the size first (microseconds, catches truncation) and then streams a SHA-256
digest in 1 MB blocks, reporting progress and honouring cancellation. Only after both
pass is the file moved with `Files.move(…, ATOMIC_MOVE, REPLACE_EXISTING)`, falling
back to a replace-move when the platform refuses an atomic one.

Consequently a reader can never observe a half-written model at the installed path.

### Cleanup

* Invalid artefact → `.part` deleted.
* Cancelled install → `.part` kept for resuming, state returns to `NotInstalled`.
* App start → `ModelStorage.cleanupOrphanTempFiles` removes any `.part` file that is
  not the current descriptor's, so a crash mid-install cannot leak gigabytes.
* *Remove model* → both the installed file and any `.part` are deleted and the app
  returns to `MODEL_MISSING`.
* An installed file whose size does not match the descriptor is deleted on refresh
  rather than being trusted.

### Long-running downloads

[`ModelInstallService`](../app/src/main/java/com/gemmory/modelinstall/ModelInstallService.kt)
is a `dataSync` foreground service started while the installer is busy. It does not
own the transfer — `ModelInstaller` does, on the application scope — it only keeps the
process alive and mirrors progress into a notification. Keeping the transfer out of
the service is what makes the whole pipeline testable on the JVM.

## Adding a source (for example Play Asset Delivery)

Implement `ModelInstaller`, or add a branch that fills the same `.part` file and then
calls the existing verify-and-commit step. Nothing in `inference/`, `chat/` or the
Compose layer refers to how the file arrived; they only ever see
`ModelInstallState.Installed.path`.

## Test coverage

`ModelInstallerTest` and `OkHttpModelDownloaderTest` cover: the full state sequence,
checksum rejection, size rejection, temp-file cleanup, resuming, servers that ignore
`Range`, truncated responses, HTTP errors, insufficient storage, metered refusal,
missing connectivity, import success and failure, removal, discarding a wrong-sized
installed file, and orphan cleanup.
