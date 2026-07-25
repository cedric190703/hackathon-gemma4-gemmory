package com.gemmory.modelinstall

/** Actionable installation failures. Raw stack traces never reach the UI. */
sealed interface ModelInstallError {
    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) :
        ModelInstallError

    data class ChecksumMismatch(val expected: String, val actual: String) : ModelInstallError

    data class SizeMismatch(val expectedBytes: Long, val actualBytes: Long) : ModelInstallError

    data object NoNetwork : ModelInstallError

    /** Refuses to spend gigabytes of mobile data without explicit consent. */
    data object MeteredNetworkNotAllowed : ModelInstallError

    data class DownloadInterrupted(val reason: String) : ModelInstallError

    data class HttpError(val code: Int) : ModelInstallError

    data class ImportFailed(val reason: String) : ModelInstallError

    data class Unknown(val reason: String) : ModelInstallError
}

/** Explicit installation state machine. */
sealed interface ModelInstallState {

    data class NotInstalled(val descriptor: ModelDescriptor) : ModelInstallState

    data class Importing(
        val descriptor: ModelDescriptor,
        val copiedBytes: Long,
        val totalBytes: Long?,
    ) : ModelInstallState

    data class Downloading(
        val descriptor: ModelDescriptor,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
        val resumed: Boolean,
    ) : ModelInstallState {
        val fraction: Float
            get() = if (totalBytes <= 0) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    data class Verifying(
        val descriptor: ModelDescriptor,
        val hashedBytes: Long,
        val totalBytes: Long,
    ) : ModelInstallState {
        val fraction: Float
            get() = if (totalBytes <= 0) 0f else (hashedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    data class Installed(
        val descriptor: ModelDescriptor,
        val path: String,
        val sizeBytes: Long,
    ) : ModelInstallState

    data class Failed(
        val descriptor: ModelDescriptor,
        val error: ModelInstallError,
        /** Bytes kept on disk that a retry can resume from, if any. */
        val resumableBytes: Long = 0,
    ) : ModelInstallState
}

val ModelInstallState.isBusy: Boolean
    get() = this is ModelInstallState.Importing ||
        this is ModelInstallState.Downloading ||
        this is ModelInstallState.Verifying
