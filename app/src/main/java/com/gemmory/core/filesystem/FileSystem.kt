package com.gemmory.core.filesystem

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Small file-system seam so installation logic can be unit tested on the JVM.
 */
interface FileSystem {
    fun usableSpaceBytes(directory: File): Long
    fun exists(file: File): Boolean
    fun sizeBytes(file: File): Long
    fun delete(file: File): Boolean
    fun ensureDirectory(directory: File)

    /**
     * Moves [source] onto [target] atomically when the platform supports it,
     * falling back to a replace-move on the same volume.
     */
    @Throws(IOException::class)
    fun atomicMove(source: File, target: File)
}

class RealFileSystem : FileSystem {

    /**
     * Deliberately uses [File.usableSpace] rather than
     * `StorageManager.getAllocatableBytes`, which counts clearable caches the
     * system *could* reclaim. For a 2.6 GB download the conservative number is
     * the honest one to show the user before starting the transfer.
     */
    @Suppress("UsableSpace")
    override fun usableSpaceBytes(directory: File): Long {
        ensureDirectory(directory)
        return directory.usableSpace
    }

    override fun exists(file: File): Boolean = file.exists()

    override fun sizeBytes(file: File): Long = if (file.exists()) file.length() else 0L

    override fun delete(file: File): Boolean = file.exists() && file.delete()

    override fun ensureDirectory(directory: File) {
        if (!directory.exists()) directory.mkdirs()
    }

    override fun atomicMove(source: File, target: File) {
        ensureDirectory(target.parentFile ?: error("target has no parent: $target"))
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
