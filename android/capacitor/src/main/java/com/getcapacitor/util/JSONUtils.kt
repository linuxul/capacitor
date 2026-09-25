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
     * Follows a dotted key path and reads the value sitting at the deepest key.
     *
     * A path made only of dots (".", "..") splits to no parts at all, because every part is empty and
     * trailing empty parts are dropped. There is then no key to read and the default value is returned.
     *
     * @param jsonObject A JSON object to search
     * @param key The key path to follow
     * @param defaultValue A default value to return if the key cannot be found
     * @param read Reads the value out of the deepest object along the path
     * @return The value at the given key path, or the default value
     */
    private fun <T> lookup(jsonObject: JSONObject, key: String, defaultValue: T, read: (JSONObject, String) -> T): T {
        val parts = DOT.split(key)
        val deepestKey = parts.lastOrNull() ?: return defaultValue
        try {
            var o = jsonObject

            // Search until the second to last part of the key
            for (i in 0 until parts.size - 1) {
                o = o.getJSONObject(parts[i])
            }

            return read(o, deepestKey)
        } catch (ignore: JSONException) {
            // value was not found
        }

        return defaultValue
    }

    /**
     * Get a string value from the given JSON object.
     *
     * @param jsonObject A JSON object to search
     * @param key A key to fetch from the JSON object
     * @param defaultValue A default value to return if the key cannot be found
     * @return The value at the given key in the JSON object, or the default value
     */
    public fun getString(jsonObject: JSONObject, key: String, defaultValue: String?): String? =
        // Nullable on purpose: JSObject overrides getString to return null.
        lookup<String?>(jsonObject, key, defaultValue) { o, k -> o.getString(k) } ?: defaultValue

    /**
     * Get a boolean value from the given JSON object.
     *
     * @param jsonObject A JSON object to search
     * @param key A key to fetch from the JSON object
     * @param defaultValue A default value to return if the key cannot be found
     * @return The value at the given key in the JSON object, or the default value
     */
    public fun getBoolean(jsonObject: JSONObject, key: String, defaultValue: Boolean): Boolean =
        lookup(jsonObject, key, defaultValue) { o, k -> o.getBoolean(k) }

    /**
     * Get an int value from the given JSON object.
     *
     * @param jsonObject A JSON object to search
     * @param key A key to fetch from the JSON object
     * @param defaultValue A default value to return if the key cannot be found
     * @return The value at the given key in the JSON object, or the default value
     */
    public fun getInt(jsonObject: JSONObject, key: String, defaultValue: Int): Int =
        lookup(jsonObject, key, defaultValue) { o, k -> o.getInt(k) }

    /**
     * Get a double value from the given JSON object.
     *
     * @param jsonObject A JSON object to search
     * @param key A key to fetch from the JSON object
     * @param defaultValue A default value to return if the key cannot be found
     * @return The value at the given key in the JSON object, or the default value
     */
    public fun getDouble(jsonObject: JSONObject, key: String, defaultValue: Double): Double =
        lookup(jsonObject, key, defaultValue) { o, k -> o.getDouble(k) }

    /**
     * Get a JSON object value from the given JSON object.
     *
     * @param jsonObject A JSON object to search
     * @param key A key to fetch from the JSON object
     * @return The value from the config, if exists. Null if not
     */
    public fun getObject(jsonObject: JSONObject, key: String): JSONObject? =
        lookup<JSONObject?>(jsonObject, key, null) { o, k -> o.getJSONObject(k) }

    /**
     * Get a string array value from the given JSON object.
     *
     * @param jsonObject A JSON object to search
     * @param key A key to fetch from the JSON object
     * @param defaultValue A default value to return if the key cannot be found
     * @return The value at the given key in the JSON object, or the default value
     */
    public fun getArray(jsonObject: JSONObject, key: String, defaultValue: Array<String>?): Array<String>? =
        lookup(jsonObject, key, defaultValue) { o, k ->
            val a = o.getJSONArray(k) ?: return@lookup defaultValue

            Array(a.length()) { i -> a.get(i) as String }
        }
}
