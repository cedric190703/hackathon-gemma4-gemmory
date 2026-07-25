package com.gemmory.modelinstall

import com.gemmory.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

sealed interface DownloadResult {
    data object Success : DownloadResult
    data class Failure(val error: ModelInstallError, val bytesOnDisk: Long) : DownloadResult
}

/** Progress callback: absolute bytes on disk, total expected, whether the transfer resumed. */
typealias DownloadProgress = suspend (downloadedBytes: Long, totalBytes: Long, resumed: Boolean) -> Unit

interface ModelDownloader {
    suspend fun download(
        descriptor: ModelDescriptor,
        target: File,
        onProgress: DownloadProgress,
    ): DownloadResult
}

/**
 * Resumable HTTP downloader.
 *
 * Writes into [target] (a `.part` file) and appends with a `Range` request when
 * a previous attempt left bytes behind. A server that ignores `Range` and
 * answers `200` restarts the transfer from zero instead of corrupting the file.
 */
class OkHttpModelDownloader(
    private val client: OkHttpClient = defaultClient(),
) : ModelDownloader {

    override suspend fun download(
        descriptor: ModelDescriptor,
        target: File,
        onProgress: DownloadProgress,
    ): DownloadResult {
        target.parentFile?.mkdirs()

        val existingBytes = if (target.isFile) target.length() else 0L
        if (existingBytes > descriptor.sizeBytes) {
            AppLog.w(TAG, "discarding oversized partial file ($existingBytes bytes)")
            target.delete()
        }
        val resumeFrom = if (target.isFile) target.length() else 0L

        val requestBuilder = Request.Builder().url(descriptor.downloadUrl)
        if (resumeFrom > 0) requestBuilder.header("Range", "bytes=$resumeFrom-")

        val call = client.newCall(requestBuilder.build())
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }

        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return@use DownloadResult.Failure(
                        ModelInstallError.HttpError(response.code),
                        target.lengthOrZero(),
                    )
                }

                val appending = response.code == HTTP_PARTIAL && resumeFrom > 0
                if (!appending && resumeFrom > 0) {
                    AppLog.i(TAG, "server ignored Range; restarting download from 0")
                    target.delete()
                }

                val startOffset = if (appending) resumeFrom else 0L
                val body = response.body

                var written = startOffset
                onProgress(written, descriptor.sizeBytes, appending)

                RandomAccessFile(target, "rw").use { output ->
                    output.setLength(startOffset)
                    output.seek(startOffset)
                    val buffer = ByteArray(BUFFER_BYTES)
                    var sinceReport = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            sinceReport += read
                            if (sinceReport >= PROGRESS_INTERVAL_BYTES) {
                                sinceReport = 0
                                onProgress(written, descriptor.sizeBytes, appending)
                            }
                        }
                    }
                    output.fd.sync()
                }
                onProgress(written, descriptor.sizeBytes, appending)

                if (written != descriptor.sizeBytes) {
                    DownloadResult.Failure(
                        ModelInstallError.SizeMismatch(descriptor.sizeBytes, written),
                        written,
                    )
                } else {
                    DownloadResult.Success
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (host: UnknownHostException) {
            DownloadResult.Failure(ModelInstallError.NoNetwork, target.lengthOrZero())
        } catch (io: IOException) {
            DownloadResult.Failure(
                ModelInstallError.DownloadInterrupted(io.message?.take(160) ?: "connection lost"),
                target.lengthOrZero(),
            )
        } finally {
            cancellation.dispose()
        }
    }

    private fun File.lengthOrZero(): Long = if (isFile) length() else 0L

    companion object {
        private const val TAG = "ModelDownloader"
        private const val HTTP_PARTIAL = 206
        private const val BUFFER_BYTES = 1 shl 16
        private const val PROGRESS_INTERVAL_BYTES = 2L shl 20

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
