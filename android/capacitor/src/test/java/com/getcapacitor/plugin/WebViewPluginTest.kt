package com.getcapacitor.plugin

import com.getcapacitor.Bridge
import com.getcapacitor.JSObject
import com.getcapacitor.MessageHandler
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginHandle
import com.getcapacitor.PluginResult
import com.getcapacitor.RecordingLogSink
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * Changing where the app is served from needs a path; without one the call is rejected and nothing changes.
 */
class WebViewPluginTest {
    @get:Rule
    val logs = RecordingLogSink()

    private val bridge = mock<Bridge>()
    private val handler = mock<MessageHandler>()
    private val plugin = WebView()

    @Before
    fun setUp() {
        PluginHandle(bridge, plugin)
    }

    private fun call(method: String, options: JSObject = JSObject()): PluginCall = PluginCall(handler, "WebView", "1", method, options)

    private fun rejection(): String? {
        val error = argumentCaptor<PluginResult>()
        verify(handler).sendResponseMessage(any(), isNull(), error.capture())
        return JSObject(error.firstValue.toString()).getString("message")
    }

    @Test
    fun setServerBasePathWithoutPathIsRejected() {
        plugin.setServerBasePath(call("setServerBasePath"))

        assertEquals("Must provide a path", rejection())
        verify(bridge, never()).serverBasePath = anyOrNull()
    }

    @Test
    fun setServerAssetPathWithoutPathIsRejected() {
        plugin.setServerAssetPath(call("setServerAssetPath"))

        assertEquals("Must provide a path", rejection())
        verify(bridge, never()).setServerAssetPath(anyOrNull())
    }

    @Test
    fun setServerBasePathServesThePath() {
        plugin.setServerBasePath(call("setServerBasePath", JSObject().put("path", "/data/app/www")))

        verify(bridge).serverBasePath = "/data/app/www"
        verify(handler).sendResponseMessage(any(), anyOrNull(), isNull())
    }

    @Test
    fun setServerAssetPathServesThePath() {
        plugin.setServerAssetPath(call("setServerAssetPath", JSObject().put("path", "public")))

        verify(bridge).setServerAssetPath("public")
        verify(handler).sendResponseMessage(any(), anyOrNull(), isNull())
    }
}
