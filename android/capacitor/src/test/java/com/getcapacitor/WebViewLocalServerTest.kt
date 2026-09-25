package com.getcapacitor

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test
    fun rangeRequestIsAnsweredWithAPartialResponse() {
        val cases =
            mapOf(
                "bytes=0-" to "bytes 0-999/1000",
                "bytes=100-199" to "bytes 100-199/1000",
                "bytes=-100" to "bytes 900-999/1000",
                "bytes=0-5000" to "bytes 0-999/1000"
            )
        for ((range, contentRange) in cases) {
            responseArguments.clear()

            val response = serve("/assets/video.mp4", mapOf("Range" to range))

            assertEquals(range, 206, response.statusCode)
            assertEquals(range, contentRange, response.headers["Content-Range"])
            assertEquals(range, "bytes", response.headers["Accept-Ranges"])
        }
    }

    @Test
    fun lowercaseRangeHeaderIsRead() {
        val response = serve("/assets/video.mp4", mapOf("range" to "bytes=10-19"))

        assertEquals(206, response.statusCode)
        assertEquals("bytes 10-19/1000", response.headers["Content-Range"])
    }

    @Test
    fun malformedOrUnsatisfiableRangeFallsBackToTheWholeFile() {
        for (range in listOf("bytes", "bytes=", "bytes=abc", "bytes=0-1,5-6", "bytes=5-2", "bytes=2000-", "items=0-1")) {
            responseArguments.clear()
            handler.opened.clear()

            val response = serve("/assets/video.mp4", mapOf("Range" to range))

            assertEquals(range, 200, response.statusCode)
            assertNull(range, response.headers["Content-Range"])
            // The stream opened to size the range is closed; the response gets a fresh one.
            assertEquals(range, 2, handler.opened.size)
            assertTrue(range, handler.opened[0].closed)
            assertFalse(range, handler.opened[1].closed)
        }
    }

    @Test
    fun aPathOfOnlySlashesIsARouteInHtml5Mode() {
        val appDir = Files.createTempDirectory("app").toFile()
        File(appDir, "index.html").writeText("<html><head></head></html>")

        // It has no last segment; reading it threw a NullPointerException out of shouldInterceptRequest.
        assertNotNull(intercept("//", html5mode = true, appDir = appDir))

        val arguments = responseArguments.single()
        assertEquals("text/html", arguments[0])
        assertEquals(200, arguments[2])
    }

    @Test
    fun aPathOfOnlySlashesIsNotServedWithoutHtml5Mode() {
        assertNull(intercept("//"))
        assertNull(intercept("///"))
    }

    @Test
    fun anInvalidPathLeavesTheServedPathAlone() {
        val server =
            WebViewLocalServer(mock<Context>().also { whenever(it.applicationContext).thenReturn(it) }, mock(), null, emptyList(), false)
        server.hostAssets("public")

        // A null path used to replace the served path (and switch from assets to files) before failing.
        assertThrows(IllegalArgumentException::class.java) { server.hostFiles(null) }
        assertThrows(IllegalArgumentException::class.java) { server.hostFiles("www/*") }
        assertThrows(IllegalArgumentException::class.java) { server.hostAssets(null) }

        assertEquals("public", server.basePath)
    }

    @Test
    fun rangeOnAMissingFileIsA404() {
        handler.missing = true

        val response = serve("/assets/video.mp4", mapOf("Range" to "bytes=0-"))

        assertEquals(404, response.statusCode)
    }

    class Response(val mimeType: String?, val statusCode: Int, val headers: Map<*, *>, val stream: InputStream)

    /**
     * Runs a GET for [path] on the app's own host through [WebViewLocalServer.shouldInterceptRequest], with the
     * given request headers, and returns what the WebResourceResponse was built from.
     */
    private fun serve(path: String, headers: Map<String, String> = emptyMap()): Response {
        assertNotNull(intercept(path, headers))

        // WebResourceResponse(mimeType, encoding, statusCode, reasonPhrase, responseHeaders, data)
        val arguments = responseArguments.single()
        return Response(arguments[0] as String?, arguments[2] as Int, arguments[4] as Map<*, *>, arguments[5] as InputStream)
    }

    /**
     * Runs a GET for [path] through [WebViewLocalServer.shouldInterceptRequest] and returns its response. In
     * [html5mode], the app's index.html is read from [appDir].
     */
    private fun intercept(
        path: String,
        headers: Map<String, String> = emptyMap(),
        html5mode: Boolean = false,
        appDir: File? = null
    ): WebResourceResponse? {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        val bridge = mock<Bridge>()
        whenever(bridge.host).thenReturn(HOST)

        // No authorities: hosting files registers nothing, and the handler below serves every path.
        val server = WebViewLocalServer(context, bridge, null, emptyList(), html5mode)
        appDir?.let { server.hostFiles(it.path) }
        // Like the registrations of hostAssets: the host itself, which a path without segments ("/") matches, and
        // everything under it.
        for (rootPath in listOf(null, "/**")) {
            val root = mock<Uri>()
            whenever(root.scheme).thenReturn("https")
            whenever(root.authority).thenReturn(HOST)
            whenever(root.path).thenReturn(rootPath)
            server.register(root, handler)
        }

        val url = mock<Uri>()
        whenever(url.scheme).thenReturn("https")
        whenever(url.authority).thenReturn(HOST)
        whenever(url.host).thenReturn(HOST)
        whenever(url.path).thenReturn(path)
        // Like android.net.Uri, empty segments are left out.
        val segments = path.split("/").filter { it.isNotEmpty() }
        whenever(url.pathSegments).thenReturn(segments)
        whenever(url.lastPathSegment).thenReturn(segments.lastOrNull())
        whenever(url.toString()).thenReturn("https://$HOST$path")
        whenever(request.url).thenReturn(url)
        whenever(request.method).thenReturn("GET")
        whenever(request.requestHeaders).thenReturn(headers)

        return server.shouldInterceptRequest(request)
    }

    private companion object {
        const val HOST = "localhost"
    }
}
