package com.gemmory.modelinstall

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ModelIntegrityVerifierTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val verifier = ModelIntegrityVerifier()

    private fun descriptorFor(content: ByteArray, sha: String = content.sha256()) = ModelDescriptor(
        id = "test",
        displayName = "Test model",
        fileName = "test.litertlm",
        sizeBytes = content.size.toLong(),
        sha256 = sha,
        downloadUrl = "https://example.invalid/test.litertlm",
        licenseName = "Test",
        licenseUrl = "https://example.invalid/license",
        sourceUrl = "https://example.invalid",
    )

    private fun fileWith(content: ByteArray): File =
        temporaryFolder.newFile("candidate.bin").apply { writeBytes(content) }

    @Test
    fun `accepts a file with the expected size and digest`() = runTest {
        val content = "gemma-4-e2b".repeat(1000).toByteArray()
        val result = verifier.verify(fileWith(content), descriptorFor(content))

        assertEquals(IntegrityResult.Valid, result)
    }

    @Test
    fun `rejects a file whose digest does not match`() = runTest {
        val content = ByteArray(2048) { 1 }
        val descriptor = descriptorFor(content, sha = "0".repeat(64))

        val result = verifier.verify(fileWith(content), descriptor)

        val error = (result as IntegrityResult.Invalid).error
        assertTrue(error is ModelInstallError.ChecksumMismatch)
        assertEquals("0".repeat(64), (error as ModelInstallError.ChecksumMismatch).expected)
    }

    @Test
    fun `rejects a truncated file before hashing it`() = runTest {
        val content = ByteArray(1024) { 7 }
        val descriptor = descriptorFor(content).copy(sizeBytes = content.size + 512L)

        val result = verifier.verify(fileWith(content), descriptor)

        val error = (result as IntegrityResult.Invalid).error
        assertTrue(error is ModelInstallError.SizeMismatch)
        assertEquals(1024L, (error as ModelInstallError.SizeMismatch).actualBytes)
    }

    @Test
    fun `rejects a missing file`() = runTest {
        val content = ByteArray(16) { 3 }
        val missing = File(temporaryFolder.root, "does-not-exist.bin")

        val result = verifier.verify(missing, descriptorFor(content))

        assertTrue((result as IntegrityResult.Invalid).error is ModelInstallError.SizeMismatch)
    }

    @Test
    fun `reports hashing progress up to the total size`() = runTest {
        val content = ByteArray(1 shl 20) { it.toByte() }
        var lastHashed = 0L
        var lastTotal = 0L

        verifier.verify(fileWith(content), descriptorFor(content)) { hashed, total ->
            lastHashed = hashed
            lastTotal = total
        }

        assertEquals(content.size.toLong(), lastHashed)
        assertEquals(content.size.toLong(), lastTotal)
    }
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
