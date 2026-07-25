package com.gemmory.modelinstall

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

/** Result of validating a candidate model file. */
sealed interface IntegrityResult {
    data object Valid : IntegrityResult
    data class Invalid(val error: ModelInstallError) : IntegrityResult
}

/**
 * Verifies size first (cheap) and then the SHA-256 digest (expensive), so an
 * obviously truncated download fails in milliseconds.
 */
class ModelIntegrityVerifier {

    suspend fun verify(
        file: File,
        descriptor: ModelDescriptor,
        onProgress: suspend (hashedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): IntegrityResult {
        if (!file.isFile) {
            return IntegrityResult.Invalid(
                ModelInstallError.SizeMismatch(descriptor.sizeBytes, 0),
            )
        }

        val actualSize = file.length()
        if (actualSize != descriptor.sizeBytes) {
            return IntegrityResult.Invalid(
                ModelInstallError.SizeMismatch(descriptor.sizeBytes, actualSize),
            )
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var hashed = 0L
        var sinceLastReport = 0L

        file.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                hashed += read
                sinceLastReport += read
                if (sinceLastReport >= PROGRESS_INTERVAL_BYTES) {
                    sinceLastReport = 0
                    onProgress(hashed, actualSize)
                }
            }
        }
        onProgress(actualSize, actualSize)

        val actualHex = digest.digest().toHex()
        return if (actualHex.equals(descriptor.sha256, ignoreCase = true)) {
            IntegrityResult.Valid
        } else {
            IntegrityResult.Invalid(ModelInstallError.ChecksumMismatch(descriptor.sha256, actualHex))
        }
    }

    private companion object {
        const val BUFFER_BYTES = 1 shl 20
        const val PROGRESS_INTERVAL_BYTES = 32L shl 20
    }
}

internal fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(HEX[value ushr 4])
        out.append(HEX[value and 0x0F])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
