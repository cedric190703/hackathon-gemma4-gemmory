package com.gemmory.chat.presentation

import com.gemmory.inference.InferenceError
import com.gemmory.modelinstall.ModelInstallError

/** Maps typed domain errors to actionable, user-facing text. Never a stack trace. */
object ErrorPresentation {

    fun forInstall(error: ModelInstallError): ErrorBanner = when (error) {
        is ModelInstallError.InsufficientStorage -> ErrorBanner(
            message = "Not enough free space. ${error.requiredBytes.toGb()} GB are needed and " +
                "${error.availableBytes.toGb()} GB are available.",
            actionLabel = "Manage storage",
            action = RecoveryAction.FREE_SPACE,
        )

        is ModelInstallError.ChecksumMismatch -> ErrorBanner(
            message = "The model file failed its integrity check and was deleted. " +
                "Download it again or import a known-good copy.",
            actionLabel = "Try again",
            action = RecoveryAction.RETRY_DOWNLOAD,
        )

        is ModelInstallError.SizeMismatch -> ErrorBanner(
            message = "The model file is incomplete (${error.actualBytes.toGb()} GB of " +
                "${error.expectedBytes.toGb()} GB).",
            actionLabel = "Resume",
            action = RecoveryAction.RETRY_DOWNLOAD,
        )

        ModelInstallError.NoNetwork -> ErrorBanner(
            message = "No internet connection. Connect to Wi-Fi to download the model, " +
                "or import a .litertlm file you already have.",
            actionLabel = "Import file",
            action = RecoveryAction.IMPORT_FILE,
        )

        ModelInstallError.MeteredNetworkNotAllowed -> ErrorBanner(
            message = "This download is about 2.6 GB and you are on a metered connection. " +
                "Connect to Wi-Fi, or allow mobile data in Settings.",
            actionLabel = "Use mobile data",
            action = RecoveryAction.ALLOW_METERED,
        )

        is ModelInstallError.DownloadInterrupted -> ErrorBanner(
            message = "The download was interrupted. Progress was kept and can be resumed.",
            actionLabel = "Resume",
            action = RecoveryAction.RETRY_DOWNLOAD,
        )

        is ModelInstallError.HttpError -> ErrorBanner(
            message = "The download server returned an error (HTTP ${error.code}). " +
                "Check the model URL in Settings.",
            actionLabel = "Try again",
            action = RecoveryAction.RETRY_DOWNLOAD,
        )

        is ModelInstallError.ImportFailed -> ErrorBanner(
            message = "The file could not be imported: ${error.reason}",
            actionLabel = "Pick another file",
            action = RecoveryAction.IMPORT_FILE,
        )

        is ModelInstallError.Unknown -> ErrorBanner(
            message = "Model installation failed: ${error.reason}",
            actionLabel = "Try again",
            action = RecoveryAction.RETRY_DOWNLOAD,
        )
    }

    fun forInference(error: InferenceError): ErrorBanner = when (error) {
        is InferenceError.ModelFileMissing -> ErrorBanner(
            message = "The installed model file is missing. Reinstall it to continue.",
            actionLabel = "Reinstall",
            action = RecoveryAction.REINSTALL_MODEL,
        )

        is InferenceError.UnsupportedDevice -> ErrorBanner(
            message = "This device cannot run Gemma 4 E2B. Tried " +
                "${error.attemptedBackends.joinToString(", ")} without success.",
            actionLabel = null,
            action = RecoveryAction.NONE,
        )

        is InferenceError.InitializationFailed -> ErrorBanner(
            message = "The model could not be loaded. Try again, or switch the backend to CPU " +
                "in Settings.",
            actionLabel = "Try again",
            action = RecoveryAction.RETRY_LOAD,
        )

        is InferenceError.OutOfMemory -> ErrorBanner(
            // Deliberately offers no automatic retry: repeated OOM would loop.
            message = "The device ran out of memory. Close other apps, then reload the model " +
                "from Settings.",
            actionLabel = null,
            action = RecoveryAction.NONE,
        )

        is InferenceError.GenerationFailed -> ErrorBanner(
            message = "The answer could not be generated. You can send the prompt again.",
            actionLabel = null,
            action = RecoveryAction.NONE,
        )

        InferenceError.EngineNotReady -> ErrorBanner(
            message = "The model is not loaded yet.",
            actionLabel = "Load model",
            action = RecoveryAction.RETRY_LOAD,
        )

        InferenceError.AlreadyGenerating -> ErrorBanner(
            message = "Wait for the current answer to finish, or stop it first.",
        )
    }

    /** Short label stored next to a failed message. */
    fun shortLabel(error: InferenceError): String = when (error) {
        is InferenceError.OutOfMemory -> "Out of memory"
        is InferenceError.GenerationFailed -> "Generation failed"
        is InferenceError.UnsupportedDevice -> "Unsupported backend"
        is InferenceError.ModelFileMissing -> "Model file missing"
        is InferenceError.InitializationFailed -> "Model not loaded"
        InferenceError.EngineNotReady -> "Model not loaded"
        InferenceError.AlreadyGenerating -> "Already generating"
    }
}

private fun Long.toGb(): String = String.format(java.util.Locale.US, "%.1f", this / 1_000_000_000.0)
