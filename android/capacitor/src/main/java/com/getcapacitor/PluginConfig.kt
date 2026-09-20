package com.getcapacitor

import com.getcapacitor.util.JSONUtils
import org.json.JSONObject

/**
 * Represents the configuration options for plugins used by Capacitor
 *
 * @param configJSON A plugin configuration expressed as a JSON Object
 */
public class PluginConfig internal constructor(
    /**
     * The JSON Object containing the config of the the provided plugin ID.
     */
    public val configJSON: JSONObject,
) {
    /**
     * Get a string value for a plugin in the Capacitor config.
     *
     * @param configKey The key of the value to retrieve
     * @param defaultValue A default value to return if the key does not exist in the config
     * @return The value from the config, if key exists. Default value returned if not
     */
    public fun getString(configKey: String, defaultValue: String? = null): String? = JSONUtils.getString(configJSON, configKey, defaultValue)

    /**
     * Get a boolean value for a plugin in the Capacitor config.
     *
     * @param configKey The key of the value to retrieve
     * @param defaultValue A default value to return if the key does not exist in the config
     * @return The value from the config, if key exists. Default value returned if not
     */
    public fun getBoolean(configKey: String, defaultValue: Boolean): Boolean = JSONUtils.getBoolean(configJSON, configKey, defaultValue)

    /**
     * Get an integer value for a plugin in the Capacitor config.
     *
     * @param configKey The key of the value to retrieve
     * @param defaultValue A default value to return if the key does not exist in the config
     * @return The value from the config, if key exists. Default value returned if not
     */
    public fun getInt(configKey: String, defaultValue: Int): Int = JSONUtils.getInt(configJSON, configKey, defaultValue)

    /**
     * Get a double value for a plugin in the Capacitor config.
     *
     * @param configKey The key of the value to retrieve
     * @param defaultValue A default value to return if the key does not exist in the config
     * @return The value from the config, if key exists. Default value returned if not
     */
    public fun getDouble(configKey: String, defaultValue: Double): Double = JSONUtils.getDouble(configJSON, configKey, defaultValue)

    /**
     * Get a string array value for a plugin in the Capacitor config.
     *
     * @param configKey The key of the value to retrieve
     * @param defaultValue A default value to return if the key does not exist in the config
     * @return The value from the config, if key exists. Default value returned if not
     */
    public fun getArray(configKey: String, defaultValue: Array<String>? = null): Array<String>? =
        JSONUtils.getArray(configJSON, configKey, defaultValue)

    /**
     * Get a JSON object value for a plugin in the Capacitor config.
     *
     * @param configKey The key of the value to retrieve
     * @return The value from the config, if exists. Null if not
     */
    public fun getObject(configKey: String): JSONObject? = JSONUtils.getObject(configJSON, configKey)

    /**
     * Check if the PluginConfig is empty.
     *
     * @return true if the plugin config has no entries
     */
    public fun isEmpty(): Boolean = configJSON.length() == 0
}
