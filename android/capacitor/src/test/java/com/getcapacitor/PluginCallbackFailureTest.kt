package com.getcapacitor

import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.mockingDetails
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Permission and activity result callbacks run outside any plugin method, so [Plugin] itself has to turn what
 * they throw into a rejection.
 */
class PluginCallbackFailureTest {
    @get:Rule
    val logs = RecordingLogSink()

    @CapacitorPlugin(name = "Failing", permissions = [Permission(alias = "camera", strings = ["android.permission.CAMERA"])])
    class FailingPlugin : Plugin() {
        val tolerantCalls = ArrayList<PluginCall?>()

        fun request(call: PluginCall, callbackName: String) {
            requestPermissionForAliases(arrayOf("camera"), call, callbackName)
        }

        @PermissionCallback
        private fun explode(call: PluginCall): Unit = throw IllegalStateException("boom")

        @PermissionCallback
        private fun deny(call: PluginCall): Unit = throw PluginException("Denied", "DENIED", JSObject().put("alias", "camera"))

        @PermissionCallback
        private fun resolveThenExplode(call: PluginCall) {
            call.resolve()
            throw IllegalStateException("late boom")
        }

        @PermissionCallback
        private fun tolerant(call: PluginCall?) {
            tolerantCalls.add(call)
        }

        @ActivityCallback
        private fun activityExplode(call: PluginCall, result: ActivityResult): Unit = throw IllegalArgumentException("activity boom")
    }

    private val bridge = mock<Bridge>()
    private val handler = mock<MessageHandler>()
    private val plugin = FailingPlugin()
    private val registrations = ArrayList<Pair<ActivityResultLauncher<*>, ActivityResultCallback<*>>>()

    @Before
    fun setUp() {
        doAnswer { invocation ->
            val launcher = mock<ActivityResultLauncher<Any>>()
            registrations.add(launcher to invocation.getArgument<ActivityResultCallback<*>>(1))
            launcher
        }.whenever(bridge).registerForActivityResult(any<ActivityResultContract<Any, Any>>(), any<ActivityResultCallback<Any>>())
        whenever(bridge.validatePermissions(anyOrNull(), any())).thenReturn(true)

        PluginHandle(bridge, plugin)
    }

    private fun call(): PluginCall = PluginCall(handler, "Failing", "1", "request", JSObject())

    @Suppress("UNCHECKED_CAST")
    private fun <T> launchedCallback(): ActivityResultCallback<T> {
        val launched = registrations.single { (launcher, _) ->
            mockingDetails(launcher).invocations.any { it.method.name == "launch" }
        }
        return launched.second as ActivityResultCallback<T>
    }

    private fun deliverPermissionResult(savedCall: PluginCall?) {
        whenever(bridge.getPermissionCall("Failing")).thenReturn(savedCall)
        launchedCallback<Map<String, Boolean>>().onActivityResult(mapOf("android.permission.CAMERA" to true))
    }

    private fun rejection(): JSObject {
        val error = argumentCaptor<PluginResult>()
        verify(handler).sendResponseMessage(any(), isNull(), error.capture())
        return JSObject(error.firstValue.toString())
    }

    @Test
    fun throwingPermissionCallbackRejectsTheCall() {
        val call = call()
        plugin.request(call, "explode")

        deliverPermissionResult(call)

        assertEquals("boom", rejection().getString("message"))
        verify(handler, times(1)).sendResponseMessage(any(), anyOrNull(), anyOrNull())
        // The cause, not the reflective wrapper, is what reject logs.
        assertTrue(logs.entries.any { it.priority == Log.ERROR && it.throwable is IllegalStateException })
    }

    @Test
    fun pluginExceptionFromACallbackRejectsWithItsCodeAndData() {
        val call = call()
        plugin.request(call, "deny")

        deliverPermissionResult(call)

        val error = rejection()
        assertEquals("Denied", error.getString("message"))
        assertEquals("DENIED", error.getString("code"))
        assertEquals("camera", error.getJSObject("data")?.getString("alias"))
    }

    @Test
    fun callbackThatSettledBeforeThrowingKeepsItsResponse() {
        val call = call()
        plugin.request(call, "resolveThenExplode")

        deliverPermissionResult(call)

        verify(handler, times(1)).sendResponseMessage(any(), anyOrNull(), isNull())
        verify(handler, never()).sendResponseMessage(any(), isNull(), any())
    }

    @Test
    fun throwingActivityCallbackRejectsTheCall() {
        val call = call()
        plugin.startActivityForResult(call, mock<Intent>(), "activityExplode")
        whenever(bridge.getSavedCall("1")).thenReturn(call)

        launchedCallback<ActivityResult>().onActivityResult(ActivityResult(0, null))

        assertEquals("activity boom", rejection().getString("message"))
    }

    @Test
    fun missingSavedCallIsLoggedInsteadOfCrashing() {
        plugin.request(call(), "explode")

        // explode takes a non-null PluginCall, so running it with null fails inside the callback.
        deliverPermissionResult(null)

        verify(handler, never()).sendResponseMessage(any(), anyOrNull(), anyOrNull())
        assertTrue(logs.entries.any { it.priority == Log.WARN && it.message.contains("No saved call for explode") })
        assertTrue(logs.entries.any { it.priority == Log.ERROR && it.message.contains("explode failed without a saved call") })
    }

    @Test
    fun missingSavedCallStillReachesACallbackThatAcceptsNull() {
        plugin.request(call(), "tolerant")

        deliverPermissionResult(null)

        assertEquals(listOf<PluginCall?>(null), plugin.tolerantCalls)
    }

    @Test
    fun missingSavedActivityCallIsLoggedInsteadOfCrashing() {
        plugin.startActivityForResult(call(), mock<Intent>(), "activityExplode")

        launchedCallback<ActivityResult>().onActivityResult(ActivityResult(0, null))

        verify(handler, never()).sendResponseMessage(any(), anyOrNull(), anyOrNull())
        assertTrue(logs.entries.any { it.priority == Log.ERROR && it.message.contains("activityExplode failed without a saved call") })
    }
}
