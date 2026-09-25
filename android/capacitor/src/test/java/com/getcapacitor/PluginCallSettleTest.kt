package com.getcapacitor

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
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
 * A call that is not kept alive answers the web layer once; everything after the first response is dropped.
 */
class PluginCallSettleTest {
    @get:Rule
    val logs = RecordingLogSink()

    private val handler = mock<MessageHandler>()

    private fun call(): PluginCall = PluginCall(handler, "Echo", "7", "echo", JSObject())

    private fun droppedResponseWarnings(): List<RecordingLogSink.Entry> = logs.entries.toList().filter {
        it.priority == Log.WARN && it.message.contains("Echo.echo") && it.message.contains("already settled")
    }

    @Test
    fun secondResolveIsDropped() {
        val call = call()

        call.resolve(JSObject().put("n", 1))
        call.resolve(JSObject().put("n", 2))

        val result = argumentCaptor<PluginResult>()
        verify(handler, times(1)).sendResponseMessage(any(), result.capture(), isNull())
        assertEquals(1, JSObject(result.firstValue.toString()).getInt("n"))
        assertEquals(1, droppedResponseWarnings().size)
    }

    @Test
    fun rejectAfterResolveIsDropped() {
        val call = call()

        call.resolve()
        call.reject("too late")

        verify(handler, times(1)).sendResponseMessage(any(), anyOrNull(), anyOrNull())
        verify(handler, never()).sendResponseMessage(any(), isNull(), any())
        assertEquals(1, droppedResponseWarnings().size)
    }

    @Test
    fun everyLaterSettleIsDroppedAfterAReject() {
        val call = call()

        call.reject("first", code = "E1")
        call.reject("second")
        call.unimplemented()
        call.unavailable()
        call.resolve()
        call.errorCallback("legacy")
        call.successCallback(PluginResult())

        val error = argumentCaptor<PluginResult>()
        verify(handler, times(1)).sendResponseMessage(any(), anyOrNull(), anyOrNull())
        verify(handler).sendResponseMessage(any(), isNull(), error.capture())
        assertEquals("first", JSObject(error.firstValue.toString()).getString("message"))
        assertEquals(6, droppedResponseWarnings().size)
    }

    @Test
    fun keptAliveCallResolvesRepeatedly() {
        val call = call()
        call.keepAlive = true

        repeat(3) { call.resolve(JSObject().put("n", it)) }

        verify(handler, times(3)).sendResponseMessage(any(), any(), isNull())
        assertTrue(droppedResponseWarnings().isEmpty())
    }

    @Test
    fun keptAliveCallSettlesOnceAfterItIsLetGo() {
        val call = call()
        call.keepAlive = true
        call.resolve()
        call.resolve()

        call.keepAlive = false
        call.reject("done")
        call.resolve()

        verify(handler, times(2)).sendResponseMessage(any(), anyOrNull(), isNull())
        verify(handler, times(1)).sendResponseMessage(any(), isNull(), any())
        assertEquals(1, droppedResponseWarnings().size)
    }

    @Test
    fun concurrentResponsesSettleOnce() {
        val call = call()
        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) { i ->
                pool.execute {
                    start.await()
                    if (i % 2 == 0) call.resolve() else call.reject("rejected by $i")
                }
            }
            start.countDown()
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }

        verify(handler, times(1)).sendResponseMessage(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun danglingCallbackIdStillSendsNothingThroughSuccessCallback() {
        val call = PluginCall(handler, "Echo", PluginCall.CALLBACK_ID_DANGLING, "echo", JSObject())

        call.successCallback(PluginResult())
        call.resolve()

        // successCallback never answers a dangling call, so the resolve after it is the first response.
        verify(handler, times(1)).sendResponseMessage(any(), anyOrNull(), anyOrNull())
    }
}
