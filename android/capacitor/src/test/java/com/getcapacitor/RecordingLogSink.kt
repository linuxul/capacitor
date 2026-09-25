package com.getcapacitor

import java.util.Collections
import org.junit.rules.ExternalResource

/**
 * JUnit rule that replaces [Logger.sink] for the duration of a test and records what gets logged, so unit tests
 * neither need android.util.Log nor a static mock of [Logger]. Safe to log to from several threads.
 */
class RecordingLogSink :
    ExternalResource(),
    LogSink {
    data class Entry(val priority: Int, val tag: String, val message: String, val throwable: Throwable?)

    val entries: MutableList<Entry> = Collections.synchronizedList(ArrayList())

    private lateinit var previousSink: LogSink
    private var previousLoggingEnabled = true

    override fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
        entries.add(Entry(priority, tag, message, throwable))
    }

    fun count(priority: Int, message: String): Int = synchronized(entries) {
        entries.count { it.priority == priority && it.message == message }
    }

    override fun before() {
        previousSink = Logger.sink
        previousLoggingEnabled = Logger.loggingEnabled
        Logger.sink = this
        Logger.loggingEnabled = true
    }

    override fun after() {
        Logger.sink = previousSink
        Logger.loggingEnabled = previousLoggingEnabled
    }
}
