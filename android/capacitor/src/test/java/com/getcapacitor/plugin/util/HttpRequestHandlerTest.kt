package com.getcapacitor.plugin.util

import android.os.LocaleList
import com.getcapacitor.JSObject
import com.getcapacitor.MessageHandler
import com.getcapacitor.PluginCall
import com.getcapacitor.plugin.util.HttpRequestHandler.HttpURLConnectionBuilder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class HttpRequestHandlerTest {
    private lateinit var localeList: MockedStatic<LocaleList>

    @Before
    fun setUp() {
        // CapacitorHttpUrlConnection reads the device locales for Accept-Language.
        val locales = mock<LocaleList>()
        whenever(locales.get(0)).thenReturn(Locale.US)
        localeList = mockStatic(LocaleList::class.java)
        localeList.`when`<LocaleList> { LocaleList.getDefault() }.thenReturn(locales)
    }

    @After
    fun tearDown() {
        localeList.close()
    }

    private fun call(): PluginCall {
        val options = JSObject().put("url", "${FakeHttpProtocol.SCHEME}://server/path").put("headers", JSObject().put("User-Agent", "test"))
        return PluginCall(mock<MessageHandler>(), "CapacitorHttp", "1", "get", options)
    }

    /** Answers every request with [body] and returns the connections opened. */
    private fun serve(body: () -> InputStream): List<FakeHttpConnection> {
        val connections = ArrayList<FakeHttpConnection>()
        FakeHttpProtocol.install { url -> FakeHttpConnection(url, body).also { connections.add(it) } }
        return connections
    }

    @Test
    fun requestDisconnectsAfterReadingTheResponse() {
        val connections = serve { ByteArrayInputStream("hi".toByteArray()) }
        val call = call()

        val response = HttpRequestHandler.request(call, "GET", null)

        assertEquals("hi", response.getString("data"))
        assertTrue(connections.single().disconnected)
        assertFalse(call.data.has("activeCapacitorHttpUrlConnection"))
    }

    @Test
    fun requestDisconnectsWhenReadingTheResponseFails() {
        val connections = serve { throw IOException("reset") }

        try {
            HttpRequestHandler.request(call(), "GET", null)
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("reset", e.message)
        }

        assertTrue(connections.single().disconnected)
    }

    @Test
    fun requestWithoutBridgeOrUserAgentUsesTheConnectionDefault() {
        val connections = serve { ByteArrayInputStream("hi".toByteArray()) }
        val call =
            PluginCall(
                mock<MessageHandler>(),
                "CapacitorHttp",
                "1",
                "get",
                JSObject().put("url", "${FakeHttpProtocol.SCHEME}://server/path")
            )

        // The bridge parameter is nullable; without a User-Agent header this dereferenced it.
        val response = HttpRequestHandler.request(call, "GET", null)

        assertEquals("hi", response.getString("data"))
        assertNull(connections.single().sentHeaders["User-Agent"])
    }

    @Test
    fun connectionIsTrackedOnlyWhileTheRequestRuns() {
        val active = HashMap<PluginCall, CapacitorHttpUrlConnection>()
        val call = call()
        var trackedWhileReading = false
        serve {
            trackedWhileReading = active[call] != null
            ByteArrayInputStream(ByteArray(0))
        }

        HttpRequestHandler.request(call, "GET", null, active)

        assertTrue(trackedWhileReading)
        assertTrue(active.isEmpty())
    }

    @Test
    fun testHttpURLConnectionBuilderSetUrlParamsEncoded() {
        val expectedQuery = "k=a%26b"
        val expectedUrl = "$BASE_URL?$expectedQuery"
        val actualUrl =
            HttpURLConnectionBuilder()
                .setUrl(URL(BASE_URL))
                .setUrlParams(JSObject(PARAMS_JSON), true)
                .url
                .toString()
        assertEquals(expectedUrl, actualUrl)
    }

    @Test
    fun testHttpURLConnectionBuilderSetUrlParamsNotEncoded() {
        val expectedQuery = "k=a&b"
        val expectedUrl = "$BASE_URL?$expectedQuery"
        val actualUrl =
            HttpURLConnectionBuilder()
                .setUrl(URL(BASE_URL))
                .setUrlParams(JSObject(PARAMS_JSON), false)
                .url
                .toString()
        assertEquals(expectedUrl, actualUrl)
    }

    @Test
    fun urlParamsKeepTheFragmentApartFromTheQuery() {
        val actualUrl =
            HttpURLConnectionBuilder()
                .setUrl(URL("https://example.com/search?q=1#results"))
                .setUrlParams(JSObject().put("page", "2"))
                .url!!

        // The fragment was appended to the last parameter ("page=2results") and so sent to the server.
        assertEquals("https://example.com/search?q=1&page=2#results", actualUrl.toString())
        assertEquals("q=1&page=2", actualUrl.query)
        assertEquals("results", actualUrl.ref)
    }

    @Test
    fun urlParamsKeepTheFragmentOfAUrlWithoutQuery() {
        val actualUrl =
            HttpURLConnectionBuilder()
                .setUrl(URL("https://example.com/list#top"))
                .setUrlParams(JSObject().put("page", "2"))
                .url!!

        assertEquals("https://example.com/list?page=2#top", actualUrl.toString())
    }

    private companion object {
        const val BASE_URL = "https://httpbin.org/get"
        const val PARAMS_JSON = "{\"k\": \"a&b\"}\n"
    }
}
