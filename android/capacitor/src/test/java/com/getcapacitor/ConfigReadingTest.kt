package com.getcapacitor

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import java.io.IOException
import java.io.InputStream

class ConfigReadingTest {
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
        try {
            whenever(assetManager.open("capacitor.config.json")).thenReturn(getTestInputStream(BAD_TEST))
            val config = CapConfig.loadDefault(context)
            assertEquals("not a real domain", config.serverUrl)
            assertNull(config.backgroundColor)
            assertFalse(config.isLoggingEnabled())
        } catch (e: IOException) {
            fail()
        }
    }

    @Test
    fun flat() {
        try {
            whenever(assetManager.open("capacitor.config.json")).thenReturn(getTestInputStream(FLAT_TEST))
            val config = CapConfig.loadDefault(context)
            assertEquals("level 1 override", config.overriddenUserAgentString)
            assertEquals("level 1 append", config.appendedUserAgentString)
            assertEquals("#ffffff", config.backgroundColor)
            assertFalse(config.isLoggingEnabled())
            assertEquals(1, config.getPluginConfiguration("SplashScreen").getInt("launchShowDuration", 0))
        } catch (e: IOException) {
            fail()
        }
    }

    @Test
    fun hierarchy() {
        try {
            whenever(assetManager.open("capacitor.config.json")).thenReturn(getTestInputStream(HIERARCHY_TEST))
            val config = CapConfig.loadDefault(context)
            assertEquals("level 2 override", config.overriddenUserAgentString)
            assertEquals("level 2 append", config.appendedUserAgentString)
            assertEquals("#000000", config.backgroundColor)
            assertFalse(config.isLoggingEnabled())
        } catch (e: IOException) {
            fail()
        }
    }

    @Test
    fun nonJSON() {
        try {
            val errText = "Unable to parse capacitor.config.json. Make sure it's valid json"
            whenever(assetManager.open("capacitor.config.json")).thenReturn(getTestInputStream(NONJSON_TEST))
            mockStatic(Logger::class.java).use { logger ->
                CapConfig.loadDefault(context)
                logger.verify({ Logger.error(eq(errText), anyOrNull()) }, times(1))
            }
        } catch (e: IOException) {
            fail()
        }
    }

    @Test
    fun server() {
        try {
            whenever(assetManager.open("capacitor.config.json")).thenReturn(getTestInputStream(SERVER_TEST))
            val config = CapConfig.loadDefault(context)
            assertEquals("myhost", config.hostname)
            assertEquals("http://192.168.100.1:2057", config.serverUrl)
            assertEquals("override", config.androidScheme)
        } catch (e: IOException) {
            fail()
        }
    }

    private companion object {
        const val FLAT_TEST = "configs/flat.json"
        const val BAD_TEST = "configs/bad.json"
        const val HIERARCHY_TEST = "configs/hierarchy.json"
        const val NONJSON_TEST = "configs/nonjson.json"
        const val SERVER_TEST = "configs/server.json"
    }
}
