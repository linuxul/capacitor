package com.getcapacitor

import org.json.JSONException
import org.json.JSONObject

/**
 * A wrapper around JSONObject that isn't afraid to do simple
 * JSON put operations without having to throw an exception
 * for every little thing jeez
 */
public open class JSObject : JSONObject {
    public constructor() : super()

    public constructor(json: String) : super(json)

    public constructor(obj: JSONObject, names: Array<String>) : super(obj, names)

    // Deliberately widens JSONObject.getString (declared non-null) to return null instead of throwing.
    @Suppress("WRONG_NULLABILITY_FOR_JAVA_OVERRIDE")
    override fun getString(key: String): String? = getString(key, null)

    public fun getString(key: String, defaultValue: String?): String? {
        try {
            val value = super.getString(key)
            if (!super.isNull(key)) {
                return value
            }
        } catch (ex: JSONException) {
        }
        return defaultValue
    }

    @JvmOverloads
    public fun getInteger(key: String, defaultValue: Int? = null): Int? {
        try {
            return super.getInt(key)
        } catch (e: JSONException) {
        }
        return defaultValue
    }

    public fun getBoolean(key: String, defaultValue: Boolean?): Boolean? {
        try {
            return super.getBoolean(key)
        } catch (e: JSONException) {
        }
        return defaultValue
    }

    /**
     * Fetch boolean from jsonObject
     */
    public fun getBool(key: String): Boolean? = getBoolean(key, null)

    @JvmOverloads
    public fun getJSObject(name: String, defaultValue: JSObject? = null): JSObject? {
        try {
            val obj = get(name)
            if (obj is JSONObject) {
                return fromJSONObject(obj)
            }
        } catch (ex: JSONException) {
        }
        return defaultValue
    }

    override fun put(key: String, value: Boolean): JSObject {
        try {
            super.put(key, value)
        } catch (ex: JSONException) {
        }
        return this
    }

    override fun put(key: String, value: Int): JSObject {
        try {
            super.put(key, value)
        } catch (ex: JSONException) {
        }
        return this
    }

    override fun put(key: String, value: Long): JSObject {
        try {
            super.put(key, value)
        } catch (ex: JSONException) {
        }
        return this
    }

    override fun put(key: String, value: Double): JSObject {
        try {
            super.put(key, value)
        } catch (ex: JSONException) {
        }
        return this
    }

    override fun put(key: String, value: Any?): JSObject {
        try {
            super.put(key, value)
        } catch (ex: JSONException) {
        }
        return this
    }

    public fun put(key: String, value: String?): JSObject {
        try {
            super.put(key, value)
        } catch (ex: JSONException) {
        }
        return this
    }

    public fun putSafe(key: String, value: Any?): JSObject = super.put(key, value) as JSObject

    public companion object {
        /**
         * Convert a pathetic JSONObject into a JSObject
         */
        public fun fromJSONObject(obj: JSONObject): JSObject {
            val keys = ArrayList<String>()
            val keysIter = obj.keys()
            while (keysIter.hasNext()) {
                keys.add(keysIter.next())
            }
            return JSObject(obj, keys.toTypedArray())
        }
    }
}
