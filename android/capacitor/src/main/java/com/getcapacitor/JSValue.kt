package com.getcapacitor

import org.json.JSONException

/**
 * Represents a single user-data value of any type on the capacitor PluginCall object.
 *
 * @param call The capacitor plugin call, used for accessing the value safely.
 * @param name The name of the property to access.
 */
public class JSValue(call: PluginCall, name: String) {
    /**
     * Returns the coerced but uncasted underlying value.
     */
    public val value: Any? = toValue(call, name)

    /**
     * The value as a string. Throws a NullPointerException when the option is missing; check [value] first when
     * it may be.
     */
    override fun toString(): String = value!!.toString()

    /**
     * Returns the underlying value as a JSObject, or throwing if it cannot.
     *
     * @throws JSONException If the underlying value is not a JSObject.
     */
    public fun toJSObject(): JSObject {
        if (value is JSObject) return value
        throw JSONException("JSValue could not be coerced to JSObject.")
    }

    /**
     * Returns the underlying value as a JSArray, or throwing if it cannot.
     *
     * @throws JSONException If the underlying value is not a JSArray.
     */
    public fun toJSArray(): JSArray {
        if (value is JSArray) return value
        throw JSONException("JSValue could not be coerced to JSArray.")
    }

    /**
     * Returns the underlying value this object represents, coercing it into a capacitor-friendly object if supported.
     */
    private fun toValue(call: PluginCall, name: String): Any? {
        call.getArray(name, null)?.let { return it }
        call.getObject(name, null)?.let { return it }
        call.getString(name, null)?.let { return it }
        return call.data.opt(name)
    }
}
