package com.getcapacitor.plugin.util

import com.getcapacitor.JSObject
import com.getcapacitor.plugin.util.HttpRequestHandler.HttpURLConnectionBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URL

class HttpRequestHandlerTest {
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

    private companion object {
        const val BASE_URL = "https://httpbin.org/get"
        const val PARAMS_JSON = "{\"k\": \"a&b\"}\n"
    }
}
