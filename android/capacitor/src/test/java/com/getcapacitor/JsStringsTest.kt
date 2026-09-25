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
}
