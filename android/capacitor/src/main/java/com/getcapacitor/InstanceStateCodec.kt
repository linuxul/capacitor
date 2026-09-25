package com.getcapacitor

import android.os.Bundle
import org.json.JSONException

/**
 * Keeps the call that started an activity for result in the activity's saved instance state, so that the result
 * still reaches its plugin when Android recreated the app while the other activity was in front.
 */
internal object InstanceStateCodec {
    private const val LAST_PLUGIN_ID_KEY = "capacitorLastActivityPluginId"
    private const val LAST_PLUGIN_CALL_METHOD_NAME_KEY = "capacitorLastActivityPluginMethod"
    private const val PLUGIN_CALL_OPTIONS_SAVED_KEY = "capacitorLastPluginCallOptions"
    private const val PLUGIN_CALL_BUNDLE_KEY = "capacitorLastPluginCallBundle"

    /**
     * What [write] stored.
     *
     * @property methodName the method of the call; null when the state held none
     * @property options the options of the call; null when the state held none, or they were not valid JSON
     * @property pluginState what the plugin's `saveInstanceState` returned
     */
    class SavedCall(val pluginId: String, val methodName: String?, val options: JSObject?, val pluginState: Bundle?)

    /**
     * Stores [call] and the state its plugin saved for it ([pluginState]) in [outState].
     */
    fun write(outState: Bundle, call: PluginCall, pluginState: Bundle) {
        outState.putString(LAST_PLUGIN_ID_KEY, call.pluginId)
        outState.putString(LAST_PLUGIN_CALL_METHOD_NAME_KEY, call.methodName)
        outState.putString(PLUGIN_CALL_OPTIONS_SAVED_KEY, call.data.toString())
        outState.putBundle(PLUGIN_CALL_BUNDLE_KEY, pluginState)
    }

    /**
     * The call stored in [savedInstanceState], or null if there is none.
     */
    fun read(savedInstanceState: Bundle): SavedCall? {
        val pluginId = savedInstanceState.getString(LAST_PLUGIN_ID_KEY) ?: return null
        val methodName = savedInstanceState.getString(LAST_PLUGIN_CALL_METHOD_NAME_KEY)
        val optionsJson = savedInstanceState.getString(PLUGIN_CALL_OPTIONS_SAVED_KEY)

        val options =
            if (methodName != null && optionsJson != null) {
                try {
                    JSObject(optionsJson)
                } catch (ex: JSONException) {
                    Logger.error("Unable to restore plugin call, unable to parse persisted JSON object", ex)
                    null
                }
            } else {
                null
            }

        return SavedCall(pluginId, methodName, options, savedInstanceState.getBundle(PLUGIN_CALL_BUNDLE_KEY))
    }
}
