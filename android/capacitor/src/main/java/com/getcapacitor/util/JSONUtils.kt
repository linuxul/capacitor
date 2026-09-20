package com.getcapacitor.util

import java.util.regex.Pattern
import org.json.JSONException
import org.json.JSONObject

/**
 * Helper methods for parsing JSON objects.
 */
public object JSONUtils {
    // Pattern.split keeps java.lang.String.split semantics (trailing empty parts dropped).
    private val DOT: Pattern = Pattern.compile("\\.")

    /**
     * Get a string value from the given JSON object.
     *
     * @param jsonObject A JSON object to search
     * @param key A key to fetch from the JSON object
     * @param defaultValue A default value to return if the key cannot be found
     * @return The value at the given key in the JSON object, or the default value
     */
    public fun getString(jsonObject: JSONObject, key: String, defaultValue: String?): String? {
        val k = getDeepestKey(key) ?: return defaultValue
        try {
            val o = getDeepestObject(jsonObject, key)

            // Nullable on purpose: JSObject overrides getString to return null.
            val value: String? = o.getString(k)
            if (value == null) {
                return defaultValue
            }
            return value
        } catch (ignore: JSONException) {
            // value was not found
        }

        return defaultValue
    }

    /**
     * Get a boolean value from the given JSON object.
     *
     * @param jsonObject A JSON object to search
     * @param key A key to fetch from the JSON object
     * @param defaultValue A default value to return if the key cannot be found
     * @return The value at the given key in the JSON object, or the default value
     */
    public fun getBoolean(jsonObject: JSONObject, key: String, defaultValue: Boolean): Boolean {
        val k = getDeepestKey(key) ?: return defaultValue
        try {
            val o = getDeepestObject(jsonObject, key)

            return o.getBoolean(k)
        } catch (ignore: JSONException) {
            // value was not found
        }

        return defaultValue
    }

    /**
     * Get an int value from the given JSON object.
     *
     * @param jsonObject A JSON object to search
     * @param key A key to fetch from the JSON object
     * @param defaultValue A default value to return if the key cannot be found
     * @return The value at the given key in the JSON object, or the default value
     */
    public fun getInt(jsonObject: JSONObject, key: String, defaultValue: Int): Int {
        val k = getDeepestKey(key) ?: return defaultValue
        try {
            val o = getDeepestObject(jsonObject, key)
            return o.getInt(k)
        } catch (ignore: JSONException) {
            // value was not found
        }

        return defaultValue
    }

    /**
     * Get a double value from the given JSON object.
     *
     * @param jsonObject A JSON object to search
     * @param key A key to fetch from the JSON object
     * @param defaultValue A default value to return if the key cannot be found
     * @return The value at the given key in the JSON object, or the default value
     */
    public fun getDouble(jsonObject: JSONObject, key: String, defaultValue: Double): Double {
        val k = getDeepestKey(key) ?: return defaultValue
        try {
            val o = getDeepestObject(jsonObject, key)
            return o.getDouble(k)
        } catch (ignore: JSONException) {
            // value was not found
        }

        return defaultValue
    }

    /**
     * Get a JSON object value from the given JSON object.
     *
     * @param jsonObject A JSON object to search
     * @param key A key to fetch from the JSON object
     * @return The value from the config, if exists. Null if not
     */
    public fun getObject(jsonObject: JSONObject, key: String): JSONObject? {
        val k = getDeepestKey(key) ?: return null
        try {
            val o = getDeepestObject(jsonObject, key)

            return o.getJSONObject(k)
        } catch (ignore: JSONException) {
            // value was not found
        }

        return null
    }

    /**
     * Get a string array value from the given JSON object.
     *
     * @param jsonObject A JSON object to search
     * @param key A key to fetch from the JSON object
     * @param defaultValue A default value to return if the key cannot be found
     * @return The value at the given key in the JSON object, or the default value
     */
    public fun getArray(jsonObject: JSONObject, key: String, defaultValue: Array<String>?): Array<String>? {
        val k = getDeepestKey(key) ?: return defaultValue
        try {
            val o = getDeepestObject(jsonObject, key)

            val a = o.getJSONArray(k) ?: return defaultValue

            return Array(a.length()) { i -> a.get(i) as String }
        } catch (ignore: JSONException) {
            // value was not found
        }

        return defaultValue
    }

    /**
     * Given a JSON key path, gets the deepest key.
     *
     * @param key The key path
     * @return The deepest key, or null when the path has no parts (the lookup then yields the default,
     * as the JSONException raised for a null name did in the Java original)
     */
    private fun getDeepestKey(key: String): String? {
        val parts = DOT.split(key)
        if (parts.isNotEmpty()) {
            return parts[parts.size - 1]
        }

        return null
    }

    /**
     * Given a JSON object and key path, gets the deepest object in the path.
     *
     * @param jsonObject A JSON object
     * @param key The key path to follow
     * @return The deepest object along the key path
     * @throws JSONException Thrown if any JSON errors
     */
    private fun getDeepestObject(jsonObject: JSONObject, key: String): JSONObject {
        val parts = DOT.split(key)
        var o = jsonObject

        // Search until the second to last part of the key
        for (i in 0 until parts.size - 1) {
            val k = parts[i]
            o = o.getJSONObject(k)
        }

        return o
    }
}
