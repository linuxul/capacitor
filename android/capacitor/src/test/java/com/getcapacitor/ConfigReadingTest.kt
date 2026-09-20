package com.getcapacitor

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.util.Log
import java.io.IOException
import java.io.InputStream
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ConfigReadingTest {
    @get:Rule
    val logs = RecordingLogSink()

    private val context = mock<Activity>()
    private val assetManager = mock<AssetManager>()
    private val applicationInfo = mock<ApplicationInfo>()

    private fun getTestInputStream(testPath: String): InputStream? = javaClass.classLoader!!.getResourceAsStream(testPath)

    @Before
    fun before() {
        whenever(context.assets).thenReturn(assetManager)
        whenever(context.applicationInfo).thenReturn(applicationInfo)
    }

    @Test
    fun bad() {
        whenever(assetManager.open("capacitor.config.json")).thenReturn(getTestInputStream(BAD_TEST))
        val config = CapConfig.loadDefault(context)
        assertEquals("not a real domain", config.serverUrl)
        assertNull(config.backgroundColor)
        assertFalse(config.isLoggingEnabled)
    }

    @Test
    fun flat() {
        whenever(assetManager.open("capacitor.config.json")).thenReturn(getTestInputStream(FLAT_TEST))
        val config = CapConfig.loadDefault(context)
        assertEquals("level 1 override", config.overriddenUserAgentString)
        assertEquals("level 1 append", config.appendedUserAgentString)
        assertEquals("#ffffff", config.backgroundColor)
        assertFalse(config.isLoggingEnabled)
        assertEquals(1, config.getPluginConfiguration("SplashScreen").getInt("launchShowDuration", 0))
    }

    @Test
    fun hierarchy() {
        whenever(assetManager.open("capacitor.config.json")).thenReturn(getTestInputStream(HIERARCHY_TEST))
        val config = CapConfig.loadDefault(context)
        assertEquals("level 2 override", config.overriddenUserAgentString)
        assertEquals("level 2 append", config.appendedUserAgentString)
        assertEquals("#000000", config.backgroundColor)
        assertFalse(config.isLoggingEnabled)
    }

    @Test
    fun nonJSON() {
        val errText = "Unable to parse capacitor.config.json. Make sure it's valid json"
        whenever(assetManager.open("capacitor.config.json")).thenReturn(getTestInputStream(NONJSON_TEST))
        CapConfig.loadDefault(context)

        // Logged exactly once, as an error, together with the JSONException that caused it.
        val logged = logs.entries.filter { it.priority == Log.ERROR && it.message == errText }
        assertEquals(1, logged.size)
        assertEquals(Logger.LOG_TAG_CORE, logged[0].tag)
        assertTrue(logged[0].throwable is JSONException)
    }

    @Test
    fun missingContextYieldsTheDefaults() {
        val config = CapConfig.loadDefault(null)

        assertEquals(1, logs.count(Log.ERROR, "Capacitor Config could not be created from file. Context must not be null."))
        assertTrue(config.isHTML5Mode)
        assertEquals("localhost", config.hostname)
        assertEquals("https", config.androidScheme)
        assertNull(config.serverUrl)
        assertTrue(config.isLoggingEnabled)
        assertTrue(config.isInitialFocus)
        assertTrue(config.isResolveServiceWorkerRequests)
        assertFalse(config.isMixedContentAllowed)
        assertEquals(Bridge.DEFAULT_ANDROID_WEBVIEW_VERSION, config.minWebViewVersion)
        assertTrue(config.getPluginConfiguration("Anything").isEmpty())
    }

    @Test
    fun unreadableConfigIsLoggedAndTreatedAsEmpty() {
        whenever(assetManager.open("capacitor.config.json")).thenThrow(IOException("missing"))

        val config = CapConfig.loadDefault(context)

        assertEquals(1, logs.count(Log.ERROR, "Unable to load capacitor.config.json. Run npx cap copy first"))
        assertEquals("localhost", config.hostname)
        assertEquals("https", config.androidScheme)
        // loggingBehavior defaults to "debug", and the mocked app is not debuggable.
        assertFalse(config.isLoggingEnabled)
        assertFalse(config.isWebContentsDebuggingEnabled)
    }

    @Test
    fun invalidSchemeFallsBackToHttps() {
        val json = "{\"server\": {\"androidScheme\": \"file\"}}"
        whenever(assetManager.open("capacitor.config.json")).thenReturn(json.byteInputStream())

        val config = CapConfig.loadDefault(context)

        assertEquals("https", config.androidScheme)
        assertEquals(1, logs.count(Log.WARN, "file is not an allowed scheme.  Defaulting to https."))
    }

    @Test
    fun server() {
        whenever(assetManager.open("capacitor.config.json")).thenReturn(getTestInputStream(SERVER_TEST))
        val config = CapConfig.loadDefault(context)
        assertEquals("myhost", config.hostname)
        assertEquals("http://192.168.100.1:2057", config.serverUrl)
        assertEquals("override", config.androidScheme)
    }

    private companion object {
        const val FLAT_TEST = "configs/flat.json"
        const val BAD_TEST = "configs/bad.json"
        const val HIERARCHY_TEST = "configs/hierarchy.json"
        const val NONJSON_TEST = "configs/nonjson.json"
        const val SERVER_TEST = "configs/server.json"
    }
}
