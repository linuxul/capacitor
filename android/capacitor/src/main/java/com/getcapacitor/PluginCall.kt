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
        // PluginResult.put logs and swallows any JSON failure, so there is nothing to catch here.
        msgHandler.sendResponseMessage(this, null, PluginResult().put("message", msg))
    }

    /**
     * Reject the call. Use named arguments when skipping [code], e.g. `reject("failed", ex = e)`.
     */
    @JvmOverloads
    public fun reject(msg: String?, code: String? = null, ex: Exception? = null, data: JSObject? = null) {
        if (ex != null) {
            Logger.error(Logger.tags("Plugin"), msg, ex)
        }

        // A null code removes the key, which is what JSObject.put does with a null value.
        val errorResult = PluginResult().put("message", msg).put("code", code)
        data?.let { errorResult.put("data", it) }

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
    public fun getString(name: String, defaultValue: String? = null): String? = data.opt(name) as? String ?: defaultValue

    @JvmOverloads
    public fun getInt(name: String, defaultValue: Int? = null): Int? = data.opt(name) as? Int ?: defaultValue

    @JvmOverloads
    public fun getLong(name: String, defaultValue: Long? = null): Long? = data.opt(name) as? Long ?: defaultValue

    @JvmOverloads
    public fun getFloat(name: String, defaultValue: Float? = null): Float? = when (val value = data.opt(name)) {
        is Float -> value
        is Double -> value.toFloat()
        is Int -> value.toFloat()
        else -> defaultValue
    }

    @JvmOverloads
    public fun getDouble(name: String, defaultValue: Double? = null): Double? = when (val value = data.opt(name)) {
        is Double -> value
        is Float -> value.toDouble()
        is Int -> value.toDouble()
        else -> defaultValue
    }

    @JvmOverloads
    public fun getBoolean(name: String, defaultValue: Boolean? = null): Boolean? = data.opt(name) as? Boolean ?: defaultValue

    @JvmOverloads
    public fun getObject(name: String, defaultValue: JSObject? = null): JSObject? {
        val value = data.opt(name) as? JSONObject ?: return defaultValue

        return try {
            JSObject.fromJSONObject(value)
        } catch (ex: JSONException) {
            defaultValue
        }
    }

    /**
     * Get a JSONArray and turn it into a JSArray
     */
    @JvmOverloads
    public fun getArray(name: String, defaultValue: JSArray? = null): JSArray? {
        val value = data.opt(name) as? JSONArray ?: return defaultValue

        return try {
            JSArray((0 until value.length()).map { value.get(it) })
        } catch (ex: JSONException) {
            defaultValue
        }
    }

    public fun release(bridge: Bridge) {
        keepAlive = false
        bridge.releaseCall(this)
    }

    public companion object {
        /**
         * A special callback id that indicates there is no matching callback
         * on the client to associate any PluginCall results back to. This is used
         * in the case of an app resuming with saved instance data, for example.
         */
        public const val CALLBACK_ID_DANGLING: String = "-1"
    }
}
