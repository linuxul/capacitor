package com.getcapacitor

import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Replies through the WebMessage listener's JavaScriptReplyProxy, which must be used on the main thread.
 */
class MessageHandlerTest {
    @get:Rule
    val logs = RecordingLogSink()

    private val webView = mock<WebView>()
    private val bridge = mock<Bridge>()
    private val replyProxy = mock<JavaScriptReplyProxy>()

    // What was posted to the WebView's (main) thread, not yet run.
    private val posted = ArrayList<Runnable>()

    @Before
    fun setUp() {
        whenever(bridge.config).thenReturn(mock<CapConfig>())
        whenever(bridge.allowedOriginRules).thenReturn(HashSet())
        whenever(webView.post(any())).thenAnswer {
            posted.add(it.arguments[0] as Runnable)
            true
        }
    }

    private fun runPosted() {
        val tasks = posted.toList()
        posted.clear()
        tasks.forEach { it.run() }
    }

    /**
     * Builds a handler on the WebMessage listener bridge and runs [block] with the listener it registered.
     *
     * WebViewCompat's static initializer calls Uri.parse, which the unit-test android.jar does not implement, so
     * Uri's statics are mocked while WebViewCompat is set up.
     */
    private fun withListenerBridge(block: (WebViewCompat.WebMessageListener) -> Unit) {
        mockStatic(Uri::class.java).use { _ -> mockStatic(WebViewCompat::class.java).close() }
        mockStatic(WebViewFeature::class.java).use { feature ->
            feature.`when`<Boolean> { WebViewFeature.isFeatureSupported(anyString()) }.thenReturn(true)
            mockStatic(WebViewCompat::class.java).use { compat ->
                var listener: WebViewCompat.WebMessageListener? = null
                compat.`when`<Unit> { WebViewCompat.addWebMessageListener(any(), any(), any(), any()) }.thenAnswer {
                    listener = it.arguments[3] as WebViewCompat.WebMessageListener
                    null
                }

                MessageHandler(bridge, webView)

                block(listener!!)
            }
        }
    }

    /** Delivers a plugin call from the page and returns the PluginCall the bridge was asked to run. */
    private fun sendFromPage(listener: WebViewCompat.WebMessageListener, callbackId: String): PluginCall {
        val message = mock<WebMessageCompat>()
        val json = JSONObject().put("type", "message").put("callbackId", callbackId).put("pluginId", "Echo").put("methodName", "echo")
        whenever(message.data).thenReturn(json.put("options", JSONObject()).toString())

        listener.onPostMessage(webView, message, mock<Uri>(), true, replyProxy)

        val call = argumentCaptor<PluginCall>()
        verify(bridge, atLeastOnce()).callPluginMethod(anyOrNull(), anyOrNull(), call.capture())
        return call.lastValue
    }

    @Test
    fun repliesArePostedToTheMainThreadInOrder() {
        withListenerBridge { listener ->
            val first = sendFromPage(listener, "1")
            val second = sendFromPage(listener, "2")

            second.resolve(JSObject().put("n", 2))
            first.resolve(JSObject().put("n", 1))

            // Nothing is sent from the answering thread itself.
            verify(replyProxy, never()).postMessage(anyString())
            assertEquals(2, posted.size)

            runPosted()

            val replies = argumentCaptor<String>()
            inOrder(replyProxy) { verify(replyProxy, times(2)).postMessage(replies.capture()) }
            assertEquals(listOf("2", "1"), replies.allValues.map { JSONObject(it).getString("callbackId") })
            verify(webView, never()).evaluateJavascript(anyString(), anyOrNull())
        }
    }

    @Test
    fun callAnsweredDuringDispatchStillUsesTheReplyProxy() {
        // A plugin that answers before the listener returns, e.g. on a thread that beats it.
        doAnswer { (it.arguments[2] as PluginCall).resolve() }
            .whenever(bridge).callPluginMethod(anyOrNull(), anyOrNull(), any())

        withListenerBridge { listener ->
            sendFromPage(listener, "1")
            runPosted()
        }

        verify(replyProxy).postMessage(anyString())
        verify(webView, never()).evaluateJavascript(anyString(), anyOrNull())
    }

    @Test
    fun replyProxyFailureIsLogged() {
        whenever(replyProxy.postMessage(anyString())).thenThrow(IllegalStateException("gone"))

        withListenerBridge { listener ->
            sendFromPage(listener, "1").resolve()
            runPosted()
        }

        assertTrue(logs.entries.any { it.priority == Log.ERROR && it.message.contains("gone") })
    }
}
