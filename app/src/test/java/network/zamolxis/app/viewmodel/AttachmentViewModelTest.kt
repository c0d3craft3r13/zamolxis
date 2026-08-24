package network.zamolxis.app.viewmodel

import network.zamolxis.app.data.model.ImageCompressionPreset
import network.zamolxis.app.repository.SettingsRepository
import network.zamolxis.app.service.ConversationLinkManager
import network.zamolxis.app.util.FileAttachment
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AttachmentViewModel.
 *
 * Covers what the composer stages, what it hands to the send path, and what it
 * takes back afterwards. The compression itself is not exercised here — it goes
 * through `ImageUtils`, which needs a real Android decoder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var conversationLinkManager: ConversationLinkManager
    private lateinit var viewModel: AttachmentViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        settingsRepository = mockk()
        conversationLinkManager = mockk()
        every { conversationLinkManager.linkStates } returns MutableStateFlow(emptyMap())
        every { conversationLinkManager.openConversationLink(any()) } just Runs
        coEvery { settingsRepository.getImageCompressionPreset() } returns ImageCompressionPreset.LOW
        viewModel = AttachmentViewModel(settingsRepository, conversationLinkManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Image Selection Tests ==========

    @Test
    fun `selectImage updates all image state fields`() {
        val imageData = byteArrayOf(1, 2, 3, 4, 5)
        val imageFormat = "jpg"

        viewModel.selectImage(imageData, imageFormat, isAnimated = false)

        assertTrue(viewModel.selectedImageData.value.contentEquals(imageData))
        assertEquals(imageFormat, viewModel.selectedImageFormat.value)
        assertFalse(viewModel.selectedImageIsAnimated.value)
    }

    @Test
    fun `selectImage with animated flag sets isAnimated true`() {
        val gifData = byteArrayOf(0x47, 0x49, 0x46, 0x38)

        viewModel.selectImage(gifData, "gif", isAnimated = true)

        assertTrue(viewModel.selectedImageIsAnimated.value)
    }

    @Test
    fun `clearSelectedImage resets all image state`() {
        viewModel.selectImage(byteArrayOf(1, 2, 3), "png", isAnimated = true)

        viewModel.clearSelectedImage()

        assertNull(viewModel.selectedImageData.value)
        assertNull(viewModel.selectedImageFormat.value)
        assertFalse(viewModel.selectedImageIsAnimated.value)
    }

    @Test
    fun `setProcessingImage updates processing state`() {
        assertFalse(viewModel.isProcessingImage.value)

        viewModel.setProcessingImage(true)
        assertTrue(viewModel.isProcessingImage.value)

        viewModel.setProcessingImage(false)
        assertFalse(viewModel.isProcessingImage.value)
    }

    // ========== File Attachment Tests ==========

    @Test
    fun `addFileAttachment adds file to list`() =
        runTest {
            val attachment = createFileAttachment("test.pdf", 1024)

            viewModel.addFileAttachment(attachment)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, viewModel.selectedFileAttachments.value.size)
            assertEquals("test.pdf", viewModel.selectedFileAttachments.value[0].filename)
        }

    @Test
    fun `addFileAttachment preserves existing attachments`() =
        runTest {
            val attachment1 = createFileAttachment("file1.pdf", 1024)
            val attachment2 = createFileAttachment("file2.doc", 2048)

            viewModel.addFileAttachment(attachment1)
            viewModel.addFileAttachment(attachment2)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(2, viewModel.selectedFileAttachments.value.size)
            assertEquals("file1.pdf", viewModel.selectedFileAttachments.value[0].filename)
            assertEquals("file2.doc", viewModel.selectedFileAttachments.value[1].filename)
        }

    /**
     * Intent-to-transmit: attaching a file is the user asking for a transfer, so
     * the link may be established then — and only then. Reticulum's rule is that
     * opening a chat must not put anything on the air.
     */
    @Test
    fun `addFileAttachment opens the conversation link`() =
        runTest {
            viewModel.addFileAttachment(createFileAttachment("test.pdf", 1024), "abcdef")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, viewModel.selectedFileAttachments.value.size)
            verify { conversationLinkManager.openConversationLink("abcdef") }
        }

    @Test
    fun `addFileAttachment without a conversation opens no link`() =
        runTest {
            viewModel.addFileAttachment(createFileAttachment("test.pdf", 1024), null)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, viewModel.selectedFileAttachments.value.size)
            verify(exactly = 0) { conversationLinkManager.openConversationLink(any()) }
        }

    @Test
    fun `removeFileAttachment removes file at index`() =
        runTest {
            viewModel.addFileAttachment(createFileAttachment("file1.pdf", 1024))
            viewModel.addFileAttachment(createFileAttachment("file2.doc", 2048))
            viewModel.addFileAttachment(createFileAttachment("file3.txt", 512))
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.removeFileAttachment(1)

            assertEquals(2, viewModel.selectedFileAttachments.value.size)
            assertEquals("file1.pdf", viewModel.selectedFileAttachments.value[0].filename)
            assertEquals("file3.txt", viewModel.selectedFileAttachments.value[1].filename)
        }

    @Test
    fun `removeFileAttachment with invalid index does nothing`() =
        runTest {
            viewModel.addFileAttachment(createFileAttachment("test.pdf", 1024))
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.removeFileAttachment(5)

            assertEquals(1, viewModel.selectedFileAttachments.value.size)
        }

    @Test
    fun `removeFileAttachment with negative index does nothing`() =
        runTest {
            viewModel.addFileAttachment(createFileAttachment("test.pdf", 1024))
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.removeFileAttachment(-1)

            assertEquals(1, viewModel.selectedFileAttachments.value.size)
        }

    @Test
    fun `clearFileAttachments removes all files`() =
        runTest {
            viewModel.addFileAttachment(createFileAttachment("file1.pdf", 1024))
            viewModel.addFileAttachment(createFileAttachment("file2.doc", 2048))
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.clearFileAttachments()

            assertTrue(viewModel.selectedFileAttachments.value.isEmpty())
        }

    @Test
    fun `setProcessingFile updates processing state`() {
        assertFalse(viewModel.isProcessingFile.value)

        viewModel.setProcessingFile(true)
        assertTrue(viewModel.isProcessingFile.value)

        viewModel.setProcessingFile(false)
        assertFalse(viewModel.isProcessingFile.value)
    }

    // ========== Combined State Tests ==========

    @Test
    fun `clearAllAttachments clears both images and files`() =
        runTest {
            viewModel.selectImage(byteArrayOf(1, 2, 3), "jpg")
            viewModel.addFileAttachment(createFileAttachment("test.pdf", 1024))
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.clearAllAttachments()

            assertNull(viewModel.selectedImageData.value)
            assertTrue(viewModel.selectedFileAttachments.value.isEmpty())
        }

    @Test
    fun `hasAttachments is true when image is selected`() =
        runTest {
            val job =
                backgroundScope.launch {
                    viewModel.hasAttachments.collect {}
                }

            viewModel.selectImage(byteArrayOf(1, 2, 3), "jpg")
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.hasAttachments.value)
            job.cancel()
        }

    @Test
    fun `hasAttachments is true when files are selected`() =
        runTest {
            val job =
                backgroundScope.launch {
                    viewModel.hasAttachments.collect {}
                }

            viewModel.addFileAttachment(createFileAttachment("test.pdf", 1024))
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.hasAttachments.value)
            job.cancel()
        }

    @Test
    fun `hasAttachments is false when nothing selected`() =
        runTest {
            val job =
                backgroundScope.launch {
                    viewModel.hasAttachments.collect {}
                }

            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(viewModel.hasAttachments.value)
            job.cancel()
        }

    @Test
    fun `totalAttachmentSize sums all file sizes`() =
        runTest {
            val job =
                backgroundScope.launch {
                    viewModel.totalAttachmentSize.collect {}
                }

            viewModel.addFileAttachment(createFileAttachment("file1.pdf", 1000))
            viewModel.addFileAttachment(createFileAttachment("file2.doc", 2500))
            viewModel.addFileAttachment(createFileAttachment("file3.txt", 500))
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(4000, viewModel.totalAttachmentSize.value)
            job.cancel()
        }

    @Test
    fun `totalAttachmentSize is zero with no files`() =
        runTest {
            val job =
                backgroundScope.launch {
                    viewModel.totalAttachmentSize.collect {}
                }

            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, viewModel.totalAttachmentSize.value)
            job.cancel()
        }

    // ========== Send hand-off Tests ==========

    @Test
    fun `snapshot carries the staged image and files by reference`() =
        runTest {
            val imageData = byteArrayOf(9, 9, 9)
            viewModel.selectImage(imageData, "png")
            viewModel.addFileAttachment(createFileAttachment("test.pdf", 16))
            testDispatcher.scheduler.advanceUntilIdle()

            val snapshot = viewModel.snapshot()

            assertSame(imageData, snapshot.imageData)
            assertEquals("png", snapshot.imageFormat)
            assertSame(viewModel.selectedFileAttachments.value, snapshot.files)
            assertFalse(snapshot.isEmpty)
        }

    @Test
    fun `snapshot of an empty composer is empty`() {
        assertTrue(viewModel.snapshot().isEmpty)
    }

    /**
     * A send takes a snapshot, not the live state, so what it later asks to
     * clear must be exactly what it carried.
     */
    @Test
    fun `clearSubmitted drops the attachments the send consumed`() =
        runTest {
            viewModel.selectImage(byteArrayOf(1, 2, 3), "jpg")
            viewModel.addFileAttachment(createFileAttachment("test.pdf", 16))
            testDispatcher.scheduler.advanceUntilIdle()
            val submitted = viewModel.snapshot()

            viewModel.clearSubmitted(submitted)

            assertNull(viewModel.selectedImageData.value)
            assertTrue(viewModel.selectedFileAttachments.value.isEmpty())
        }

    /**
     * The whole reason [AttachmentViewModel.clearSubmitted] compares by identity:
     * a photo staged while the previous one was going out must survive that send
     * landing. Equal bytes are not the same attachment.
     */
    @Test
    fun `clearSubmitted keeps an image staged after the send started`() =
        runTest {
            viewModel.selectImage(byteArrayOf(1, 2, 3), "jpg")
            val submitted = viewModel.snapshot()

            val restaged = byteArrayOf(1, 2, 3)
            viewModel.selectImage(restaged, "jpg")
            viewModel.clearSubmitted(submitted)

            assertSame(restaged, viewModel.selectedImageData.value)
        }

    @Test
    fun `clearSubmitted keeps files staged after the send started`() =
        runTest {
            viewModel.addFileAttachment(createFileAttachment("first.pdf", 16))
            testDispatcher.scheduler.advanceUntilIdle()
            val submitted = viewModel.snapshot()

            viewModel.addFileAttachment(createFileAttachment("second.pdf", 16))
            testDispatcher.scheduler.advanceUntilIdle()
            viewModel.clearSubmitted(submitted)

            assertEquals(2, viewModel.selectedFileAttachments.value.size)
        }

    // ========== Quality Selection Tests ==========

    @Test
    fun `dismissQualitySelection clears quality state`() {
        assertNull(viewModel.qualitySelectionState.value)

        viewModel.dismissQualitySelection()

        assertNull(viewModel.qualitySelectionState.value)
    }

    @Test
    fun `selectImageQuality does nothing when no quality state`() =
        runTest {
            viewModel.selectImageQuality(ImageCompressionPreset.MEDIUM)

            assertNull(viewModel.selectedImageData.value)
        }

    @Test
    fun `pendingSharedImageCount is zero outside a shared batch`() {
        assertEquals(0, viewModel.pendingSharedImageCount())
    }

    @Test
    fun `selectImageQualityForSharedImages emits nothing without a pending batch`() =
        runTest {
            val requests = mutableListOf<SharedImageRequest>()
            val job = backgroundScope.launch { viewModel.sharedImageRequest.collect { requests.add(it) } }
            testDispatcher.scheduler.runCurrent()

            viewModel.selectImageQualityForSharedImages(ImageCompressionPreset.MEDIUM)
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(requests.isEmpty())
            job.cancel()
        }

    // ========== Helper Functions ==========

    private fun createFileAttachment(
        filename: String,
        sizeBytes: Int,
    ): FileAttachment =
        FileAttachment(
            filename = filename,
            data = ByteArray(sizeBytes),
            mimeType = "application/octet-stream",
            sizeBytes = sizeBytes,
        )
}
