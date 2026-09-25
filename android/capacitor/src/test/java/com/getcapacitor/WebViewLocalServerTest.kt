package com.getcapacitor

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.MockedConstruction
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Serving local files: the streams handed to the WebView and the status codes and headers they go out with.
 */
class WebViewLocalServerTest {
    @get:Rule
    val logs = RecordingLogSink()

    /** A stream that remembers whether it was closed. */
    class TrackingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    /** Opens a fresh [TrackingStream] of [size] bytes per request, or nothing when [missing], and keeps them. */
    class FakeHandler : WebViewLocalServer.PathHandler() {
        var size = 1000
        var missing = false
        val opened = ArrayList<TrackingStream>()

        override fun handle(url: Uri): InputStream? = null

        override fun handle(request: WebResourceRequest): InputStream? {
            if (missing) return null
            return TrackingStream(ByteArray(size)).also { opened.add(it) }
        }
    }

    private val handler = FakeHandler()
    private val request = mock<WebResourceRequest>()
    private lateinit var responses: MockedConstruction<WebResourceResponse>
    private val responseArguments = ArrayList<List<Any?>>()

    @Before
    fun setUp() {
        responses = mockConstruction(WebResourceResponse::class.java) { _, context -> responseArguments.add(context.arguments()) }
    }

    @After
    fun tearDown() {
        responses.close()
    }

    @Test
    fun closeClosesTheOpenedStream() {
        val stream = WebViewLocalServer.LazyInputStream(handler, request)

        assertEquals(0, stream.read())
        stream.close()

        assertTrue(handler.opened.single().closed)
    }

    @Test
    fun closeBeforeUseOpensNothingAndNothingAfterwards() {
        val stream = WebViewLocalServer.LazyInputStream(handler, request)

        stream.close()

        assertEquals(-1, stream.read())
        assertEquals(0, stream.available())
        assertTrue(handler.opened.isEmpty())
    }

    @Test
    fun missingStreamReportsNothingAvailableInsteadOfANegativeCount() {
        handler.missing = true
        val stream = WebViewLocalServer.LazyInputStream(handler, request)

        assertEquals(0, stream.available())
        assertEquals(-1, stream.read())
        assertFalse(stream.exists())
        stream.close()
    }

    @Test
    fun availableCountsTheWrappedStream() {
        val stream = WebViewLocalServer.LazyInputStream(handler, request)

        assertEquals(1000, stream.available())
        assertTrue(stream.exists())
        assertEquals(1, handler.opened.size)
    }

    @Test
    fun injectorClosesTheStreamItReads() {
        val page = TrackingStream("<html><head></head></html>".toByteArray())

        JSInjector("G", "B", "P", "L", null).getInjectedStream(page)

        assertTrue(page.closed)
    }

    @Test
    fun existingFileIsServedWithTheHandlerStatus() {
        val response = serve("/assets/app.js")

        assertEquals(200, response.statusCode)
        assertEquals(1000, response.stream.available())
    }

    @Test
    fun missingFileIsServedAsA404() {
        handler.missing = true

        val response = serve("/assets/app.js")

        assertEquals(404, response.statusCode)
    }

    class Response(val statusCode: Int, val headers: Map<*, *>, val stream: InputStream)

    /**
     * Runs a GET for [path] on the app's own host through [WebViewLocalServer.shouldInterceptRequest], with the
     * given request headers, and returns what the WebResourceResponse was built from.
     */
    private fun serve(path: String, headers: Map<String, String> = emptyMap()): Response {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        val bridge = mock<Bridge>()
        whenever(bridge.host).thenReturn(HOST)

        val server = WebViewLocalServer(context, bridge, null, arrayListOf<String?>(HOST), false)
        val root = mock<Uri>()
        whenever(root.scheme).thenReturn("https")
        whenever(root.authority).thenReturn(HOST)
        whenever(root.path).thenReturn("/**")
        server.register(root, handler)

        val url = mock<Uri>()
        whenever(url.scheme).thenReturn("https")
        whenever(url.authority).thenReturn(HOST)
        whenever(url.host).thenReturn(HOST)
        whenever(url.path).thenReturn(path)
        val segments = path.removePrefix("/").split("/")
        whenever(url.pathSegments).thenReturn(segments)
        whenever(url.lastPathSegment).thenReturn(segments.last())
        whenever(url.toString()).thenReturn("https://$HOST$path")
        whenever(request.url).thenReturn(url)
        whenever(request.method).thenReturn("GET")
        whenever(request.requestHeaders).thenReturn(headers)

        assertNotNull(server.shouldInterceptRequest(request))

        // WebResourceResponse(mimeType, encoding, statusCode, reasonPhrase, responseHeaders, data)
        val arguments = responseArguments.single()
        return Response(arguments[2] as Int, arguments[4] as Map<*, *>, arguments[5] as InputStream)
    }

    private companion object {
        const val HOST = "localhost"
    }
}
