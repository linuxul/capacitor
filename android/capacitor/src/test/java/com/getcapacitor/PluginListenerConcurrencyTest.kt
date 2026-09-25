package com.getcapacitor

import com.getcapacitor.annotation.CapacitorPlugin
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Listeners are added and removed on the bridge thread while plugins notify from their own threads.
 */
class PluginListenerConcurrencyTest {
    @get:Rule
    val logs = RecordingLogSink()

    @CapacitorPlugin(name = "Events")
    class EventsPlugin : Plugin() {
        fun fire(data: JSObject?, retain: Boolean = false) {
            notifyListeners(EVENT, data, retain)
        }

        fun listening(): Boolean = hasListeners(EVENT)
    }

    /** Every response the plugin sends: the listener's callback id and the event's "n". */
    private val deliveries = Collections.synchronizedList(ArrayList<Pair<String?, Int>>())
    private val savedCalls = ConcurrentHashMap<String, PluginCall>()
    private val handler = mock<MessageHandler>(stubOnly = true)
    private val bridge = mock<Bridge>(stubOnly = true)
    private val plugin = EventsPlugin()

    @Before
    fun setUp() {
        doAnswer { invocation ->
            val call = invocation.arguments[0] as PluginCall
            val result = invocation.arguments[1] as PluginResult?
            deliveries.add(call.callbackId to JSObject(result.toString()).getInt("n"))
            null
        }.whenever(handler).sendResponseMessage(any(), anyOrNull(), anyOrNull())
        whenever(bridge.getSavedCall(anyOrNull())).thenAnswer { (it.arguments[0] as String?)?.let { id -> savedCalls[id] } }
        PluginHandle(bridge, plugin)
    }

    private fun addListener(callbackId: String): PluginCall {
        val call = PluginCall(handler, "Events", callbackId, "addListener", JSObject().put("eventName", EVENT))
        plugin.addListener(call)
        // What Bridge.callPluginMethod does with a kept-alive call once the method returns.
        savedCalls[callbackId] = call
        return call
    }

    private fun removeListener(callbackId: String) {
        val options = JSObject().put("eventName", EVENT).put("callbackId", callbackId)
        plugin.removeListener(PluginCall(handler, "Events", "remove-$callbackId", "removeListener", options))
        savedCalls.remove(callbackId)
    }

    private fun event(n: Int): JSObject = JSObject().put("n", n)

    /** Runs [work] on [threads] threads released at the same moment and rethrows the first failure. */
    private fun concurrently(threads: Int = 8, work: (thread: Int) -> Unit) {
        val start = CountDownLatch(1)
        val failures = Collections.synchronizedList(ArrayList<Throwable>())
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) { thread ->
            pool.execute {
                try {
                    start.await()
                    work(thread)
                } catch (t: Throwable) {
                    failures.add(t)
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue("threads did not finish", pool.awaitTermination(30, TimeUnit.SECONDS))
        failures.firstOrNull()?.let { throw AssertionError("a thread failed", it) }
    }

    @Test
    fun addingRemovingAndNotifyingFromManyThreads() {
        concurrently { thread ->
            repeat(500) { i ->
                val callbackId = "$thread-$i"
                addListener(callbackId)
                plugin.fire(event(i), retain = i % 7 == 0)
                plugin.listening()
                removeListener(callbackId)
            }
        }

        assertFalse(plugin.listening())
        assertTrue(savedCalls.isEmpty())
    }

    @Test
    fun retainedEventsGoToTheFirstListenerOnly() {
        plugin.fire(event(1), retain = true)
        plugin.fire(event(2), retain = true)

        addListener("first")
        addListener("second")
        plugin.fire(event(3))

        assertEquals(listOf("first" to 1, "first" to 2, "first" to 3, "second" to 3), deliveries.toList())
    }

    @Test
    fun retainedEventsAreHandedOutExactlyOnceWhenListenersRace() {
        repeat(20) { plugin.fire(event(it), retain = true) }

        concurrently { thread -> addListener("listener-$thread") }

        // One of the racing listeners took the retained events. Each event went out once, to the listeners
        // registered by then, so no listener got one twice.
        val byEvent = deliveries.toList().groupBy({ it.second }, { it.first })
        assertEquals((0 until 20).toSet(), byEvent.keys)
        for ((n, listeners) in byEvent) {
            assertEquals("event $n", listeners.toSet().size, listeners.size)
        }
    }

    private companion object {
        const val EVENT = "change"
    }
}
