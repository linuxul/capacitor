package com.getcapacitor.plugin

import com.getcapacitor.JSObject
import com.getcapacitor.MessageHandler
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginResult
import com.getcapacitor.RecordingLogSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

/**
 * A cookie call with a missing option is rejected and leaves the cookie store alone.
 */
class CapacitorCookiesTest {
    @get:Rule
    val logs = RecordingLogSink()

    private val handler = mock<MessageHandler>()
    private val cookieManager = mock<CapacitorCookieManager>()
    private val plugin = CapacitorCookies()

    @Before
    fun setUp() {
        // load() needs a live WebView; hand the plugin its cookie manager directly instead.
        CapacitorCookies::class.java.getDeclaredField("cookieManager").apply {
            isAccessible = true
            set(plugin, cookieManager)
        }
    }

    private fun call(method: String, options: JSObject): PluginCall = PluginCall(handler, "CapacitorCookies", "1", method, options)

    private fun rejection(): String? {
        val error = argumentCaptor<PluginResult>()
        verify(handler, times(1)).sendResponseMessage(any(), anyOrNull(), anyOrNull())
        verify(handler).sendResponseMessage(any(), isNull(), error.capture())
        return JSObject(error.firstValue.toString()).getString("message")
    }

    private fun assertNoDroppedResponses() {
        assertTrue(logs.entries.none { it.message.contains("already settled") })
    }

    @Test
    fun setCookieWithoutKeyRejectsAndWritesNothing() {
        plugin.setCookie(call("setCookie", JSObject().put("value", "v")))

        assertEquals("Must provide key", rejection())
        verifyNoInteractions(cookieManager)
        assertNoDroppedResponses()
    }

    @Test
    fun setCookieWithoutValueRejectsAndWritesNothing() {
        plugin.setCookie(call("setCookie", JSObject().put("key", "k")))

        assertEquals("Must provide value", rejection())
        verifyNoInteractions(cookieManager)
        assertNoDroppedResponses()
    }

    @Test
    fun setCookieWithKeyAndValueWritesAndResolves() {
        plugin.setCookie(call("setCookie", JSObject().put("key", "k").put("value", "v").put("url", "https://a.test")))

        verify(cookieManager).setCookie("https://a.test", "k", "v", "", "/")
        verify(handler, times(1)).sendResponseMessage(any(), isNull(), isNull())
    }

    @Test
    fun deleteCookieWithoutKeyRejectsAndWritesNothing() {
        plugin.deleteCookie(call("deleteCookie", JSObject().put("url", "https://a.test")))

        assertEquals("Must provide key", rejection())
        verifyNoInteractions(cookieManager)
        assertNoDroppedResponses()
    }
}
