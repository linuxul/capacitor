package com.getcapacitor

import android.webkit.PermissionRequest
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Every WebView permission prompt must be answered, also when a second one arrives before the first is.
 */
class BridgeWebChromeClientTest {
    @get:Rule
    val logs = RecordingLogSink()

    private val bridge = mock<Bridge>()
    private val permissionLauncher = mock<ActivityResultLauncher<Array<String>>>()
    private lateinit var permissionResults: ActivityResultCallback<Map<String, Boolean>>
    private lateinit var client: BridgeWebChromeClient

    @Before
    @Suppress("UNCHECKED_CAST")
    fun setUp() {
        doAnswer { invocation ->
            if (invocation.arguments[0] is ActivityResultContracts.RequestMultiplePermissions) {
                permissionResults = invocation.arguments[1] as ActivityResultCallback<Map<String, Boolean>>
                permissionLauncher
            } else {
                mock<ActivityResultLauncher<Any>>()
            }
        }.whenever(bridge).registerForActivityResult(any<ActivityResultContract<Any, Any>>(), any<ActivityResultCallback<Any>>())

        client = BridgeWebChromeClient(bridge)
    }

    private fun prompt(resource: String): PermissionRequest {
        val request = mock<PermissionRequest>()
        whenever(request.resources).thenReturn(arrayOf(resource))
        return request
    }

    private fun launchedPermissions(): List<List<String>> {
        val launched = argumentCaptor<Array<String>>()
        verify(permissionLauncher, atLeast(0)).launch(launched.capture())
        return launched.allValues.map { it.toList() }
    }

    @Test
    fun secondPromptWaitsForTheFirstAndBothAreAnswered() {
        val camera = prompt(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val microphone = prompt(PermissionRequest.RESOURCE_AUDIO_CAPTURE)

        client.onPermissionRequest(camera)
        client.onPermissionRequest(microphone)

        // One system request at a time.
        assertEquals(listOf(listOf(CAMERA)), launchedPermissions())

        permissionResults.onActivityResult(mapOf(CAMERA to true))
        verify(camera).grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        assertEquals(listOf(listOf(CAMERA), listOf(MODIFY_AUDIO_SETTINGS, RECORD_AUDIO)), launchedPermissions())

        permissionResults.onActivityResult(mapOf(MODIFY_AUDIO_SETTINGS to true, RECORD_AUDIO to false))
        verify(microphone).deny()
        verify(microphone, never()).grant(any())
    }

    @Test
    fun cancelledRequestDeniesInsteadOfGranting() {
        val camera = prompt(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        client.onPermissionRequest(camera)

        permissionResults.onActivityResult(emptyMap())

        verify(camera).deny()
        verify(camera, never()).grant(any())
    }

    @Test
    fun failedLaunchDeniesAndMovesOn() {
        doThrow(IllegalStateException("not registered")).doAnswer { }.whenever(permissionLauncher).launch(any())
        val camera = prompt(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val microphone = prompt(PermissionRequest.RESOURCE_AUDIO_CAPTURE)

        client.onPermissionRequest(camera)
        client.onPermissionRequest(microphone)

        verify(camera).deny()
        verify(permissionLauncher, times(2)).launch(any())
        permissionResults.onActivityResult(mapOf(MODIFY_AUDIO_SETTINGS to true, RECORD_AUDIO to true))
        verify(microphone).grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
    }

    @Test
    fun promptWithoutRuntimePermissionsIsGrantedAtOnce() {
        val midi = prompt(PermissionRequest.RESOURCE_MIDI_SYSEX)

        client.onPermissionRequest(midi)

        verify(midi).grant(arrayOf(PermissionRequest.RESOURCE_MIDI_SYSEX))
        verify(permissionLauncher, never()).launch(any())
    }

    private companion object {
        const val CAMERA = "android.permission.CAMERA"
        const val MODIFY_AUDIO_SETTINGS = "android.permission.MODIFY_AUDIO_SETTINGS"
        const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    }
}
