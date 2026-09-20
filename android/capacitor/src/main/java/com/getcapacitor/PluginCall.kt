package com.getcapacitor

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Wraps a call from the web layer to native
 */
class PluginCall(
    private val msgHandler: MessageHandler,
    val pluginId: String?,
    val callbackId: String?,
    val methodName: String?,
    val data: JSObject,
) {
    private var keepAlive = false

    fun successCallback(successResult: PluginResult?) {
        if (CALLBACK_ID_DANGLING == callbackId) {
            // don't send back response if the callbackId was "-1"
            return
        }

        msgHandler.sendResponseMessage(this, successResult, null)
    }

    fun resolve(data: JSObject?) {
        // resolve(null) has always produced a response without a "data" key (the Java PluginResult
        // wrapped the null and JSObject.put(key, null) dropped it), i.e. the same message as resolve().
        val result = if (data != null) PluginResult(data) else null
        msgHandler.sendResponseMessage(this, result, null)
    }

    fun resolve() {
        msgHandler.sendResponseMessage(this, null, null)
    }

    fun errorCallback(msg: String?) {
        val errorResult = PluginResult()

        try {
            errorResult.put("message", msg)
        } catch (jsonEx: Exception) {
            Logger.error(Logger.tags("Plugin"), jsonEx.toString(), null)
        }

        msgHandler.sendResponseMessage(this, null, errorResult)
    }

    fun reject(msg: String?, code: String?, ex: Exception?, data: JSObject?) {
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

    fun reject(msg: String?, ex: Exception?, data: JSObject?) {
        reject(msg, null, ex, data)
    }

    fun reject(msg: String?, code: String?, data: JSObject?) {
        reject(msg, code, null, data)
    }

    fun reject(msg: String?, code: String?, ex: Exception?) {
        reject(msg, code, ex, null)
    }

    fun reject(msg: String?, data: JSObject?) {
        reject(msg, null, null, data)
    }

    fun reject(msg: String?, ex: Exception?) {
        reject(msg, null, ex, null)
    }

    fun reject(msg: String?, code: String?) {
        reject(msg, code, null, null)
    }

    fun reject(msg: String?) {
        reject(msg, null, null, null)
    }

    fun unimplemented() {
        unimplemented("not implemented")
    }

    fun unimplemented(msg: String?) {
        reject(msg, "UNIMPLEMENTED", null, null)
    }

    fun unavailable() {
        unavailable("not available")
    }

    fun unavailable(msg: String?) {
        reject(msg, "UNAVAILABLE", null, null)
    }

    fun getString(name: String): String? = getString(name, null)

    fun getString(name: String, defaultValue: String?): String? {
        val value = data.opt(name) ?: return defaultValue

        if (value is String) {
            return value
        }
        return defaultValue
    }

    fun getInt(name: String): Int? = getInt(name, null)

    fun getInt(name: String, defaultValue: Int?): Int? {
        val value = data.opt(name) ?: return defaultValue

        if (value is Int) {
            return value
        }
        return defaultValue
    }

    fun getLong(name: String): Long? = getLong(name, null)

    fun getLong(name: String, defaultValue: Long?): Long? {
        val value = data.opt(name) ?: return defaultValue

        if (value is Long) {
            return value
        }
        return defaultValue
    }

    fun getFloat(name: String): Float? = getFloat(name, null)

    fun getFloat(name: String, defaultValue: Float?): Float? {
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

    fun getDouble(name: String): Double? = getDouble(name, null)

    fun getDouble(name: String, defaultValue: Double?): Double? {
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

    fun getBoolean(name: String): Boolean? = getBoolean(name, null)

    fun getBoolean(name: String, defaultValue: Boolean?): Boolean? {
        val value = data.opt(name) ?: return defaultValue

        if (value is Boolean) {
            return value
        }
        return defaultValue
    }

    fun getObject(name: String): JSObject? = getObject(name, null)

    fun getObject(name: String, defaultValue: JSObject?): JSObject? {
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

    fun getArray(name: String): JSArray? = getArray(name, null)

    /**
     * Get a JSONArray and turn it into a JSArray
     */
    fun getArray(name: String, defaultValue: JSArray?): JSArray? {
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

    /**
     * Indicate that the Bridge should cache this call in order to call
     * it again later. For example, the addListener system uses this to
     * continuously call the call's callback.
     *
     * @param keepAlive whether to keep the callback saved
     */
    fun setKeepAlive(keepAlive: Boolean) {
        this.keepAlive = keepAlive
    }

    fun release(bridge: Bridge) {
        keepAlive = false
        bridge.releaseCall(this)
    }

    /**
     * Gets the keepAlive value of the plugin call
     * @return true if the plugin call is kept alive
     */
    fun isKeptAlive(): Boolean = keepAlive

    internal inner class PluginCallDataTypeException(m: String?) : Exception(m)

    companion object {
        /**
         * A special callback id that indicates there is no matching callback
         * on the client to associate any PluginCall results back to. This is used
         * in the case of an app resuming with saved instance data, for example.
         */
        const val CALLBACK_ID_DANGLING = "-1"
    }
}
