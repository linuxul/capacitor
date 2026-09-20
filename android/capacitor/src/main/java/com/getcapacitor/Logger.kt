package com.getcapacitor

import android.text.TextUtils
import android.util.Log

// Every member stays a JVM static for now: the Java tests call them statically and mock them with mockStatic.
object Logger {
    const val LOG_TAG_CORE = "Capacitor"

    @JvmField
    var config: CapConfig? = null

    @JvmStatic
    fun init(config: CapConfig?) {
        Logger.config = config
    }

    @JvmStatic
    fun tags(vararg subtags: String?): String {
        if (subtags.isNotEmpty()) {
            return LOG_TAG_CORE + "/" + TextUtils.join("/", subtags)
        }

        return LOG_TAG_CORE
    }

    @JvmStatic
    fun verbose(message: String?) {
        verbose(LOG_TAG_CORE, message)
    }

    @JvmStatic
    fun verbose(tag: String?, message: String?) {
        if (!shouldLog()) {
            return
        }

        Log.v(tag, message.orNullText())
    }

    @JvmStatic
    fun debug(message: String?) {
        debug(LOG_TAG_CORE, message)
    }

    @JvmStatic
    fun debug(tag: String?, message: String?) {
        if (!shouldLog()) {
            return
        }

        Log.d(tag, message.orNullText())
    }

    @JvmStatic
    fun info(message: String?) {
        info(LOG_TAG_CORE, message)
    }

    @JvmStatic
    fun info(tag: String?, message: String?) {
        if (!shouldLog()) {
            return
        }

        Log.i(tag, message.orNullText())
    }

    @JvmStatic
    fun warn(message: String?) {
        warn(LOG_TAG_CORE, message)
    }

    @JvmStatic
    fun warn(tag: String?, message: String?) {
        if (!shouldLog()) {
            return
        }

        Log.w(tag, message.orNullText())
    }

    @JvmStatic
    fun error(message: String?) {
        error(LOG_TAG_CORE, message, null)
    }

    @JvmStatic
    fun error(message: String?, e: Throwable?) {
        error(LOG_TAG_CORE, message, e)
    }

    @JvmStatic
    fun error(tag: String?, message: String?, e: Throwable?) {
        if (!shouldLog()) {
            return
        }

        Log.e(tag, message, e)
    }

    // android.util.Log.v/d/i/w reject a null message with a NullPointerException; log the text "null" instead.
    private fun String?.orNullText(): String = this ?: "null"

    @JvmStatic
    fun shouldLog(): Boolean {
        val config = config
        return config == null || config.isLoggingEnabled()
    }
}
