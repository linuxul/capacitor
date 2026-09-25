package com.getcapacitor

import android.util.Log

/**
 * Receives everything [Logger] decides to log.
 *
 * [priority] is one of the android.util.Log priority constants.
 */
public fun interface LogSink {
    public fun log(priority: Int, tag: String, message: String, throwable: Throwable?)
}

/**
 * The default [LogSink]: writes to logcat through android.util.Log.
 */
public object AndroidLogSink : LogSink {
    override fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
        when (priority) {
            Log.VERBOSE -> Log.v(tag, message)
            Log.DEBUG -> Log.d(tag, message)
            Log.INFO -> Log.i(tag, message)
            Log.WARN -> Log.w(tag, message)
            else -> Log.e(tag, message, throwable)
        }
    }
}

public object Logger {
    public const val LOG_TAG_CORE: String = "Capacitor"

    /**
     * Whether anything gets logged. The bridge sets this from the loaded config (`loggingBehavior`);
     * until then everything is logged.
     */
    @Volatile
    public var loggingEnabled: Boolean = true

    /**
     * Where log entries go. Tests replace this with a recording sink.
     */
    @Volatile
    internal var sink: LogSink = AndroidLogSink

    public fun tags(vararg subtags: String?): String {
        if (subtags.isNotEmpty()) {
            return LOG_TAG_CORE + "/" + subtags.joinToString("/")
        }

        return LOG_TAG_CORE
    }

    public fun verbose(message: String?) {
        verbose(LOG_TAG_CORE, message)
    }

    public fun verbose(tag: String?, message: String?) {
        log(Log.VERBOSE, tag, message, null)
    }

    public fun debug(message: String?) {
        debug(LOG_TAG_CORE, message)
    }

    public fun debug(tag: String?, message: String?) {
        log(Log.DEBUG, tag, message, null)
    }

    public fun info(message: String?) {
        info(LOG_TAG_CORE, message)
    }

    public fun info(tag: String?, message: String?) {
        log(Log.INFO, tag, message, null)
    }

    public fun warn(message: String?) {
        warn(LOG_TAG_CORE, message)
    }

    public fun warn(tag: String?, message: String?) {
        log(Log.WARN, tag, message, null)
    }

    @JvmOverloads
    public fun error(message: String?, e: Throwable? = null) {
        error(LOG_TAG_CORE, message, e)
    }

    public fun error(tag: String?, message: String?, e: Throwable?) {
        log(Log.ERROR, tag, message, e)
    }

    public fun shouldLog(): Boolean = loggingEnabled

    // A null tag or message is logged as the text "null", which is what android.util.Log printed for them.
    private fun log(priority: Int, tag: String?, message: String?, throwable: Throwable?) {
        if (!shouldLog()) {
            return
        }

        sink.log(priority, tag ?: "null", message ?: "null", throwable)
    }
}
