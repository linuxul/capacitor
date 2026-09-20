package com.getcapacitor

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Wraps a call from the web layer to native.
 *
 * The functions with default arguments are `@JvmOverloads` so that the short forms, such as
 * `call.getString("x")`, `call.resolve()` and `call.reject("msg")`, stay callable from Java plugins.
 */
public class PluginCall(
    private val msgHandler: MessageHandler,
    public val pluginId: String?,
    public val callbackId: String?,
    public val methodName: String?,
    public val data: JSObject
) {
    /**
     * Indicate that the Bridge should cache this call in order to call
     * it again later. For example, the addListener system uses this to
     * continuously call the call's callback.
     */
    public var keepAlive: Boolean = false

    public fun successCallback(successResult: PluginResult?) {
        if (CALLBACK_ID_DANGLING == callbackId) {
            // don't send back response if the callbackId was "-1"
            return
        }

        msgHandler.sendResponseMessage(this, successResult, null)
    }

    /**
     * Resolve the call, optionally with data.
     *
     * A null [data] sends a response without a "data" key.
     */
    @JvmOverloads
    public fun resolve(data: JSObject? = null) {
        val result = if (data != null) PluginResult(data) else null
        msgHandler.sendResponseMessage(this, result, null)
    }

    public fun errorCallback(msg: String?) {
        val errorResult = PluginResult()

        try {
            errorResult.put("message", msg)
        } catch (jsonEx: Exception) {
            Logger.error(Logger.tags("Plugin"), jsonEx.toString(), null)
        }

        msgHandler.sendResponseMessage(this, null, errorResult)
    }

    /**
     * Reject the call. Use named arguments when skipping [code], e.g. `reject("failed", ex = e)`.
     */
    @JvmOverloads
    public fun reject(msg: String?, code: String? = null, ex: Exception? = null, data: JSObject? = null) {
        val errorResult = PluginResult()

        if (ex != null) {
            Logger.error(Logger.tags("Plugin"), msg, ex)
        }

        try {
            errorResult.put("message", msg)
            errorResult.put("code", code)
            if (null != data) {
                errorResult.put("data", data)
            }
        } catch (jsonEx: Exception) {
            Logger.error(Logger.tags("Plugin"), jsonEx.message, jsonEx)
        }

        msgHandler.sendResponseMessage(this, null, errorResult)
    }

    @JvmOverloads
    public fun unimplemented(msg: String? = "not implemented") {
        reject(msg, "UNIMPLEMENTED")
    }

    @JvmOverloads
    public fun unavailable(msg: String? = "not available") {
        reject(msg, "UNAVAILABLE")
    }

    @JvmOverloads
    public fun getString(name: String, defaultValue: String? = null): String? {
        val value = data.opt(name) ?: return defaultValue

        if (value is String) {
            return value
        }
        return defaultValue
    }

    @JvmOverloads
    public fun getInt(name: String, defaultValue: Int? = null): Int? {
        val value = data.opt(name) ?: return defaultValue

        if (value is Int) {
            return value
        }
        return defaultValue
    }

    @JvmOverloads
    public fun getLong(name: String, defaultValue: Long? = null): Long? {
        val value = data.opt(name) ?: return defaultValue

        if (value is Long) {
            return value
        }
        return defaultValue
    }

    @JvmOverloads
    public fun getFloat(name: String, defaultValue: Float? = null): Float? {
        val value = data.opt(name) ?: return defaultValue

        if (value is Float) {
            return value
        }
        if (value is Double) {
            return value.toFloat()
        }
        if (value is Int) {
            return value.toFloat()
        }
        return defaultValue
    }

    @JvmOverloads
    public fun getDouble(name: String, defaultValue: Double? = null): Double? {
        val value = data.opt(name) ?: return defaultValue

        if (value is Double) {
            return value
        }
        if (value is Float) {
            return value.toDouble()
        }
        if (value is Int) {
            return value.toDouble()
        }
        return defaultValue
    }

    @JvmOverloads
    public fun getBoolean(name: String, defaultValue: Boolean? = null): Boolean? {
        val value = data.opt(name) ?: return defaultValue

        if (value is Boolean) {
            return value
        }
        return defaultValue
    }

    @JvmOverloads
    public fun getObject(name: String, defaultValue: JSObject? = null): JSObject? {
        val value = data.opt(name) ?: return defaultValue

        if (value is JSONObject) {
            try {
                return JSObject.fromJSONObject(value)
            } catch (ex: JSONException) {
                return defaultValue
            }
        }
        return defaultValue
    }

    /**
     * Get a JSONArray and turn it into a JSArray
     */
    @JvmOverloads
    public fun getArray(name: String, defaultValue: JSArray? = null): JSArray? {
        val value = data.opt(name) ?: return defaultValue

        if (value is JSONArray) {
            try {
                val items = ArrayList<Any?>()
                for (i in 0 until value.length()) {
                    items.add(value.get(i))
                }
                val array: Any = items.toTypedArray()
                return JSArray(array)
            } catch (ex: JSONException) {
                return defaultValue
            }
        }
        return defaultValue
    }

    public fun release(bridge: Bridge) {
        keepAlive = false
        bridge.releaseCall(this)
    }

    internal inner class PluginCallDataTypeException(m: String?) : Exception(m)

    public companion object {
        /**
         * A special callback id that indicates there is no matching callback
         * on the client to associate any PluginCall results back to. This is used
         * in the case of an app resuming with saved instance data, for example.
         */
        public const val CALLBACK_ID_DANGLING: String = "-1"
    }
}
