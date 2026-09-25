package com.getcapacitor

import android.net.Uri
import android.webkit.WebResourceRequest
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * The response body of a local file: opened on first use, and closed with the file it opened.
 */
class LazyInputStreamTest {
    /** A stream that remembers whether it was closed. */
    class TrackingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    /** Opens a fresh [TrackingStream] of 1000 bytes per call, or nothing while [missing], and counts the calls. */
    class FakeHandler : WebViewLocalServer.PathHandler() {
        var missing = false
        var calls = 0
        val opened = ArrayList<TrackingStream>()

        override fun handle(url: Uri): InputStream? = null

        override fun handle(request: WebResourceRequest): InputStream? {
            calls++
            if (missing) return null
            return TrackingStream(ByteArray(1000)).also { opened.add(it) }
        }
    }

    private val handler = FakeHandler()
    private val request = mock<WebResourceRequest>()

    @Test
    fun nothingIsOpenedBeforeFirstUse() {
        LazyInputStream(handler, request)

        assertEquals(0, handler.calls)
    }

    @Test
    fun theFileIsOpenedOnceAndRead() {
        val stream = LazyInputStream(handler, request)

        assertTrue(stream.exists())
        assertEquals(1000, stream.available())
        assertEquals(0, stream.read())
        assertEquals(10, stream.skip(10))

        assertEquals(1, handler.opened.size)
    }

    @Test
    fun closeClosesTheOpenedStream() {
        val stream = LazyInputStream(handler, request)

        assertEquals(0, stream.read())
        stream.close()

        assertTrue(handler.opened.single().closed)
    }

    @Test
    fun closeBeforeUseOpensNothingAndNothingAfterwards() {
        val stream = LazyInputStream(handler, request)

        stream.close()

        assertEquals(-1, stream.read())
        assertEquals(0, stream.available())
        assertFalse(stream.exists())
        assertEquals(0, handler.calls)
    }

    @Test
    fun missingStreamReportsNothingAvailableInsteadOfANegativeCount() {
        handler.missing = true
        val stream = LazyInputStream(handler, request)

        assertEquals(0, stream.available())
        assertEquals(-1, stream.read())
        assertEquals(-1, stream.read(ByteArray(8)))
        assertEquals(0, stream.skip(8))
        assertFalse(stream.exists())
        stream.close()
    }

    @Test
    fun aHandlerThatYieldedNothingIsAskedAgain() {
        handler.missing = true
        val stream = LazyInputStream(handler, request)
        assertFalse(stream.exists())

        handler.missing = false

        assertTrue(stream.exists())
        assertEquals(2, handler.calls)
        assertEquals(1, handler.opened.size)
    }
}
