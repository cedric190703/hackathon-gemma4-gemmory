package com.gemmory.modelinstall

import android.net.Uri
import com.gemmory.core.dispatchers.AppDispatchers
import com.gemmory.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Model provisioning boundary.
 *
 * The chat and inference layers only ever see [state] and [ModelInstallState.Installed.path],
 * so an additional source (for example Play Asset Delivery) can be added by
 * implementing this interface without touching them.
 */
interface ModelInstaller {
    val state: StateFlow<ModelInstallState>

    /** Re-reads disk and republishes the correct state. Safe to call at startup. */
    suspend fun refresh()

    fun startDownload(allowMeteredNetwork: Boolean = false)

    fun startImport(uri: Uri)

    fun cancel()

    suspend fun remove()
}

/**
 * Default installer: download or SAF import into a `.part` file, verify size and
 * SHA-256, then atomically publish into app-private storage.
 */
class DefaultModelInstaller(
    private val descriptor: ModelDescriptor,
    /**
     * Resolves the (user configurable) download URL on a background dispatcher.
     * Size and digest always come from [descriptor] and are never configurable.
     */
    private val downloadUrlProvider: suspend () -> String = { descriptor.downloadUrl },
    private val storage: ModelStorage,
    private val downloader: ModelDownloader,
    private val importer: ModelFileImporter,
    private val verifier: ModelIntegrityVerifier,
    private val network: NetworkStatusProvider,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
) : ModelInstaller {

    private val _state = MutableStateFlow<ModelInstallState>(ModelInstallState.NotInstalled(descriptor))
    override val state: StateFlow<ModelInstallState> = _state.asStateFlow()

    private var job: Job? = null

    override suspend fun refresh() = withContext(dispatchers.io) {
        storage.prepareDirectories()
        storage.cleanupOrphanTempFiles(descriptor)
        val installed = storage.installedFile(descriptor)
        _state.value = if (storage.isInstalled(descriptor)) {
            ModelInstallState.Installed(descriptor, installed.absolutePath, installed.length())
        } else {
            // A wrong-sized leftover in the final location is never trusted.
            if (installed.exists()) {
                AppLog.w(TAG, "removing installed file with unexpected size")
                storage.deleteInstalled(descriptor)
            }
            ModelInstallState.NotInstalled(descriptor)
        }
    }

    override fun startDownload(allowMeteredNetwork: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch(dispatchers.io) {
            runInstall {
                if (!network.isConnected()) {
                    return@runInstall ModelInstallState.Failed(
                        descriptor,
                        ModelInstallError.NoNetwork,
                        storage.partialBytes(descriptor),
                    )
                }
                if (network.isMetered() && !allowMeteredNetwork) {
                    return@runInstall ModelInstallState.Failed(
                        descriptor,
                        ModelInstallError.MeteredNetworkNotAllowed,
                        storage.partialBytes(descriptor),
                    )
                }

                storage.prepareDirectories()
                val alreadyOnDisk = storage.partialBytes(descriptor)
                val needed = descriptor.requiredFreeSpaceBytes - alreadyOnDisk
                val available = storage.usableSpaceBytes()
                if (available < needed) {
                    return@runInstall ModelInstallState.Failed(
                        descriptor,
                        ModelInstallError.InsufficientStorage(needed, available),
                        alreadyOnDisk,
                    )
                }

                val temp = storage.tempFile(descriptor)
                var lastReportAt = 0L
                var lastBytes = alreadyOnDisk
                _state.value = ModelInstallState.Downloading(
                    descriptor = descriptor,
                    downloadedBytes = alreadyOnDisk,
                    totalBytes = descriptor.sizeBytes,
                    bytesPerSecond = 0,
                    resumed = alreadyOnDisk > 0,
                )

                val source = descriptor.copy(downloadUrl = downloadUrlProvider())
                val result = downloader.download(source, temp) { downloaded, total, resumed ->
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastReportAt
                    val speed = if (lastReportAt != 0L && elapsed > 0) {
                        (downloaded - lastBytes) * 1000 / elapsed
                    } else {
                        (_state.value as? ModelInstallState.Downloading)?.bytesPerSecond ?: 0L
                    }
                    lastReportAt = now
                    lastBytes = downloaded
                    _state.value = ModelInstallState.Downloading(
                        descriptor = descriptor,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        bytesPerSecond = speed.coerceAtLeast(0),
                        resumed = resumed,
                    )
                }

                when (result) {
                    is DownloadResult.Failure -> {
                        // Keep the partial file: a retry can resume from it.
                        ModelInstallState.Failed(descriptor, result.error, result.bytesOnDisk)
                    }

                    DownloadResult.Success -> verifyAndCommit()
                }
            }
        }
    }

    override fun startImport(uri: Uri) {
        if (job?.isActive == true) return
        job = scope.launch(dispatchers.io) {
            runInstall {
                storage.prepareDirectories()
                val available = storage.usableSpaceBytes()
                if (available < descriptor.requiredFreeSpaceBytes) {
                    return@runInstall ModelInstallState.Failed(
                        descriptor,
                        ModelInstallError.InsufficientStorage(
                            descriptor.requiredFreeSpaceBytes,
                            available,
                        ),
                    )
                }

                val temp = storage.tempFile(descriptor)
                _state.value = ModelInstallState.Importing(descriptor, 0, null)

                val result = importer.import(uri, temp) { copied, total ->
                    _state.value = ModelInstallState.Importing(descriptor, copied, total)
                }

                when (result) {
                    is ImportResult.Failure -> {
                        storage.deleteTemp(descriptor)
                        ModelInstallState.Failed(descriptor, result.error)
                    }

                    ImportResult.Success -> verifyAndCommit()
                }
            }
        }
    }

    override fun cancel() {
        job?.cancel()
    }

    override suspend fun remove() = withContext(dispatchers.io) {
        job?.cancel()
        storage.deleteTemp(descriptor)
        storage.deleteInstalled(descriptor)
        _state.value = ModelInstallState.NotInstalled(descriptor)
        AppLog.i(TAG, "model removed")
    }

    // ---------------------------------------------------------------- internals

    private suspend fun verifyAndCommit(): ModelInstallState {
        val temp = storage.tempFile(descriptor)
        _state.value = ModelInstallState.Verifying(descriptor, 0, descriptor.sizeBytes)

        val integrity = verifier.verify(temp, descriptor) { hashed, total ->
            _state.value = ModelInstallState.Verifying(descriptor, hashed, total)
        }

        return when (integrity) {
            is IntegrityResult.Invalid -> {
                // An invalid artefact is never kept, and never resumable.
                storage.deleteTemp(descriptor)
                AppLog.w(TAG, "integrity check failed: ${integrity.error::class.simpleName}")
                ModelInstallState.Failed(descriptor, integrity.error)
            }

            IntegrityResult.Valid -> {
                storage.commit(descriptor)
                val installed = storage.installedFile(descriptor)
                AppLog.i(TAG, "model installed (${installed.length()} bytes)")
                ModelInstallState.Installed(descriptor, installed.absolutePath, installed.length())
            }
        }
    }

    private suspend fun runInstall(block: suspend () -> ModelInstallState) {
        try {
            _state.value = block()
        } catch (ce: CancellationException) {
            // Cancelled installs keep their partial file so the user can resume.
            _state.value = ModelInstallState.NotInstalled(descriptor)
            throw ce
        } catch (t: Throwable) {
            AppLog.e(TAG, "install failed", t)
            _state.value = ModelInstallState.Failed(
                descriptor,
                ModelInstallError.Unknown(t.message?.take(160) ?: t::class.java.simpleName),
                storage.partialBytes(descriptor),
            )
        }
    }

    private companion object {
        const val TAG = "ModelInstaller"
    }
}
