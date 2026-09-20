package com.getcapacitor

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LoggerTest {
    @get:Rule
    val logs = RecordingLogSink()

    @Test
    fun tagsJoinSubtagsUnderTheCoreTag() {
        assertEquals("Capacitor", Logger.tags())
        assertEquals("Capacitor/Plugin", Logger.tags("Plugin"))
        assertEquals("Capacitor/A/B", Logger.tags("A", "B"))
    }

    @Test
    fun eachLevelReachesTheSinkWithItsPriorityAndTheCoreTag() {
        Logger.verbose("v")
        Logger.debug("d")
        Logger.info("i")
        Logger.warn("w")
        Logger.error("e")

        assertEquals(
            listOf(Log.VERBOSE to "v", Log.DEBUG to "d", Log.INFO to "i", Log.WARN to "w", Log.ERROR to "e"),
            logs.entries.map { it.priority to it.message },
        )
        assertTrue(logs.entries.all { it.tag == Logger.LOG_TAG_CORE && it.throwable == null })
    }

    @Test
    fun explicitTagAndThrowableArePassedThrough() {
        val cause = IllegalStateException("boom")

        Logger.debug("Capacitor/Custom", "message")
        Logger.error("failed", cause)
        Logger.error("Capacitor/Custom", "failed again", cause)

        assertEquals("Capacitor/Custom", logs.entries[0].tag)
        assertNull(logs.entries[0].throwable)
        assertEquals(Logger.LOG_TAG_CORE, logs.entries[1].tag)
        assertSame(cause, logs.entries[1].throwable)
        assertEquals("Capacitor/Custom", logs.entries[2].tag)
        assertSame(cause, logs.entries[2].throwable)
    }

    @Test
    fun nothingIsLoggedWhileLoggingIsDisabled() {
        Logger.loggingEnabled = false

        Logger.verbose("v")
        Logger.error("e", RuntimeException())

        assertFalse(Logger.shouldLog())
        assertTrue(logs.entries.isEmpty())
    }

    @Test
    fun nullMessageIsLoggedAsText() {
        Logger.warn(null)

        assertEquals("null", logs.entries.single().message)
    }
}
