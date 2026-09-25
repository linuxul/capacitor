package com.getcapacitor

import android.view.KeyEvent
import android.webkit.ValueCallback
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doCallRealMethod
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Strings that end up inside evaluated JavaScript must stay string literals whatever they contain.
 */
class JsStringsTest {
    private val hostile =
        listOf(
            "plain",
            "",
            "it's",
            "say \"hi\"",
            "back\\slash\\",
            "line\nbreak\r\n",
            "tab\tand\u0000nul",
            "</script><script>alert(1)</script>",
            "\"); alert(1); (\"",
            "'); alert(1); ('",
            "line separator paragraph",
            "emoji 😀"
        )

    /** Reads [literal] back the way a JavaScript engine would, through the JSON grammar it is a subset of. */
    private fun evaluate(literal: String): String = JSONArray("[$literal]").getString(0)

    @Test
    fun literalRoundTripsAnyString() {
        for (value in hostile) {
            assertEquals(value, evaluate(JsStrings.literal(value)))
        }
    }

    @Test
    fun literalIsOneDoubleQuotedTokenOnOneLine() {
        for (value in hostile) {
            val literal = JsStrings.literal(value)

            assertTrue(literal, literal.startsWith("\"") && literal.endsWith("\""))
            for (lineTerminator in listOf('\n', '\r', ' ', ' ')) {
                assertFalse(literal, lineTerminator in literal)
            }
            // Every quote inside is escaped, so the first unescaped quote after the opening one closes the literal.
            val body = literal.substring(1, literal.length - 1)
            assertFalse(literal, Regex("(^|[^\\\\])(\\\\\\\\)*\"").containsMatchIn(body))
        }
    }

    @Test
    fun nullIsTheNullLiteral() {
        assertEquals("null", JsStrings.literal(null))
    }

    private fun bridgeScripts(block: (Bridge) -> Unit): List<String> {
        val bridge = mock<Bridge>()
        doCallRealMethod().whenever(bridge).triggerJSEvent(anyOrNull(), anyOrNull())
        doCallRealMethod().whenever(bridge).triggerJSEvent(anyOrNull(), anyOrNull(), anyOrNull())

        block(bridge)

        val scripts = argumentCaptor<String>()
        verify(bridge, atLeastOnce()).eval(scripts.capture(), anyOrNull<ValueCallback<String>>())
        return scripts.allValues
    }

    @Test
    fun bridgeQuotesTheStringsItPutsIntoScripts() {
        val scripts =
            bridgeScripts { bridge ->
                bridge.triggerJSEvent("x\"y", "window")
                bridge.triggerJSEvent("resume", "document", "{\"a\":1}")
            }

        assertEquals(
            listOf(
                "window.Capacitor.triggerEvent(${JsStrings.literal("x\"y")}, \"window\")",
                // The data argument is JSON and goes in as it is.
                "window.Capacitor.triggerEvent(\"resume\", \"document\", {\"a\":1})"
            ),
            scripts
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun typedCharactersAreQuoted() {
        val webView = mock<CapacitorWebView>()
        doCallRealMethod().whenever(webView).dispatchKeyEvent(any())
        val event = mock<KeyEvent>()
        whenever(event.action).thenReturn(KeyEvent.ACTION_MULTIPLE)
        whenever(event.characters).thenReturn("it's\n")

        webView.dispatchKeyEvent(event)

        val script = argumentCaptor<String>()
        verify(webView).evaluateJavascript(script.capture(), isNull())
        assertEquals("document.activeElement.value = document.activeElement.value + ${JsStrings.literal("it's\n")};", script.firstValue)
    }

    private fun methodHandle(methodName: String, returnType: String): PluginMethodHandle {
        val method = mock<PluginMethodHandle>()
        whenever(method.name).thenReturn(methodName)
        whenever(method.returnType).thenReturn(returnType)
        return method
    }

    @Test
    fun pluginProxiesQuotePluginAndMethodNames() {
        // A plugin id is any annotation string. Kotlin cannot declare a method whose name has a backslash or a line
        // break, but the JVM allows one, so the handles are mocks.
        val pluginId = "Echo'\\\n\u2028"
        val promiseName = "it's\\a\nmethod\u2028"
        val callbackName = "watch'\\\n\u2028"
        val noneName = "fire'\\\n\u2028"
        // Stubbed before plugin.methods: Mockito cannot stub one mock inside another's thenReturn.
        val methods =
            listOf(
                methodHandle(promiseName, PluginMethod.RETURN_PROMISE),
                methodHandle(callbackName, PluginMethod.RETURN_CALLBACK),
                methodHandle(noneName, PluginMethod.RETURN_NONE)
            )
        val plugin = mock<PluginHandle>()
        whenever(plugin.id).thenReturn(pluginId)
        whenever(plugin.methods).thenReturn(methods)

        val (proxies, headers) = JSExport.getPluginJS(listOf(plugin)).split("\nwindow.Capacitor.PluginHeaders = ")

        // A raw string keeps its backslashes, so this is the JavaScript source itself.
        assertEquals(
            """
            // Begin: Capacitor Plugin JS
            (function(w) {
            var a = (w.Capacitor = w.Capacitor || {});
            var p = (a.Plugins = a.Plugins || {});
            var t = (p["Echo'\\\n\u2028"] = {});
            t.addListener = function(eventName, callback) {
              return w.Capacitor.addListener("Echo'\\\n\u2028", eventName, callback);
            }
            t["it's\\a\nmethod\u2028"] = function(_options) {
            return w.Capacitor.nativePromise("Echo'\\\n\u2028", "it's\\a\nmethod\u2028", _options)
            }
            t["watch'\\\n\u2028"] = function(_options, _callback) {
            return w.Capacitor.nativeCallback("Echo'\\\n\u2028", "watch'\\\n\u2028", _options, _callback)
            }
            t["fire'\\\n\u2028"] = function(_options) {
            return w.Capacitor.nativeCallback("Echo'\\\n\u2028", "fire'\\\n\u2028", _options)
            }
            })(window);
            """.trimIndent() + "\n",
            proxies
        )
        // Read back the way an engine would, the script's string literals are exactly the names: none ends early.
        val literals = Regex("\"(?:[^\"\\\\]|\\\\.)*\"").findAll(proxies).map { evaluate(it.value) }.toSet()
        assertEquals(setOf(pluginId, promiseName, callbackName, noneName), literals)

        // The headers are JSON, a JavaScript literal as they are.
        val header = JSONArray(headers.removeSuffix(";")).getJSONObject(0)
        val methodHeaders = header.getJSONArray("methods")
        assertEquals(pluginId, header.getString("name"))
        assertEquals(
            listOf(promiseName, callbackName, noneName),
            (0 until methodHeaders.length()).map { methodHeaders.getJSONObject(it).getString("name") }
        )
    }
}
