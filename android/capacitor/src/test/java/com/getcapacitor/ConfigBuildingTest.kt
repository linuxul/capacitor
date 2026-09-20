package com.getcapacitor

import android.app.Activity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock

class ConfigBuildingTest {
    @get:Rule
    val logs = RecordingLogSink()

    private val context = mock<Activity>()
    private val pluginConfig = JSONObject()
    private val testPluginObject = JSONObject()
    private val testPluginNestedObject = JSONObject()
    private val testPluginArray = JSONArray()
    private lateinit var config: CapConfig

    @Before
    fun setup() {
        try {
            testPluginNestedObject.put("var10", true)

            testPluginArray.put("5")
            testPluginArray.put("6")
            testPluginArray.put("7")
            testPluginArray.put("8")

            testPluginObject.put("var1", true)
            testPluginObject.put("var2", "hello")
            testPluginObject.put("var3", testPluginNestedObject)
            testPluginObject.put("var4", 2)
            testPluginObject.put("var5", testPluginArray)

            pluginConfig.put(TEST_PLUGIN_NAME, testPluginObject)

            config =
                CapConfig.Builder(context)
                    .setAllowMixedContent(true)
                    .setAllowNavigation(arrayOf("http://www.google.com"))
                    .setAndroidScheme("test")
                    .setCaptureInput(true)
                    .setLoggingEnabled(true)
                    .setHTML5mode(false)
                    .setOverriddenUserAgentString("test-user-agent")
                    .setAppendedUserAgentString("test-append")
                    .setWebContentsDebuggingEnabled(true)
                    .setZoomableWebView(false)
                    .setBackgroundColor("red")
                    .setPluginsConfiguration(pluginConfig)
                    .setServerUrl("http://www.google.com")
                    .setResolveServiceWorkerRequests(false)
                    .create()
        } catch (e: Exception) {
            fail()
        }
    }

    @Test
    fun getCoreConfigValues() {
        assertTrue(config.isMixedContentAllowed)
        assertArrayEquals(arrayOf("http://www.google.com"), config.allowNavigation)
        assertEquals("test", config.androidScheme)
        assertTrue(config.isInputCaptured)
        assertTrue(config.isLoggingEnabled)
        assertFalse(config.isHTML5Mode)
        assertEquals("test-user-agent", config.overriddenUserAgentString)
        assertEquals("test-append", config.appendedUserAgentString)
        assertTrue(config.isWebContentsDebuggingEnabled)
        assertEquals("red", config.backgroundColor)
        assertEquals("http://www.google.com", config.serverUrl)
        assertFalse(config.isResolveServiceWorkerRequests)
    }

    @Test
    fun getPluginString() {
        val testString = config.getPluginConfiguration(TEST_PLUGIN_NAME).getString("var2")
        assertEquals("hello", testString)
    }

    @Test
    fun getPluginBoolean() {
        val testBool = config.getPluginConfiguration(TEST_PLUGIN_NAME).getBoolean("var1", false)
        assertTrue(testBool)
    }

    @Test
    fun getPluginInt() {
        val testInt = config.getPluginConfiguration(TEST_PLUGIN_NAME).getInt("var4", -1)
        assertEquals(2, testInt)
    }

    @Test
    fun getPluginArray() {
        val comparison = arrayOf("5", "6", "7", "8")
        val testArray = config.getPluginConfiguration(TEST_PLUGIN_NAME).getArray("var5")
        assertArrayEquals(comparison, testArray)
    }

    @Test
    fun getPluginObject() {
        val testObject = config.getPluginConfiguration(TEST_PLUGIN_NAME).getObject("var3")
        assertEquals(testPluginNestedObject, testObject)
    }

    private companion object {
        const val TEST_PLUGIN_NAME = "TestPlugin"
    }
}
