package com.gemmory.modelinstall

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Exercises the downloader against a real HTTP server so `Range` handling,
 * resuming and truncation are covered end to end.
 */
class OkHttpModelDownloaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val payload = ByteArray(64 * 1024) { (it % 253).toByte() }
    private lateinit var server: TinyHttpServer

    @Before
    fun startServer() {
        server = TinyHttpServer(payload)
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun descriptor() = ModelDescriptor(
        id = "test",
        displayName = "Test model",
        fileName = "test.litertlm",
        sizeBytes = payload.size.toLong(),
        sha256 = payload.sha256(),
        downloadUrl = server.url,
        licenseName = "Test",
        licenseUrl = "https://example.invalid/license",
        sourceUrl = "https://example.invalid",
    )

    private fun target(): File = File(temporaryFolder.newFolder("tmp"), "model.part")

    @Test
    fun `downloads the whole file and reports progress`() = runTest {
        val downloader = OkHttpModelDownloader()
        val target = target()
        var lastDownloaded = 0L

        val result = downloader.download(descriptor(), target) { downloaded, _, _ ->
            lastDownloaded = downloaded
        }

        assertEquals(DownloadResult.Success, result)
        assertArrayEquals(payload, target.readBytes())
        assertEquals(payload.size.toLong(), lastDownloaded)
    }

    @Test
    fun `resumes from an existing partial file using a range request`() = runTest {
        val target = target()
        val alreadyOnDisk = 20_000
        target.writeBytes(payload.copyOfRange(0, alreadyOnDisk))

        var sawResume = false
        val result = OkHttpModelDownloader().download(descriptor(), target) { _, _, resumed ->
            if (resumed) sawResume = true
        }

        assertEquals(DownloadResult.Success, result)
        assertTrue("the transfer should have resumed", sawResume)
        assertArrayEquals(payload, target.readBytes())
    }

    @Test
    fun `restarts from zero when the server ignores the range header`() = runTest {
        server.supportsRange = false
        val target = target()
        target.writeBytes(payload.copyOfRange(0, 20_000))

        val result = OkHttpModelDownloader().download(descriptor(), target) { _, _, _ -> }

        assertEquals(DownloadResult.Success, result)
        assertArrayEquals(payload, target.readBytes())
    }

    @Test
    fun `a truncated response is reported as a size mismatch and keeps the partial file`() = runTest {
        server.truncateAfterBytes = 10_000
        val target = target()

        val result = OkHttpModelDownloader().download(descriptor(), target) { _, _, _ -> }

        val failure = result as DownloadResult.Failure
        assertTrue(failure.error is ModelInstallError.SizeMismatch)
        assertEquals(10_000L, failure.bytesOnDisk)
        assertTrue("partial bytes must be kept for resuming", target.length() > 0)
    }

    @Test
    fun `an http error is reported with its status code`() = runTest {
        server.statusCode = 404
        val target = target()

        val result = OkHttpModelDownloader().download(descriptor(), target) { _, _, _ -> }

        assertEquals(
            ModelInstallError.HttpError(404),
            (result as DownloadResult.Failure).error,
        )
    }

    @Test
    fun `an oversized leftover file is discarded before downloading`() = runTest {
        val target = target()
        target.writeBytes(ByteArray(payload.size * 2))

        val result = OkHttpModelDownloader().download(descriptor(), target) { _, _, _ -> }

        assertEquals(DownloadResult.Success, result)
        assertArrayEquals(payload, target.readBytes())
    }
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
