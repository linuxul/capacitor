package com.getcapacitor

import com.getcapacitor.annotation.CapacitorPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/**
 * Removing every listener must also release the kept-alive addListener calls the bridge saved for them. And a
 * listener needs an event name.
 */
class PluginRemoveAllListenersTest {
    @get:Rule
    val logs = RecordingLogSink()

    @CapacitorPlugin(name = "Events")
    class EventsPlugin : Plugin() {
        fun fire() {
            notifyListeners("a", JSObject())
            notifyListeners("b", JSObject())
        }

        fun listening(): Boolean = hasListeners("a") || hasListeners("b")
    }

    private val handler = mock<MessageHandler>()
    private val bridge = mock<Bridge>()
    private val plugin = EventsPlugin().also { PluginHandle(bridge, it) }

    private fun addListener(callbackId: String, eventName: String): PluginCall =
        PluginCall(handler, "Events", callbackId, "addListener", JSObject().put("eventName", eventName)).also { plugin.addListener(it) }

    @Test
    fun removeAllListenersCallReleasesEveryListenerAndResolves() {
        val first = addListener("1", "a")
        val second = addListener("2", "a")
        val third = addListener("3", "b")
        val removeAll = PluginCall(handler, "Events", "4", "removeAllListeners", JSObject())

        plugin.removeAllListeners(removeAll)

        verify(bridge).releaseCall(first)
        verify(bridge).releaseCall(second)
        verify(bridge).releaseCall(third)
        verify(handler, times(1)).sendResponseMessage(removeAll, null, null)
        assertFalse(plugin.listening())
    }

    @Test
    fun bridgeResetReleasesEveryListener() {
        val first = addListener("1", "a")
        val second = addListener("2", "b")

        plugin.removeAllListeners()

        verify(bridge).releaseCall(first)
        verify(bridge).releaseCall(second)
        plugin.fire()
        verify(handler, never()).sendResponseMessage(any(), any(), isNull())
    }

    @Test
    fun addListenerWithoutAnEventNameRejectsInsteadOfListening() {
        val call = PluginCall(handler, "Events", "1", "addListener", JSObject())

        plugin.addListener(call)

        val error = argumentCaptor<PluginResult>()
        verify(handler).sendResponseMessage(eq(call), isNull(), error.capture())
        assertEquals("Must provide an eventName", JSObject(error.firstValue.toString()).getString("message"))
        assertFalse(call.keepAlive)
        assertFalse(plugin.listening())
    }
}
