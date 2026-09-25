package com.getcapacitor

import android.util.Log
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
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
 * The permission request helpers on [Plugin] must settle the call on every path, including requests that
 * have nothing to ask the user.
 */
class PluginPermissionFlowTest {
    @get:Rule
    val logs = RecordingLogSink()

    @CapacitorPlugin(
        name = "Flow",
        permissions = [
            Permission(alias = "camera", strings = [CAMERA]),
            Permission(alias = "photos", strings = []),
            Permission(alias = "blank", strings = [""])
        ]
    )
    class FlowPlugin : Plugin() {
        val callbackCalls = ArrayList<PluginCall>()

        fun request(aliases: Array<String>, call: PluginCall, callbackName: String = "done") {
            requestPermissionForAliases(aliases, call, callbackName)
        }

        @PermissionCallback
        private fun done(call: PluginCall) {
            callbackCalls.add(call)
            call.resolve()
        }
    }

    private val bridge = mock<Bridge>()
    private val handler = mock<MessageHandler>()
    private val plugin = FlowPlugin()

    // Every registerForActivityResult call gets its own launcher, so a launch can be traced to its callback.
    private val registrations = ArrayList<Pair<ActivityResultLauncher<*>, ActivityResultCallback<*>>>()

    // What was handed to the main thread, run by the tests with runMainThread().
    private val mainThreadTasks = ArrayList<Runnable>()

    @Before
    fun setUp() {
        doAnswer { mainThreadTasks.add(it.arguments[0] as Runnable) }.whenever(bridge).executeOnMainThread(any())
        doAnswer { invocation ->
            val launcher = mock<ActivityResultLauncher<Any>>()
            registrations.add(launcher to invocation.getArgument<ActivityResultCallback<*>>(1))
            launcher
        }.whenever(bridge).registerForActivityResult(any<ActivityResultContract<Any, Any>>(), any<ActivityResultCallback<Any>>())

        PluginHandle(bridge, plugin)
    }

    private fun call(): PluginCall = PluginCall(handler, "Flow", "1", "request", JSObject())

    private fun requestPermissionsCall(vararg aliases: String): PluginCall =
        PluginCall(handler, "Flow", "1", "requestPermissions", JSObject().put("permissions", JSArray(aliases.toList())))

    private fun runMainThread() {
        val tasks = mainThreadTasks.toList()
        mainThreadTasks.clear()
        tasks.forEach { it.run() }
    }

    private fun launchedLaunchers(): List<ActivityResultLauncher<*>> = registrations.map { it.first }.filter { launcher ->
        mockingDetails(launcher).invocations.any { it.method.name == "launch" }
    }

    private fun rejection(): String? {
        val error = argumentCaptor<PluginResult>()
        verify(handler).sendResponseMessage(any(), isNull(), error.capture())
        return JSObject(error.firstValue.toString()).getString("message")
    }

    @Test
    fun emptyAliasListRejects() {
        plugin.request(emptyArray(), call())

        assertEquals("No permission alias was provided", rejection())
        assertTrue(plugin.callbackCalls.isEmpty())
        assertTrue(launchedLaunchers().isEmpty())
    }

    @Test
    fun aliasWithoutPermissionStringsRunsTheCallbackRightAwayOnTheMainThread() {
        val call = call()

        plugin.request(arrayOf("photos"), call)

        // Like a prompt's result, the callback runs on the main thread.
        assertTrue(plugin.callbackCalls.isEmpty())
        runMainThread()
        assertEquals(listOf(call), plugin.callbackCalls)
        verify(handler, times(1)).sendResponseMessage(any(), anyOrNull(), isNull())
        assertTrue(launchedLaunchers().isEmpty())
        verify(bridge, never()).savePermissionCall(any())
    }

    @Test
    fun emptyPermissionStringCountsAsNoPermission() {
        plugin.request(arrayOf("blank", "photos"), call())
        runMainThread()

        assertEquals(1, plugin.callbackCalls.size)
        assertTrue(launchedLaunchers().isEmpty())
    }

    @Test
    fun undeclaredAliasStillRunsTheCallbackAndIsLogged() {
        plugin.request(arrayOf("nope"), call())
        runMainThread()

        assertEquals(1, plugin.callbackCalls.size)
        assertEquals(1, logs.entries.count { it.priority == Log.WARN && it.message.contains("[nope]") })
    }

    @Test
    fun aliasWithPermissionStringsLaunchesTheRequest() {
        val call = call()

        plugin.request(arrayOf("camera", "photos"), call)

        val launched = launchedLaunchers().single()
        val permissions = argumentCaptor<Any>()
        @Suppress("UNCHECKED_CAST")
        verify(launched as ActivityResultLauncher<Any>).launch(permissions.capture())
        assertEquals(listOf(CAMERA), (permissions.firstValue as Array<*>).toList())
        verify(bridge).savePermissionCall(call)
        // Nothing is settled until the permission result comes back.
        assertTrue(plugin.callbackCalls.isEmpty())
        verify(handler, never()).sendResponseMessage(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun unknownCallbackNameRejectsWhenThereIsNothingToAsk() {
        plugin.request(arrayOf("photos"), call(), "missing")

        assertTrue(rejection()!!.contains("no PermissionCallback method registered for the name: missing"))
        assertTrue(plugin.callbackCalls.isEmpty())
    }

    @Test
    fun unknownCallbackNameRejectsWhenThereIsSomethingToAsk() {
        plugin.request(arrayOf("camera"), call(), "missing")

        assertTrue(rejection()!!.contains("no PermissionCallback method registered for the name: missing"))
        assertTrue(launchedLaunchers().isEmpty())
    }

    @Test
    fun permissionResultRunsTheCallbackWithTheSavedCall() {
        val call = call()
        plugin.request(arrayOf("camera"), call)
        whenever(bridge.getPermissionCall("Flow")).thenReturn(call)
        whenever(bridge.validatePermissions(any(), anyOrNull(), any())).thenReturn(true)

        permissionResultCallback().onActivityResult(mapOf(CAMERA to true))

        assertEquals(listOf(call), plugin.callbackCalls)
        verify(handler, times(1)).sendResponseMessage(any(), anyOrNull(), isNull())
    }

    @Test
    fun requestPermissionsWithOnlyUnknownAliasesRejectsOnce() {
        plugin.requestPermissions(requestPermissionsCall("nope"))

        assertEquals("No valid permission alias was requested of this plugin.", rejection())
        verify(handler, times(1)).sendResponseMessage(any(), anyOrNull(), anyOrNull())
        assertTrue(logs.entries.none { it.message.contains("already settled") })
    }

    @Test
    fun requestPermissionsForAnAliasWithoutStringsResolvesTheStates() {
        plugin.requestPermissions(requestPermissionsCall("photos"))
        runMainThread()

        // checkPermissions, the callback requestPermissions names, reads the states and resolves.
        verify(handler, times(1)).sendResponseMessage(any(), anyOrNull(), isNull())
        assertTrue(launchedLaunchers().isEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    private fun permissionResultCallback(): ActivityResultCallback<Map<String, Boolean>> {
        val launched = launchedLaunchers().single()
        return registrations.single { it.first === launched }.second as ActivityResultCallback<Map<String, Boolean>>
    }

    private companion object {
        const val CAMERA = "android.permission.CAMERA"
    }
}
