package com.getcapacitor

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Wraps a result for web from calling a native plugin.
 */
class PluginResult
    @JvmOverloads
    constructor(private val json: JSObject = JSObject()) {
        fun put(name: String, value: Boolean): PluginResult = jsonPut(name, value)

        fun put(name: String, value: Double): PluginResult = jsonPut(name, value)

        fun put(name: String, value: Int): PluginResult = jsonPut(name, value)

        fun put(name: String, value: Long): PluginResult = jsonPut(name, value)

        /**
         * Format a date as an ISO string
         */
        fun put(name: String, value: Date): PluginResult {
            val tz = TimeZone.getTimeZone("UTC")
            // Locale.getDefault() is what the single-argument constructor used implicitly.
            val df: DateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.getDefault())
            df.timeZone = tz
            return jsonPut(name, df.format(value))
        }

        fun put(name: String, value: Any?): PluginResult = jsonPut(name, value)

        fun put(name: String, value: PluginResult): PluginResult = jsonPut(name, value.json)

        fun jsonPut(name: String, value: Any?): PluginResult {
            try {
                json.put(name, value)
            } catch (ex: Exception) {
                Logger.error(Logger.tags("Plugin"), "", ex)
            }
            return this
        }

        override fun toString(): String = json.toString()

        /**
         * Return plugin metadata and information about the result, if it succeeded the data, or error information if it didn't.
         * This is used for appRestoredResult, as it's technically a raw data response from a plugin.
         * @return the raw data response from the plugin.
         */
        val wrappedResult: JSObject
            get() {
                val ret = JSObject()
                ret.put("pluginId", json.getString("pluginId"))
                ret.put("methodName", json.getString("methodName"))
                ret.put("success", json.getBoolean("success", false))
                ret.put("data", json.getJSObject("data"))
                ret.put("error", json.getJSObject("error"))
                return ret
            }
    }
