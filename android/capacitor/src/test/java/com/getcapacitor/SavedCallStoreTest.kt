package com.getcapacitor

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.mock

class SavedCallStoreTest {
    private val store = SavedCallStore()

    private fun call(callbackId: String?, pluginId: String? = "P"): PluginCall =
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
        store.savePermissionCall(first)
        store.savePermissionCall(second)
        store.savePermissionCall(other)

        assertSame(first, store.takePermissionCall("A"))
        assertSame(second, store.takePermissionCall("A"))
        assertNull(store.takePermissionCall("A"))
        assertSame(other, store.takePermissionCall("B"))
        assertNull(store.takePermissionCall("unknown"))
    }

    @Test
    fun permissionCallStaysSavedUntilReleased() {
        val call = call("1", "A")
        store.savePermissionCall(call)
        store.takePermissionCall("A")

        assertSame(call, store.get("1"))
    }

    @Test
    fun releasedPermissionCallIsNotHandedOut() {
        store.savePermissionCall(call("1", "A"))
        store.release("1")

        assertNull(store.takePermissionCall("A"))
    }

    @Test
    fun savingANullPermissionCallIsIgnored() {
        store.savePermissionCall(null)

        assertNull(store.takePermissionCall(null))
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
}
