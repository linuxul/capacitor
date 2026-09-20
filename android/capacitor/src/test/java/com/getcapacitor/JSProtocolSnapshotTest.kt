package com.getcapacitor

import android.webkit.WebView
import androidx.webkit.WebViewFeature
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Pins the JS that the native side generates for the WebView and the message format it
 * sends back, since native-bridge.js and @capacitor/core depend on both.
 *
 * Run with UPDATE_SNAPSHOTS=1 to rewrite the files under src/test/resources/snapshots.
 */
class JSProtocolSnapshotTest {
    @get:Rule
    val logs = RecordingLogSink()

    @CapacitorPlugin(name = "Snapshot")
    class SnapshotPlugin : Plugin() {
        @PluginMethod
        fun echo(call: PluginCall) {}

        @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
        fun watch(call: PluginCall) {}

        @PluginMethod(returnType = PluginMethod.RETURN_NONE)
        fun fire(call: PluginCall) {}
    }

    @Test
    fun globalJS() {
        assertSnapshot("global-js.txt", JSExport.getGlobalJS(null, true, false))
    }

    @Test
    fun pluginJS() {
        val handle = PluginHandle(mock<Bridge>(), SnapshotPlugin())
        assertSnapshot("plugin-js.txt", sortMethods(JSExport.getPluginJS(listOf(handle))))
    }

    /**
     * Plugin methods are indexed in a HashMap from Class.getMethods(), neither of which has a
     * guaranteed order, so sort the generated method blocks and headers before comparing.
     */
    private fun sortMethods(pluginJS: String): String {
        val trailer = "\n})(window);\n"
        val headersPrefix = "\nwindow.Capacitor.PluginHeaders = "
        val trailerAt = pluginJS.indexOf(trailer)
        val headersAt = pluginJS.indexOf(headersPrefix)

        // Same split as java.lang.String.split: a lookahead regex, trailing empty parts dropped.
        val blocks = Regex("\n(?=t\\[')").toPattern().split(pluginJS.substring(0, trailerAt))
        blocks.sort(1, blocks.size)

        val headers = JSONArray(pluginJS.substring(headersAt + headersPrefix.length, pluginJS.length - 1))
        val methods = ArrayList<String>()
        val methodHeaders = headers.getJSONObject(0).getJSONArray("methods")
        for (i in 0 until methodHeaders.length()) {
            val method = methodHeaders.getJSONObject(i)
            methods.add(method.getString("name") + " -> " + method.optString("rtype", "(none)"))
        }
        methods.sort()

        return (
            blocks.joinToString("\n") +
                trailer +
                "\nPluginHeaders for " +
                headers.getJSONObject(0).getString("name") +
                ":\n" +
                methods.joinToString("\n")
        )
    }

    @Test
    fun injectedScript() {
        assertSnapshot("injected-script.txt", newInjector("MISC").scriptString)
        assertSnapshot("injected-script-no-misc.txt", newInjector(null).scriptString)
    }

    @Test
    fun successResponseMessage() {
        val result = PluginResult()
        result.put("value", "hello")

        val message = sendResponse(false, result, null)

        assertEquals(6, message.length())
        assertFalse(message.getBoolean("save"))
        assertEquals("42", message.getString("callbackId"))
        assertEquals("Snapshot", message.getString("pluginId"))
        assertEquals("echo", message.getString("methodName"))
        assertTrue(message.getBoolean("success"))
        assertEquals("hello", message.getJSONObject("data").getString("value"))
    }

    @Test
    fun errorResponseMessage() {
        val error = PluginResult()
        error.put("message", "nope")

        val message = sendResponse(true, null, error)

        assertEquals(6, message.length())
        assertTrue(message.getBoolean("save"))
        assertFalse(message.getBoolean("success"))
        assertEquals("nope", message.getJSONObject("error").getString("message"))
    }

    private fun newInjector(miscJS: String?): JSInjector = JSInjector("GLOBAL", "BRIDGE", "PLUGINS", "LOCAL_URL", miscJS)

    private fun sendResponse(keepAlive: Boolean, success: PluginResult?, error: PluginResult?): JSONObject {
        val config = mock<CapConfig>()
        whenever(config.isUsingLegacyBridge).thenReturn(true)
        val bridge = mock<Bridge>()
        whenever(bridge.config).thenReturn(config)
        val webView = mock<WebView>()

        mockStatic(WebViewFeature::class.java).use { feature ->
            feature.`when`<Boolean> { WebViewFeature.isFeatureSupported(anyString()) }.thenReturn(false)

            val handler = MessageHandler(bridge, webView)
            val call = PluginCall(handler, "Snapshot", "42", "echo", JSObject())
            call.keepAlive = keepAlive
            handler.sendResponseMessage(call, success, error)
        }

        val posted = argumentCaptor<Runnable>()
        verify(webView).post(posted.capture())
        posted.firstValue.run()

        val script = argumentCaptor<String>()
        verify(webView).evaluateJavascript(script.capture(), anyOrNull())

        val prefix = "window.Capacitor.fromNative("
        val js = script.firstValue
        assertTrue(js, js.startsWith(prefix) && js.endsWith(")"))
        return JSONObject(js.substring(prefix.length, js.length - 1))
    }

    private fun assertSnapshot(name: String, actual: String) {
        val file = File("src/test/resources/snapshots", name)
        if (System.getenv("UPDATE_SNAPSHOTS") != null) {
            file.parentFile!!.mkdirs()
            Files.write(file.toPath(), actual.toByteArray(StandardCharsets.UTF_8))
            return
        }
        if (!file.exists()) {
            fail("Missing snapshot $file. Run the tests with UPDATE_SNAPSHOTS=1 to create it.")
        }
        assertEquals(String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), actual)
    }
}
