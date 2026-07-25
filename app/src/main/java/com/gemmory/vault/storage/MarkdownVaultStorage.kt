package com.gemmory.vault.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MarkdownVaultStorage(private val root: File) {

    suspend fun prepare() = withContext(Dispatchers.IO) {
        listOf(
            "concepts",
            "people",
            "projects",
            "meetings",
            "decisions",
            "resources",
            "areas",
            "archive",
            "attachments",
            ".history",
        ).forEach { File(root, it).mkdirs() }
    }

    suspend fun write(path: String, markdown: String) = withContext(Dispatchers.IO) {
        val file = resolve(path)
        file.parentFile?.mkdirs()
        file.writeText(markdown)
    }

    suspend fun read(path: String): String? = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (file.isFile) file.readText() else null
    }

    suspend fun move(from: String, to: String) = withContext(Dispatchers.IO) {
        val source = resolve(from)
        val destination = resolve(to)
        destination.parentFile?.mkdirs()
        check(source.renameTo(destination)) { "Unable to move $from to $to" }
    }

    suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        resolve(path).delete()
    }

    fun resolve(path: String): File {
        require(isSafePath(path)) { "Unsafe vault path: $path" }
        val file = File(root, path)
        val rootCanonical = root.canonicalFile
        val fileCanonical = file.canonicalFile
        require(fileCanonical.path.startsWith(rootCanonical.path + File.separator)) {
            "Path escapes vault root: $path"
        }
        return fileCanonical
    }

    companion object {
        fun isSafePath(path: String): Boolean =
            path.isNotBlank() &&
                path.endsWith(".md") &&
                !path.startsWith("/") &&
                !path.contains("\\") &&
                path.split("/").none { it.isBlank() || it == "." || it == ".." }
    }
}
