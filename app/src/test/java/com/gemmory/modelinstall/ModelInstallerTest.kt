package com.gemmory.modelinstall

import com.gemmory.testing.FakeNetworkStatusProvider
import com.gemmory.testing.TestDispatchers
import com.gemmory.testing.TestFileSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

// Robolectric only for a real android.net.Uri in the import tests.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ModelInstallerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val content = ByteArray(4096) { (it % 251).toByte() }
    private val descriptor by lazy {
        ModelDescriptor(
            id = "test",
            displayName = "Test model",
            fileName = "test.litertlm",
            sizeBytes = content.size.toLong(),
            sha256 = content.sha256(),
            downloadUrl = "https://example.invalid/test.litertlm",
            licenseName = "Test",
            licenseUrl = "https://example.invalid/license",
            sourceUrl = "https://example.invalid",
        )
    }

    private lateinit var filesDir: File
    private lateinit var fileSystem: TestFileSystem
    private lateinit var storage: ModelStorage
    private val network = FakeNetworkStatusProvider()

    @Before
    fun setUp() {
        filesDir = temporaryFolder.newFolder("files")
        fileSystem = TestFileSystem()
        storage = ModelStorage(filesDir, fileSystem)
    }

    private fun TestScope.installer(
        downloader: ModelDownloader = FakeDownloader(content),
        importer: ModelFileImporter = FakeImporter(content),
    ) = DefaultModelInstaller(
        descriptor = descriptor,
        storage = storage,
        downloader = downloader,
        importer = importer,
        verifier = ModelIntegrityVerifier(),
        network = network,
        scope = this,
        dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler)),
    )

    @Test
    fun `a fresh install goes not installed then downloading then verifying then installed`() = runTest {
        val installer = installer()
        val seen = mutableListOf<String>()

        installer.refresh()
        seen += installer.state.value.name()

        installer.startDownload(allowMeteredNetwork = true)
        advanceUntilIdle()

        val finalState = installer.state.value
        assertTrue("expected Installed, got $finalState", finalState is ModelInstallState.Installed)
        assertEquals(listOf("NotInstalled"), seen)
        assertTrue(storage.installedFile(descriptor).isFile)
        assertEquals(content.size.toLong(), storage.installedFile(descriptor).length())
    }

    @Test
    fun `the temporary file is removed once the model is committed`() = runTest {
        val installer = installer()
        installer.refresh()
        installer.startDownload(allowMeteredNetwork = true)
        advanceUntilIdle()

        assertFalse(storage.tempFile(descriptor).exists())
    }

    @Test
    fun `an invalid checksum deletes the temporary file and reports a mismatch`() = runTest {
        val corrupted = ByteArray(content.size) { 9 }
        val installer = installer(downloader = FakeDownloader(corrupted))
        installer.refresh()

        installer.startDownload(allowMeteredNetwork = true)
        advanceUntilIdle()

        val state = installer.state.value as ModelInstallState.Failed
        assertTrue(state.error is ModelInstallError.ChecksumMismatch)
        assertFalse("invalid artefacts must never be kept", storage.tempFile(descriptor).exists())
        assertFalse(storage.installedFile(descriptor).exists())
    }

    @Test
    fun `an interrupted download keeps its partial file so it can be resumed`() = runTest {
        val partial = content.copyOfRange(0, 1024)
        val installer = installer(
            downloader = FailingDownloader(partial, ModelInstallError.DownloadInterrupted("reset")),
        )
        installer.refresh()

        installer.startDownload(allowMeteredNetwork = true)
        advanceUntilIdle()

        val failed = installer.state.value as ModelInstallState.Failed
        assertTrue(failed.error is ModelInstallError.DownloadInterrupted)
        assertEquals(1024L, failed.resumableBytes)
        assertEquals(1024L, storage.partialBytes(descriptor))
    }

    @Test
    fun `a resumed download completes and installs the model`() = runTest {
        storage.prepareDirectories()
        storage.tempFile(descriptor).writeBytes(content.copyOfRange(0, 1024))

        val installer = installer(downloader = ResumingDownloader(content))
        installer.startDownload(allowMeteredNetwork = true)
        advanceUntilIdle()

        assertTrue(installer.state.value is ModelInstallState.Installed)
    }

    @Test
    fun `insufficient storage fails before any bytes are transferred`() = runTest {
        fileSystem.setUsableSpace(1024)
        val downloader = FakeDownloader(content)
        val installer = installer(downloader = downloader)
        installer.refresh()

        installer.startDownload(allowMeteredNetwork = true)
        advanceUntilIdle()

        val failed = installer.state.value as ModelInstallState.Failed
        assertTrue(failed.error is ModelInstallError.InsufficientStorage)
        assertEquals(0, downloader.callCount)
    }

    @Test
    fun `several gigabytes are never downloaded silently over a metered network`() = runTest {
        network.metered = true
        val downloader = FakeDownloader(content)
        val installer = installer(downloader = downloader)
        installer.refresh()

        installer.startDownload(allowMeteredNetwork = false)
        advanceUntilIdle()

        val failed = installer.state.value as ModelInstallState.Failed
        assertEquals(ModelInstallError.MeteredNetworkNotAllowed, failed.error)
        assertEquals(0, downloader.callCount)
    }

    @Test
    fun `no connectivity is reported as a recoverable failure`() = runTest {
        network.connected = false
        val installer = installer()
        installer.refresh()

        installer.startDownload(allowMeteredNetwork = true)
        advanceUntilIdle()

        assertEquals(
            ModelInstallError.NoNetwork,
            (installer.state.value as ModelInstallState.Failed).error,
        )
    }

    @Test
    fun `importing a valid file installs it`() = runTest {
        val installer = installer()
        installer.refresh()

        installer.startImport(android.net.Uri.EMPTY)
        advanceUntilIdle()

        assertTrue(installer.state.value is ModelInstallState.Installed)
        assertFalse(storage.tempFile(descriptor).exists())
    }

    @Test
    fun `importing an invalid file is rejected and cleans up`() = runTest {
        val installer = installer(importer = FakeImporter(ByteArray(10) { 1 }))
        installer.refresh()

        installer.startImport(android.net.Uri.EMPTY)
        advanceUntilIdle()

        val failed = installer.state.value as ModelInstallState.Failed
        assertTrue(failed.error is ModelInstallError.SizeMismatch)
        assertFalse(storage.tempFile(descriptor).exists())
    }

    @Test
    fun `removing the model returns to the not installed state`() = runTest {
        val installer = installer()
        installer.startDownload(allowMeteredNetwork = true)
        advanceUntilIdle()
        assertTrue(installer.state.value is ModelInstallState.Installed)

        installer.remove()

        assertTrue(installer.state.value is ModelInstallState.NotInstalled)
        assertFalse(storage.installedFile(descriptor).exists())
    }

    @Test
    fun `refresh discards an installed file with an unexpected size`() = runTest {
        storage.prepareDirectories()
        storage.installedFile(descriptor).writeBytes(ByteArray(11))

        val installer = installer()
        installer.refresh()

        assertTrue(installer.state.value is ModelInstallState.NotInstalled)
        assertFalse(storage.installedFile(descriptor).exists())
    }

    @Test
    fun `refresh removes orphaned temporary files`() = runTest {
        storage.prepareDirectories()
        val orphan = File(storage.tempFile(descriptor).parentFile, "stale-model.part")
        orphan.writeBytes(ByteArray(64))

        installer().refresh()

        assertFalse(orphan.exists())
    }
}

private fun ModelInstallState.name(): String = this::class.simpleName!!

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

private class FakeDownloader(private val payload: ByteArray) : ModelDownloader {
    var callCount = 0

    override suspend fun download(
        descriptor: ModelDescriptor,
        target: File,
        onProgress: DownloadProgress,
    ): DownloadResult {
        callCount++
        target.parentFile?.mkdirs()
        target.writeBytes(payload)
        onProgress(payload.size.toLong(), descriptor.sizeBytes, false)
        return DownloadResult.Success
    }
}

private class FailingDownloader(
    private val partial: ByteArray,
    private val error: ModelInstallError,
) : ModelDownloader {
    override suspend fun download(
        descriptor: ModelDescriptor,
        target: File,
        onProgress: DownloadProgress,
    ): DownloadResult {
        target.parentFile?.mkdirs()
        target.writeBytes(partial)
        onProgress(partial.size.toLong(), descriptor.sizeBytes, false)
        return DownloadResult.Failure(error, partial.size.toLong())
    }
}

/** Appends only the missing tail, the way a `Range` request does. */
private class ResumingDownloader(private val full: ByteArray) : ModelDownloader {
    var resumedFrom: Long = -1

    override suspend fun download(
        descriptor: ModelDescriptor,
        target: File,
        onProgress: DownloadProgress,
    ): DownloadResult {
        resumedFrom = if (target.isFile) target.length() else 0
        target.appendBytes(full.copyOfRange(resumedFrom.toInt(), full.size))
        onProgress(full.size.toLong(), descriptor.sizeBytes, resumedFrom > 0)
        return DownloadResult.Success
    }
}

private class FakeImporter(private val payload: ByteArray) : ModelFileImporter {
    override suspend fun import(
        uri: android.net.Uri,
        target: File,
        onProgress: suspend (copiedBytes: Long, totalBytes: Long?) -> Unit,
    ): ImportResult {
        target.parentFile?.mkdirs()
        target.writeBytes(payload)
        onProgress(payload.size.toLong(), payload.size.toLong())
        return ImportResult.Success
    }
}
