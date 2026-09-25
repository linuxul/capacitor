package com.getcapacitor

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.MockedConstruction
import org.mockito.MockedStatic
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * `<input type="file" capture>` opens the camera app when there is one, and the file picker otherwise.
 */
class BridgeWebChromeClientFileChooserTest {
    @get:Rule
    val logs = RecordingLogSink()

    private val bridge = mock<Bridge>()
    private val activityLauncher = mock<ActivityResultLauncher<Intent>>()
    private val filePicker = mock<Intent>()
    private val filePathCallback = mock<ValueCallback<Array<Uri>>>()
    private val params = mock<FileChooserParams>()

    // Whether resolveActivity finds a camera app for a capture intent.
    private var cameraApp: ComponentName? = mock()
    private lateinit var intents: MockedConstruction<Intent>
    private lateinit var contextCompat: MockedStatic<ContextCompat>
    private lateinit var client: BridgeWebChromeClient

    @Before
    fun setUp() {
        doAnswer { invocation ->
            val isActivityContract = invocation.arguments[0] is ActivityResultContracts.StartActivityForResult
            if (isActivityContract) activityLauncher else mock<ActivityResultLauncher<Any>>()
        }.whenever(bridge).registerForActivityResult(any<ActivityResultContract<Any, Any>>(), any<ActivityResultCallback<Any>>())
        val activity = mock<AppCompatActivity>()
        whenever(activity.packageManager).thenReturn(mock<PackageManager>())
        whenever(bridge.activity).thenReturn(activity)
        whenever(bridge.context).thenReturn(mock<Context>())

        // The camera permission is granted, so capture goes straight to the camera app.
        contextCompat = mockStatic(ContextCompat::class.java)
        contextCompat.`when`<Int> { ContextCompat.checkSelfPermission(any(), any()) }.thenReturn(PackageManager.PERMISSION_GRANTED)
        intents = mockConstruction(Intent::class.java) { intent, _ -> whenever(intent.resolveActivity(any())).thenAnswer { cameraApp } }

        whenever(params.acceptTypes).thenReturn(arrayOf("video/*"))
        whenever(params.isCaptureEnabled).thenReturn(true)
        whenever(params.mode).thenReturn(FileChooserParams.MODE_OPEN)
        whenever(params.createIntent()).thenReturn(filePicker)

        client = BridgeWebChromeClient(bridge)
    }

    @After
    fun tearDown() {
        if (::intents.isInitialized) intents.close()
        if (::contextCompat.isInitialized) contextCompat.close()
    }

    private fun launched(): List<Intent> {
        val intent = argumentCaptor<Intent>()
        verify(activityLauncher, atLeast(0)).launch(intent.capture())
        return intent.allValues
    }

    @Test
    fun captureOpensTheCameraApp() {
        client.onShowFileChooser(null, filePathCallback, params)

        assertSame(intents.constructed().single(), launched().single())
    }

    @Test
    fun captureWithoutCameraAppOpensTheFilePicker() {
        cameraApp = null

        client.onShowFileChooser(null, filePathCallback, params)

        assertEquals(listOf(filePicker), launched())
    }

    @Test
    fun aCameraAppThatCannotBeStartedFallsBackToTheFilePicker() {
        doAnswer { invocation ->
            if (invocation.arguments[0] !== filePicker) throw mock<ActivityNotFoundException>()
        }.whenever(activityLauncher).launch(any())

        client.onShowFileChooser(null, filePathCallback, params)

        assertEquals(listOf(intents.constructed().single(), filePicker), launched())
    }
}
