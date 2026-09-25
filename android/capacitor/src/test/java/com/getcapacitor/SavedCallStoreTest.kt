package com.getcapacitor

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class SavedCallStoreTest {
    private val store = SavedCallStore()

    private fun call(callbackId: String, pluginId: String = "P"): PluginCall =
        PluginCall(mock<MessageHandler>(), pluginId, callbackId, "method", JSObject())

    @Test
    fun savedCallIsFoundByCallbackIdUntilReleased() {
        val call = call("1")
        store.save(call)

        assertSame(call, store.get("1"))

        store.release("1")
        assertNull(store.get("1"))
    }

    @Test
    fun nullOrUnknownCallbackIdYieldsNothing() {
        store.save(call("1"))

        assertNull(store.get(null))
        assertNull(store.get("2"))
    }

    @Test
    fun savingUnderTheSameCallbackIdReplacesTheCall() {
        val first = call("1")
        val second = call("1")
        store.save(first)
        store.save(second)

        assertSame(second, store.get("1"))
    }

    @Test
    fun resetForgetsSavedCallsButKeepsTheLastActivityCall() {
        val activityCall = call("9")
        store.save(call("1"))
        store.setLastActivityCall(activityCall)

        store.reset()

        assertNull(store.get("1"))
        assertSame(activityCall, store.peekLastActivityCall())
    }

    @Test
    fun permissionCallsComeBackInOrderPerPlugin() {
        val first = call("1", "A")
        val second = call("2", "A")
        val other = call("3", "B")
        store.savePermissionCall(first, "cb")
        store.savePermissionCall(second, "cb")
        store.savePermissionCall(other, "cb")

        assertSame(first, store.takePermissionCall("A", "cb"))
        assertSame(second, store.takePermissionCall("A", "cb"))
        assertNull(store.takePermissionCall("A", "cb"))
        assertSame(other, store.takePermissionCall("B", "cb"))
        assertNull(store.takePermissionCall("unknown", "cb"))
    }

    @Test
    fun permissionResultsGoToTheCallsWaitingOnTheirCallback() {
        // getPhoto waits on the camera prompt; requestPermissions starts a second request, which Android answers
        // first. Its result must not answer getPhoto.
        val photo = call("1", "A")
        val request = call("2", "A")
        store.savePermissionCall(photo, "cameraPermissionsCallback")
        store.savePermissionCall(request, "checkPermissions")

        assertSame(request, store.takePermissionCall("A", "checkPermissions"))
        assertNull(store.takePermissionCall("A", "checkPermissions"))
        assertSame(photo, store.takePermissionCall("A", "cameraPermissionsCallback"))
    }

    @Test
    fun permissionCallStaysSavedUntilReleased() {
        val call = call("1", "A")
        store.savePermissionCall(call, "cb")
        store.takePermissionCall("A", "cb")

        assertSame(call, store.get("1"))
    }

    @Test
    fun releasedPermissionCallIsNotHandedOut() {
        store.savePermissionCall(call("1", "A"), "cb")
        store.release("1")

        assertNull(store.takePermissionCall("A", "cb"))
    }

    @Test
    fun releasingANullOrUnknownCallbackIdIsIgnored() {
        val call = call("1")
        store.save(call)

        store.release(null)
        store.release("2")

        assertSame(call, store.get("1"))
    }

    @Test
    fun lastActivityCallIsClearedByTakingIt() {
        val call = call("1")
        store.setLastActivityCall(call)

        assertSame(call, store.peekLastActivityCall())
        assertSame(call, store.takeLastActivityCall())
        assertNull(store.takeLastActivityCall())
        assertNull(store.peekLastActivityCall())
    }

    @Test
    fun concurrentPermissionCallsAreEachHandedOutOnce() {
        val threads = 8
        val perThread = 500
        val handler = mock<MessageHandler>()
        val taken = Collections.synchronizedList(ArrayList<PluginCall>())
        val start = CountDownLatch(1)
        val failures = Collections.synchronizedList(ArrayList<Throwable>())
        val pool = Executors.newFixedThreadPool(threads * 2)

        repeat(threads) { thread ->
            pool.execute {
                try {
                    start.await()
                    repeat(perThread) { i ->
                        val call = PluginCall(handler, "P", "$thread-$i", "method", JSObject())
                        store.savePermissionCall(call, "cb")
                        store.save(PluginCall(handler, "Q", "kept-$thread-$i", "method", JSObject()))
                        store.release("kept-$thread-$i")
                    }
                } catch (t: Throwable) {
                    failures.add(t)
                }
            }
            pool.execute {
                try {
                    start.await()
                    var misses = 0
                    while (misses < 10_000) {
                        val call = store.takePermissionCall("P", "cb")
                        if (call == null) {
                            misses++
                        } else {
                            taken.add(call)
                            store.release(call.callbackId)
                        }
                    }
                } catch (t: Throwable) {
                    failures.add(t)
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertTrue(failures.toString(), failures.isEmpty())

        // Whatever the takers missed while the savers were still running is still queued.
        while (true) {
            taken.add(store.takePermissionCall("P", "cb") ?: break)
        }
        assertEquals(threads * perThread, taken.size)
        assertEquals(taken.size, taken.map { it.callbackId }.toSet().size)
    }
}
