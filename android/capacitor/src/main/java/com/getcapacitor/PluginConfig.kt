package com.getcapacitor

import com.getcapacitor.util.JSONUtils
import org.json.JSONObject

/**
 * Represents the configuration options for plugins used by Capacitor
 *
 * @param configJSON A plugin configuration expressed as a JSON Object
 */
class PluginConfig(
    /**
     * The JSON Object containing the config of the the provided plugin ID.
     */
    val configJSON: JSONObject,
) {
    /**
     * Get a string value for a plugin in the Capacitor config.
     *
     * @param configKey The key of the value to retrieve
     * @return The value from the config, if exists. Null if not
     */
    fun getString(configKey: String): String? = getString(configKey, null)

    /**
     * Get a string value for a plugin in the Capacitor config.
     *
     * @param configKey The key of the value to retrieve
     * @param defaultValue A default value to return if the key does not exist in the config
     * @return The value from the config, if key exists. Default value returned if not
     */
    fun getString(configKey: String, defaultValue: String?): String? = JSONUtils.getString(configJSON, configKey, defaultValue)

    /**
     * Get a boolean value for a plugin in the Capacitor config.
     *
     * @param configKey The key of the value to retrieve
     * @param defaultValue A default value to return if the key does not exist in the config
     * @return The value from the config, if key exists. Default value returned if not
     */
    fun getBoolean(configKey: String, defaultValue: Boolean): Boolean = JSONUtils.getBoolean(configJSON, configKey, defaultValue)

    /**
     * Get an integer value for a plugin in the Capacitor config.
     *
     * @param configKey The key of the value to retrieve
     * @param defaultValue A default value to return if the key does not exist in the config
     * @return The value from the config, if key exists. Default value returned if not
     */
    fun getInt(configKey: String, defaultValue: Int): Int = JSONUtils.getInt(configJSON, configKey, defaultValue)

    /**
     * Get a double value for a plugin in the Capacitor config.
     *
     * @param configKey The key of the value to retrieve
     * @param defaultValue A default value to return if the key does not exist in the config
     * @return The value from the config, if key exists. Default value returned if not
     */
    fun getDouble(configKey: String, defaultValue: Double): Double = JSONUtils.getDouble(configJSON, configKey, defaultValue)

    /**
     * Get a string array value for a plugin in the Capacitor config.
     *
     * @param configKey The key of the value to retrieve
     * @return The value from the config, if exists. Null if not
     */
    fun getArray(configKey: String): Array<String>? = getArray(configKey, null)

    /**
     * Get a string array value for a plugin in the Capacitor config.
     *
     * @param configKey The key of the value to retrieve
     * @param defaultValue A default value to return if the key does not exist in the config
     * @return The value from the config, if key exists. Default value returned if not
     */
    fun getArray(configKey: String, defaultValue: Array<String>?): Array<String>? =
        JSONUtils.getArray(configJSON, configKey, defaultValue)

    /**
     * Get a JSON object value for a plugin in the Capacitor config.
     *
     * @param configKey The key of the value to retrieve
     * @return The value from the config, if exists. Null if not
     */
    fun getObject(configKey: String): JSONObject? = JSONUtils.getObject(configJSON, configKey)

    /**
     * Check if the PluginConfig is empty.
     *
     * @return true if the plugin config has no entries
     */
    fun isEmpty(): Boolean = configJSON.length() == 0
}
