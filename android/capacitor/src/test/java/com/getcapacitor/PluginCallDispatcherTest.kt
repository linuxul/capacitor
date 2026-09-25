package com.getcapacitor

import android.util.Log
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/**
 * What the bridge answers when a plugin method fails, and what it does with a call the method keeps alive.
 */
class PluginCallDispatcherTest {
    @get:Rule
    val logs = RecordingLogSink()

    @CapacitorPlugin(name = "Dispatched")
    class DispatchedPlugin : Plugin() {
        @PluginMethod
        fun denied(call: PluginCall): Unit = throw PluginException("Denied", "DENIED", JSObject().put("reason", "user"))

        @PluginMethod
        fun deniedWithCause(call: PluginCall): Unit = throw PluginException("Failed to read", cause = IOException("disk"))

        @PluginMethod
        fun crash(call: PluginCall): Unit = throw IllegalStateException("boom")

        @PluginMethod
        fun resolveThenThrow(call: PluginCall) {
            call.resolve()
            throw PluginException("late")
        }

        @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
        fun watch(call: PluginCall) {
            call.keepAlive = true
        }
    }

    private val handler = mock<MessageHandler>()
    private val plugin = PluginHandle(mock<Bridge>(), DispatchedPlugin())
    private val saved = ArrayList<PluginCall>()
    private var threadAvailable = true

    // Runs posted tasks right away, as long as the "thread" is available.
    private val dispatcher =
        PluginCallDispatcher(
            { task ->
                if (threadAvailable) task.run()
                threadAvailable
            },
            { saved.add(it) }
        )

    private fun dispatch(method: String): PluginCall {
        val call = PluginCall(handler, "Dispatched", "1", method, JSObject())
        dispatcher.dispatch(plugin, method, call)
        return call
    }

    private fun rejection(): JSObject {
        val error = argumentCaptor<PluginResult>()
        verify(handler, times(1)).sendResponseMessage(any(), anyOrNull(), anyOrNull())
        verify(handler).sendResponseMessage(any(), isNull(), error.capture())
        return JSObject(error.firstValue.toString())
    }

    private fun errorLogs(): List<RecordingLogSink.Entry> = logs.entries.filter { it.priority == Log.ERROR }

    @Test
    fun pluginExceptionRejectsWithItsMessageCodeAndData() {
        dispatch("denied")

        val error = rejection()
        assertEquals("Denied", error.getString("message"))
        assertEquals("DENIED", error.getString("code"))
        assertEquals("user", error.getJSObject("data")?.getString("reason"))
        // An expected rejection, like call.reject("Denied"): nothing is logged as an error.
        assertTrue(errorLogs().isEmpty())
    }

    @Test
    fun causeOfAPluginExceptionIsLogged() {
        dispatch("deniedWithCause")

        val error = rejection()
        assertEquals("Failed to read", error.getString("message"))
        assertFalse(error.has("code"))
        assertTrue(errorLogs().any { it.throwable is IOException })
    }

    @Test
    fun otherExceptionsKeepTheGenericRejection() {
        dispatch("crash")

        assertEquals("Error executing plugin method crash", rejection().getString("message"))
        // The exception the method threw is logged, not the reflective wrapper around it.
        assertTrue(errorLogs().any { it.throwable is IllegalStateException })
    }

    @Test
    fun throwingAfterResolvingKeepsTheResolve() {
        dispatch("resolveThenThrow")

        verify(handler, times(1)).sendResponseMessage(any(), anyOrNull(), anyOrNull())
        verify(handler, never()).sendResponseMessage(any(), isNull(), any())
    }

    @Test
    fun missingMethodIsUnimplemented() {
        dispatch("nope")

        assertEquals("UNIMPLEMENTED", rejection().getString("code"))
    }

    @Test
    fun unavailablePluginThreadRejects() {
        threadAvailable = false

        dispatch("watch")

        val error = rejection()
        assertEquals("Plugin thread is unavailable", error.getString("message"))
        assertEquals("UNAVAILABLE", error.getString("code"))
        assertTrue(saved.isEmpty())
    }

    @Test
    fun callKeptAliveByItsMethodIsSaved() {
        val call = dispatch("watch")

        assertEquals(listOf(call), saved)
        verify(handler, never()).sendResponseMessage(any(), anyOrNull(), anyOrNull())
    }
}
