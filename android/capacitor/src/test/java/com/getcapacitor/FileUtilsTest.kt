package com.getcapacitor

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Resolving a content URI that has no file path copies the content into the app, closing what it opens.
 */
class FileUtilsTest {
    @get:Rule
    val logs = RecordingLogSink()

    class TrackingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private val filesDir: File = Files.createTempDirectory("file-utils").toFile()
    private val resolver = mock<ContentResolver>()
    private val context = mock<Context>()
    private val uri = mock<Uri>()

    // The first query asks for the _data column, which these providers do not have.
    private val dataCursor = mock<Cursor>()
    private val nameCursor = mock<Cursor>()
    private lateinit var documents: MockedStatic<DocumentsContract>

    @Before
    fun setUp() {
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(context.filesDir).thenReturn(filesDir)
        whenever(uri.scheme).thenReturn("content")
        whenever(uri.authority).thenReturn("com.example.provider")
        whenever(dataCursor.moveToFirst()).thenReturn(false)
        whenever(nameCursor.getColumnIndex("_display_name")).thenReturn(0)
        whenever(nameCursor.moveToFirst()).thenReturn(true)
        whenever(nameCursor.getString(0)).thenReturn("photo.jpg")
        documents = mockStatic(DocumentsContract::class.java)
        documents.`when`<Boolean> { DocumentsContract.isDocumentUri(any(), any()) }.thenReturn(false)
    }

    @After
    fun tearDown() {
        documents.close()
        filesDir.deleteRecursively()
    }

    private fun queriesReturn(nameCursor: Cursor?) {
        whenever(resolver.query(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(dataCursor, nameCursor)
    }

    @Test
    fun contentIsCopiedUnderItsDisplayNameAndEverythingIsClosed() {
        queriesReturn(nameCursor)
        val content = TrackingStream(byteArrayOf(7, 8, 9))
        whenever(resolver.openInputStream(uri)).thenReturn(content)

        val path = FileUtils.getFileUrlForUri(context, uri)

        assertEquals(File(filesDir, "photo.jpg").path, path)
        assertArrayEquals(byteArrayOf(7, 8, 9), File(path!!).readBytes())
        assertTrue(content.closed)
        verify(dataCursor).close()
        verify(nameCursor).close()
    }

    @Test
    fun missingCursorYieldsNoPath() {
        queriesReturn(null)

        assertNull(FileUtils.getFileUrlForUri(context, uri))
    }

    @Test
    fun cursorWithoutADisplayNameYieldsNoPathAndIsClosed() {
        queriesReturn(nameCursor)
        whenever(nameCursor.getColumnIndex("_display_name")).thenReturn(-1)

        assertNull(FileUtils.getFileUrlForUri(context, uri))
        verify(nameCursor).close()
    }

    @Test
    fun emptyCursorYieldsNoPathAndIsClosed() {
        queriesReturn(nameCursor)
        whenever(nameCursor.moveToFirst()).thenReturn(false)

        assertNull(FileUtils.getFileUrlForUri(context, uri))
        verify(nameCursor).close()
    }

    @Test
    fun aMediaDocumentOfAnotherTypeYieldsNoPath() {
        // What the system picker's Documents category returns, for example for a PDF.
        whenever(uri.authority).thenReturn("com.android.providers.media.documents")
        documents.`when`<Boolean> { DocumentsContract.isDocumentUri(any(), any()) }.thenReturn(true)
        documents.`when`<String> { DocumentsContract.getDocumentId(any()) }.thenReturn("document:1234")

        // It threw a NullPointerException instead.
        assertNull(FileUtils.getFileUrlForUri(context, uri))
    }

    @Test
    fun unreadableContentYieldsNoPathAndIsLogged() {
        queriesReturn(nameCursor)
        whenever(resolver.openInputStream(uri)).thenThrow(FileNotFoundException("gone"))

        assertNull(FileUtils.getFileUrlForUri(context, uri))
        verify(nameCursor).close()
        assertTrue(logs.entries.any { it.throwable is FileNotFoundException })
    }
}
