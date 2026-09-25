package com.getcapacitor

import android.util.Log
import com.getcapacitor.annotation.CapacitorPlugin
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Suspend plugin methods: how their result answers the call, and on which thread they run and resume. The plugin
 * and main threads are real threads here, so that a resumption from another thread can be observed.
 */
class PluginSuspendMethodTest {
    @get:Rule
    val logs = RecordingLogSink()

    @CapacitorPlugin(name = "Suspending")
    class SuspendingPlugin : Plugin() {
        val threads: MutableList<String> = Collections.synchronizedList(ArrayList())
        val parkedLatch = CountDownLatch(1)

        @Volatile
        var parked: Continuation<Unit>? = null

        private fun recordThread() {
            threads.add(Thread.currentThread().name)
        }

        private suspend fun <T> fromOtherThread(block: () -> T): T = suspendCoroutine { continuation ->
            Thread({ continuation.resumeWith(runCatching(block)) }, "test-callback").start()
        }

        private suspend fun park() = suspendCoroutine { continuation ->
            parked = continuation
            parkedLatch.countDown()
        }

        @PluginMethod
        suspend fun nothing(call: PluginCall) {
            recordThread()
        }

        @PluginMethod
        suspend fun value(call: PluginCall): JSObject = JSObject().put("value", call.getString("value"))

        @PluginMethod
        suspend fun resolvesItself(call: PluginCall) {
            call.resolve(JSObject().put("by", "method"))
        }

        @PluginMethod
        suspend fun rejectsItself(call: PluginCall) {
            call.reject("No", "NO")
        }

        @PluginMethod
        suspend fun denied(call: PluginCall): Unit = throw PluginException("Denied", "DENIED", JSObject().put("reason", "user"))

        @PluginMethod
        suspend fun crash(call: PluginCall): Unit = throw IllegalStateException("boom")

        @PluginMethod
        suspend fun wrongType(call: PluginCall): String = "oops"

        @PluginMethod
        suspend fun afterCallback(call: PluginCall): JSObject {
            recordThread()
            val value = fromOtherThread { "from callback" }
            recordThread()
            return JSObject().put("value", value)
        }

        @PluginMethod
        suspend fun deniedAfterCallback(call: PluginCall) {
            fromOtherThread { throw PluginException("Late", "LATE") }
        }

        @PluginMethod(thread = PluginThread.MAIN)
        suspend fun onMain(call: PluginCall) {
            recordThread()
            fromOtherThread { }
            recordThread()
        }

        @PluginMethod
        suspend fun parks(call: PluginCall): JSObject {
            park()
            return JSObject().put("late", true)
        }

        @PluginMethod
        suspend fun resolvesThenParks(call: PluginCall) {
            call.resolve()
            park()
        }

        @PluginMethod(returnType = PluginMethod.RETURN_NONE)
        suspend fun fireAndForget(call: PluginCall): JSObject = JSObject().put("ignored", true)
    }

    private data class Response(val success: PluginResult?, val error: PluginResult?) {
        val data: JSObject? get() = success?.let { JSObject(it.toString()) }
        val errorData: JSObject get() = JSObject(checkNotNull(error) { "expected an error response" }.toString())
    }

    private val responses = LinkedBlockingQueue<Response>()
    private val handler = mock<MessageHandler>()
    private val plugin = SuspendingPlugin()
    private val handle = PluginHandle(mock<Bridge>(), plugin)

    private val pluginExecutor = Executors.newSingleThreadExecutor { Thread(it, "test-plugin") }
    private val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, "test-main") }

    @Volatile
    private var pluginThreadAvailable = true

    private val dispatcher =
        PluginCallDispatcher(
            { task ->
                if (pluginThreadAvailable) pluginExecutor.execute(task)
                pluginThreadAvailable
            },
            { task ->
                mainExecutor.execute(task)
                true
            },
            { }
        )

    init {
        doAnswer {
            responses.add(Response(it.getArgument(1), it.getArgument(2)))
            null
        }.whenever(handler).sendResponseMessage(any(), anyOrNull(), anyOrNull())
    }

    @After
    fun tearDown() {
        pluginExecutor.shutdownNow()
        mainExecutor.shutdownNow()
    }

    private fun dispatch(method: String, options: JSObject = JSObject()) {
        dispatcher.dispatch(handle, method, PluginCall(handler, "Suspending", "1", method, options))
    }

    private fun awaitResponse(): Response = responses.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no response")

    // Waits for what the plugin and main threads have queued so far.
    private fun drain() {
        pluginExecutor.submit {}.get(5, TimeUnit.SECONDS)
        mainExecutor.submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun assertNoFurtherResponse() {
        drain()
        assertNull(responses.poll())
    }

    private fun assertNoDroppedAnswers() {
        assertTrue(logs.entries.none { it.message.contains("already settled") })
    }

    @Test
    fun returningUnitResolvesWithoutData() {
        dispatch("nothing")

        val response = awaitResponse()
        assertNull(response.error)
        assertNull(response.success)
        assertEquals(listOf("test-plugin"), plugin.threads)
    }

    @Test
    fun returningAJSObjectResolvesWithIt() {
        dispatch("value", JSObject().put("value", "v"))

        assertEquals("v", awaitResponse().data?.getString("value"))
    }

    @Test
    fun methodThatResolvesItselfIsNotAnsweredTwice() {
        dispatch("resolvesItself")

        assertEquals("method", awaitResponse().data?.getString("by"))
        assertNoFurtherResponse()
        assertNoDroppedAnswers()
    }

    @Test
    fun methodThatRejectsItselfIsNotAnsweredTwice() {
        dispatch("rejectsItself")

        assertEquals("NO", awaitResponse().errorData.getString("code"))
        assertNoFurtherResponse()
        assertNoDroppedAnswers()
    }

    @Test
    fun pluginExceptionRejectsWithItsCodeAndData() {
        dispatch("denied")

        val error = awaitResponse().errorData
        assertEquals("Denied", error.getString("message"))
        assertEquals("DENIED", error.getString("code"))
        assertEquals("user", error.getJSObject("data")?.getString("reason"))
    }

    @Test
    fun pluginExceptionAfterSuspendingRejects() {
        dispatch("deniedAfterCallback")

        val error = awaitResponse().errorData
        assertEquals("Late", error.getString("message"))
        assertEquals("LATE", error.getString("code"))
    }

    @Test
    fun otherExceptionsRejectLikePlainMethods() {
        dispatch("crash")

        assertEquals("Error executing plugin method crash", awaitResponse().errorData.getString("message"))
        assertTrue(logs.entries.any { it.priority == Log.ERROR && it.throwable is IllegalStateException })
    }

    @Test
    fun returningAnotherTypeIsRejectedAsAProgrammingError() {
        dispatch("wrongType")

        val message = awaitResponse().errorData.getString("message") ?: ""
        assertTrue(message, message.contains("Suspending.wrongType returned a java.lang.String"))
        assertTrue(message, message.contains("must return Unit or a JSObject"))
    }

    @Test
    fun resumesOnThePluginThreadAfterACallbackFromAnotherThread() {
        dispatch("afterCallback")

        assertEquals("from callback", awaitResponse().data?.getString("value"))
        assertEquals(listOf("test-plugin", "test-plugin"), plugin.threads)
    }

    @Test
    fun mainThreadMethodsStartAndResumeOnTheMainThread() {
        dispatch("onMain")

        assertNull(awaitResponse().error)
        assertEquals(listOf("test-main", "test-main"), plugin.threads)
    }

    @Test
    fun cancellingRejectsRunningCallsAndDropsWhatTheyReturnLater() {
        dispatch("parks")
        assertTrue(plugin.parkedLatch.await(5, TimeUnit.SECONDS))

        dispatcher.cancelRunningCalls()

        assertEquals("The plugin call was cancelled", awaitResponse().errorData.getString("message"))

        plugin.parked!!.resume(Unit)
        assertNoFurtherResponse()
        assertNoDroppedAnswers()
    }

    @Test
    fun cancellingLeavesAnAnsweredCallAlone() {
        dispatch("resolvesThenParks")
        assertTrue(plugin.parkedLatch.await(5, TimeUnit.SECONDS))
        assertNull(awaitResponse().error)

        dispatcher.cancelRunningCalls()
        plugin.parked!!.resume(Unit)

        assertNoFurtherResponse()
        assertNoDroppedAnswers()
    }

    @Test
    fun finishedCallsAreNotCancelled() {
        dispatch("nothing")
        awaitResponse()
        drain()

        dispatcher.cancelRunningCalls()

        assertNoFurtherResponse()
    }

    @Test
    fun unavailablePluginThreadRejects() {
        pluginThreadAvailable = false

        dispatch("nothing")

        val error = awaitResponse().errorData
        assertEquals("Plugin thread is unavailable", error.getString("message"))
        assertEquals("UNAVAILABLE", error.getString("code"))
        assertTrue(plugin.threads.isEmpty())
    }

    @Test
    fun returnNoneMethodsSendNothingWhenTheyReturn() {
        dispatch("fireAndForget")

        assertNoFurtherResponse()
    }
}
