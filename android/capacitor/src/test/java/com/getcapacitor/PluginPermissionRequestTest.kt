package com.getcapacitor

import android.util.Log
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.mockingDetails
import org.mockito.kotlin.whenever

/**
 * requestPermissionsFor, the suspend way to ask for permissions without a @PermissionCallback.
 */
class PluginPermissionRequestTest {
    @get:Rule
    val logs = RecordingLogSink()

    @CapacitorPlugin(
        name = "Requesting",
        permissions = [
            Permission(alias = "camera", strings = [CAMERA]),
            Permission(alias = "location", strings = [FINE_LOCATION, COARSE_LOCATION]),
            Permission(alias = "photos", strings = [])
        ]
    )
    class RequestingPlugin : Plugin() {
        suspend fun request(vararg aliases: String): Map<String, PermissionState> = requestPermissionsFor(*aliases)
    }

    private val bridge = mock<Bridge>()
    private val plugin = RequestingPlugin()

    // Every registerForActivityResult call gets its own launcher, so a launch can be traced to its callback.
    private val registrations = ArrayList<Pair<ActivityResultLauncher<*>, ActivityResultCallback<*>>>()

    private var states =
        mapOf(
            "camera" to PermissionState.PROMPT,
            "location" to PermissionState.PROMPT,
            "photos" to PermissionState.GRANTED
        )

    @Before
    fun setUp() {
        doAnswer { invocation ->
            val launcher = mock<ActivityResultLauncher<Any>>()
            registrations.add(launcher to invocation.getArgument<ActivityResultCallback<*>>(1))
            launcher
        }.whenever(bridge).registerForActivityResult(any<ActivityResultContract<Any, Any>>(), any<ActivityResultCallback<Any>>())
        whenever(bridge.validatePermissions(isNull(), any())).thenReturn(true)
        doAnswer { states }.whenever(bridge).getPermissionStates(plugin)

        PluginHandle(bridge, plugin)
    }

    /** Starts a request; the returned holder is filled when it completes. */
    private fun request(vararg aliases: String): () -> Result<Map<String, PermissionState>>? {
        var result: Result<Map<String, PermissionState>>? = null
        val block: suspend () -> Map<String, PermissionState> = { plugin.request(*aliases) }
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return { result }
    }

    /** The permission strings of each launch, in order. */
    private fun launches(): List<Array<String>> = registrations.flatMap { (launcher, _) ->
        mockingDetails(launcher).invocations.filter { it.method.name == "launch" }.map { it.getArgument<Array<String>>(0) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun answer(result: Map<String, Boolean>) {
        val (_, callback) = registrations.single { (launcher, _) ->
            mockingDetails(launcher).invocations.any { it.method.name == "launch" }
        }
        (callback as ActivityResultCallback<Map<String, Boolean>>).onActivityResult(result)
    }

    @Test
    fun asksForThePermissionsOfTheAliasesAndReturnsTheirStates() {
        val result = request("camera", "location")

        assertEquals(1, launches().size)
        assertEquals(setOf(CAMERA, FINE_LOCATION, COARSE_LOCATION), launches().single().toSet())
        assertNull(result())

        states = states + ("camera" to PermissionState.GRANTED) + ("location" to PermissionState.DENIED)
        answer(mapOf(CAMERA to true, FINE_LOCATION to false, COARSE_LOCATION to false))

        assertEquals(mapOf("camera" to PermissionState.GRANTED, "location" to PermissionState.DENIED), result()?.getOrThrow())
    }

    @Test
    fun aliasesWithoutPermissionStringsAreNotAskedFor() {
        val result = request("photos")

        assertTrue(launches().isEmpty())
        assertEquals(mapOf("photos" to PermissionState.GRANTED), result()?.getOrThrow())
    }

    @Test
    fun aRequestWaitsForTheOneBeingAsked() {
        val first = request("camera")
        val second = request("location")

        assertEquals(1, launches().size)

        answer(mapOf(CAMERA to true))

        assertTrue(first()?.isSuccess == true)
        assertNull(second())
        assertEquals(2, launches().size)
        assertArrayEquals(arrayOf(FINE_LOCATION, COARSE_LOCATION).sortedArray(), launches()[1].sortedArray())

        answer(mapOf(FINE_LOCATION to true, COARSE_LOCATION to true))

        assertTrue(second()?.isSuccess == true)
    }

    @Test
    fun permissionMissingFromTheManifestThrows() {
        whenever(bridge.validatePermissions(isNull(), any())).thenReturn(false)
        whenever(bridge.missingPermissionsMessage(any())).thenReturn("Missing the following permissions in AndroidManifest.xml:\n$CAMERA\n")
        val result = request("camera")

        answer(mapOf(CAMERA to false))

        val error = result()?.exceptionOrNull()
        assertTrue(error is PluginException)
        assertTrue(error?.message ?: "", (error?.message ?: "").contains(CAMERA))
    }

    @Test
    fun noAliasThrows() {
        val error = request()()?.exceptionOrNull()

        assertTrue(error is PluginException)
        assertEquals("No permission alias was provided", error?.message)
        assertTrue(launches().isEmpty())
    }

    @Test
    fun undeclaredAliasesAreLeftOutAndLogged() {
        val result = request("photos", "microphone")

        assertEquals(mapOf("photos" to PermissionState.GRANTED), result()?.getOrThrow())
        assertTrue(logs.entries.any { it.priority == Log.WARN && it.message.contains("[microphone]") })
    }

    @Test
    fun resultThatNoRequestWaitsForIsDropped() {
        request("camera")
        answer(mapOf(CAMERA to true))

        // A second result for the same launcher, as after an activity was recreated.
        answer(mapOf(CAMERA to true))

        assertTrue(logs.entries.any { it.priority == Log.WARN && it.message.contains("no request is waiting for") })
    }

    private companion object {
        const val CAMERA = "android.permission.CAMERA"
        const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
        const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    }
}
