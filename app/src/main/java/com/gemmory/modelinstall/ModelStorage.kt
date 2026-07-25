package com.gemmory.modelinstall

import com.gemmory.core.filesystem.FileSystem
import java.io.File

/**
 * Owns the app-private layout for model artefacts.
 *
 * ```
 * <filesDir>/models/<fileName>          installed, verified model
 * <filesDir>/models/tmp/<fileName>.part in-flight download / import
 * ```
 */
class ModelStorage(
    private val filesDir: File,
    private val fileSystem: FileSystem,
) {
    val modelsDir: File get() = File(filesDir, MODELS_DIR)
    private val tempDir: File get() = File(modelsDir, TEMP_DIR)

    fun installedFile(descriptor: ModelDescriptor): File = File(modelsDir, descriptor.fileName)

    fun tempFile(descriptor: ModelDescriptor): File = File(tempDir, descriptor.fileName + PART_SUFFIX)

    fun prepareDirectories() {
        fileSystem.ensureDirectory(modelsDir)
        fileSystem.ensureDirectory(tempDir)
    }

    fun isInstalled(descriptor: ModelDescriptor): Boolean {
        val file = installedFile(descriptor)
        return fileSystem.exists(file) && fileSystem.sizeBytes(file) == descriptor.sizeBytes
    }

    fun usableSpaceBytes(): Long = fileSystem.usableSpaceBytes(modelsDir)

    fun partialBytes(descriptor: ModelDescriptor): Long = fileSystem.sizeBytes(tempFile(descriptor))

    fun deleteTemp(descriptor: ModelDescriptor): Boolean = fileSystem.delete(tempFile(descriptor))

    fun deleteInstalled(descriptor: ModelDescriptor): Boolean =
        fileSystem.delete(installedFile(descriptor))

    /** Publishes a verified temp file as the installed model. */
    fun commit(descriptor: ModelDescriptor) {
        fileSystem.atomicMove(tempFile(descriptor), installedFile(descriptor))
    }

    /**
     * Removes any orphaned `.part` files that do not belong to [keep].
     * Called at startup so a crash mid-install cannot leak gigabytes.
     */
    fun cleanupOrphanTempFiles(keep: ModelDescriptor) {
        val expected = tempFile(keep).name
        tempDir.listFiles()?.forEach { file ->
            if (file.name != expected) fileSystem.delete(file)
        }
    }

    private companion object {
        const val MODELS_DIR = "models"
        const val TEMP_DIR = "tmp"
        const val PART_SUFFIX = ".part"
    }
}
