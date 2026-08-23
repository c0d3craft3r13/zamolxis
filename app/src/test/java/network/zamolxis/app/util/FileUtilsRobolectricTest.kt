package network.zamolxis.app.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Unit tests for FileUtils Android-specific functions using MockK.
 * Tests readFileFromUriWithResult and getFilename which require Android Context.
 *
 * `NoRelaxedMocks` is suppressed for the Android framework classes here (Context,
 * ContentResolver, Cursor, Uri), which have many methods irrelevant to these tests.
 */
@Suppress("NoRelaxedMocks")
class FileUtilsRobolectricTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var mockContentResolver: ContentResolver

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockContentResolver = mockk(relaxed = true)
        every { mockContext.contentResolver } returns mockContentResolver
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `cleanupAllTempFiles removes only stale voice cache artifacts`() {
        val cacheDir = temporaryFolder.newFolder("cache")
        every { mockContext.cacheDir } returns cacheDir
        val voiceNotes = File(cacheDir, "voice-notes").apply { mkdirs() }
        val staleFiles =
            listOf(
                File(voiceNotes, "recording.ogg"),
                File(cacheDir, "voice_message_stale.ogg"),
                File(cacheDir, "voice_preview_stale.wav"),
                File(cacheDir, "voice_waveform_stale.ogg"),
            ).onEach { file ->
                file.writeText("stale")
                assertTrue(file.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000))
            }
        val recentFiles =
            listOf(
                File(voiceNotes, "recent.ogg"),
                File(cacheDir, "voice_message_recent.ogg"),
                File(cacheDir, "voice_preview_recent.wav"),
                File(cacheDir, "voice_waveform_recent.ogg"),
            ).onEach { it.writeText("recent") }
        val unrelated =
            File(cacheDir, "unrelated_stale.ogg").apply {
                writeText("keep")
                assertTrue(setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000))
            }

        assertEquals(4, FileUtils.cleanupAllTempFiles(mockContext, maxAgeMs = 60 * 60 * 1000))

        staleFiles.forEach { assertFalse(it.exists()) }
        recentFiles.forEach { assertTrue(it.exists()) }
        assertTrue(unrelated.exists())
    }

    // ========== readFileFromUriWithResult Tests ==========

    @Test
    fun `readFileFromUriWithResult returns FileAttachment for valid file`() {
        val testData = "Hello, World!".toByteArray()
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        // Setup cursor for filename
        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "test_file.txt"

        // Setup input stream
        every { mockContentResolver.openInputStream(testUri) } returns ByteArrayInputStream(testData)
        every { mockContentResolver.getType(testUri) } returns "text/plain"

        val result = FileUtils.readFileFromUriWithResult(mockContext, testUri)

        val attachment = (result as FileUtils.FileReadResult.Success).attachment
        assertEquals("test_file.txt", attachment.filename)
        assertEquals(testData.size, attachment.sizeBytes)
        assertEquals("text/plain", attachment.mimeType)
    }

    @Test
    fun `readFileFromUriWithResult handles empty file`() {
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "empty.txt"

        every { mockContentResolver.openInputStream(testUri) } returns ByteArrayInputStream(ByteArray(0))
        every { mockContentResolver.getType(testUri) } returns "text/plain"

        val result = FileUtils.readFileFromUriWithResult(mockContext, testUri)

        val attachment = (result as FileUtils.FileReadResult.Success).attachment
        assertEquals("empty.txt", attachment.filename)
        assertEquals(0, attachment.sizeBytes)
    }

    @Test
    fun `readFileFromUriWithResult uses unknown filename when cursor returns no name`() {
        val testData = "test".toByteArray()
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        // Cursor returns false for moveToFirst (empty)
        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns false
        every { testUri.lastPathSegment } returns null

        every { mockContentResolver.openInputStream(testUri) } returns ByteArrayInputStream(testData)
        every { mockContentResolver.getType(testUri) } returns "text/plain"

        val result = FileUtils.readFileFromUriWithResult(mockContext, testUri)

        val attachment = (result as FileUtils.FileReadResult.Success).attachment
        assertEquals("unknown", attachment.filename)
    }

    @Test
    fun `readFileFromUriWithResult returns correct size for binary data`() {
        val binaryData = ByteArray(1024) { it.toByte() }
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "binary.bin"

        every { mockContentResolver.openInputStream(testUri) } returns ByteArrayInputStream(binaryData)
        every { mockContentResolver.getType(testUri) } returns "application/octet-stream"

        val result = FileUtils.readFileFromUriWithResult(mockContext, testUri)

        val attachment = (result as FileUtils.FileReadResult.Success).attachment
        assertEquals(1024, attachment.sizeBytes)
        assertEquals(1024, attachment.data.size)
    }

    @Test
    fun `readFileFromUriWithResult reports an error when openInputStream fails`() {
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "file.txt"

        // Return null for input stream
        every { mockContentResolver.openInputStream(testUri) } returns null
        every { mockContentResolver.getType(testUri) } returns "text/plain"

        val result = FileUtils.readFileFromUriWithResult(mockContext, testUri)

        assertTrue(result is FileUtils.FileReadResult.Error)
    }

    @Test
    fun `readFileFromUriWithResult reports an error on exception`() {
        val testUri = mockk<Uri>()

        // Throw exception on query
        every { mockContentResolver.query(testUri, null, null, null, null) } throws RuntimeException("Test error")

        val result = FileUtils.readFileFromUriWithResult(mockContext, testUri)

        assertTrue(result is FileUtils.FileReadResult.Error)
    }

    // ========== getFilename Tests ==========

    @Test
    fun `getFilename returns display name from cursor`() {
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "my_document.pdf"

        val result = FileUtils.getFilename(mockContext, testUri)

        assertEquals("my_document.pdf", result)
    }

    @Test
    fun `getFilename returns last path segment when cursor is empty`() {
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns false
        every { testUri.lastPathSegment } returns "file.txt"

        val result = FileUtils.getFilename(mockContext, testUri)

        assertEquals("file.txt", result)
    }

    @Test
    fun `getFilename handles cursor without display name column`() {
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns -1 // Column not found
        every { testUri.lastPathSegment } returns "fallback.txt"

        val result = FileUtils.getFilename(mockContext, testUri)

        // When column index is -1, the inner block returns null, which triggers
        // the ?: operator to use lastPathSegment as fallback
        assertEquals("fallback.txt", result)
    }

    @Test
    fun `getFilename handles special characters in filename`() {
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "file (1) [copy].pdf"

        val result = FileUtils.getFilename(mockContext, testUri)

        assertEquals("file (1) [copy].pdf", result)
    }

    @Test
    fun `getFilename handles unicode filename`() {
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "文档.pdf"

        val result = FileUtils.getFilename(mockContext, testUri)

        assertEquals("文档.pdf", result)
    }

    @Test
    fun `getFilename returns last path segment when query returns null`() {
        val testUri = mockk<Uri>()

        every { mockContentResolver.query(testUri, null, null, null, null) } returns null
        every { testUri.lastPathSegment } returns "segment.txt"

        val result = FileUtils.getFilename(mockContext, testUri)

        assertEquals("segment.txt", result)
    }

    @Test
    fun `getFilename returns last path segment on exception`() {
        val testUri = mockk<Uri>()

        every { mockContentResolver.query(testUri, null, null, null, null) } throws RuntimeException("Query failed")
        every { testUri.lastPathSegment } returns "exception_fallback.txt"

        val result = FileUtils.getFilename(mockContext, testUri)

        assertEquals("exception_fallback.txt", result)
    }

    // ========== Bounded read ==========

    @Test
    fun `readAtMost returns the bytes when the stream fits`() =
        with(FileUtils) {
            val data = "under the limit".toByteArray()

            assertArrayEquals(data, ByteArrayInputStream(data).readAtMost(64))
        }

    @Test
    fun `readAtMost gives up instead of buffering a stream past the limit`() =
        with(FileUtils) {
            val data = ByteArray(65) { it.toByte() }

            assertNull(ByteArrayInputStream(data).readAtMost(64))
        }

    @Test
    fun `readAtMost accepts a stream of exactly the limit`() =
        with(FileUtils) {
            val data = ByteArray(64) { it.toByte() }

            assertArrayEquals(data, ByteArrayInputStream(data).readAtMost(64))
        }

    @Test
    fun `readFileFromUriWithResult rejects a file the provider reports as oversized`() {
        val testUri = mockk<Uri>()
        val descriptor = mockk<ParcelFileDescriptor>(relaxed = true)
        every { descriptor.statSize } returns FileUtils.MAX_SINGLE_FILE_SIZE.toLong() + 1
        every { mockContentResolver.openFileDescriptor(testUri, "r") } returns descriptor

        val result = FileUtils.readFileFromUriWithResult(mockContext, testUri)

        val tooLarge = result as FileUtils.FileReadResult.FileTooLarge
        assertEquals(FileUtils.MAX_SINGLE_FILE_SIZE, tooLarge.maxSize)
        verify(exactly = 0) { mockContentResolver.openInputStream(testUri) }
    }
}
