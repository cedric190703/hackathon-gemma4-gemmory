package com.gemmory.modelinstall

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

sealed interface ImportResult {
    data object Success : ImportResult
    data class Failure(val error: ModelInstallError) : ImportResult
}

/** Copies a user-picked `.litertlm` file from the Storage Access Framework. */
interface ModelFileImporter {
    suspend fun import(
        uri: Uri,
        target: File,
        onProgress: suspend (copiedBytes: Long, totalBytes: Long?) -> Unit,
    ): ImportResult
}

class ContentResolverModelFileImporter(
    private val contentResolver: ContentResolver,
) : ModelFileImporter {

    override suspend fun import(
        uri: Uri,
        target: File,
        onProgress: suspend (copiedBytes: Long, totalBytes: Long?) -> Unit,
    ): ImportResult {
        target.parentFile?.mkdirs()
        target.delete()

        return try {
            val declaredSize = querySize(uri)
            val input = contentResolver.openInputStream(uri)
                ?: return ImportResult.Failure(
                    ModelInstallError.ImportFailed("the selected file could not be opened"),
                )

            var copied = 0L
            var sinceReport = 0L
            input.use { source ->
                target.outputStream().use { sink ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        copied += read
                        sinceReport += read
                        if (sinceReport >= PROGRESS_INTERVAL_BYTES) {
                            sinceReport = 0
                            onProgress(copied, declaredSize)
                        }
                    }
                    sink.flush()
                }
            }
            onProgress(copied, declaredSize)
            ImportResult.Success
        } catch (ce: CancellationException) {
            target.delete()
            throw ce
        } catch (io: IOException) {
            target.delete()
            ImportResult.Failure(
                ModelInstallError.ImportFailed(io.message?.take(160) ?: "copy failed"),
            )
        } catch (se: SecurityException) {
            target.delete()
            ImportResult.Failure(
                ModelInstallError.ImportFailed("permission to read the selected file was denied"),
            )
        }
    }

    private fun querySize(uri: Uri): Long? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                cursor.getLong(index)
            } else {
                null
            }
        }
    }.getOrNull()

    private companion object {
        const val BUFFER_BYTES = 1 shl 16
        const val PROGRESS_INTERVAL_BYTES = 8L shl 20
    }
}
