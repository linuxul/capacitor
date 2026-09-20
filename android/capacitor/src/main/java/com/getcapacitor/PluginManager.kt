package com.getcapacitor

import android.content.res.AssetManager
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException

public class PluginManager(private val assetManager: AssetManager) {
    public fun loadPluginClasses(): List<Class<out Plugin>> {
        val pluginsJSON = parsePluginsJSON()
        val pluginList = ArrayList<Class<out Plugin>>()

        try {
            for (i in 0 until pluginsJSON.length()) {
                val pluginJSON = pluginsJSON.getJSONObject(i)
                val classPath = pluginJSON.getString("classpath")
                val c = Class.forName(classPath)
                pluginList.add(c.asSubclass(Plugin::class.java))
            }
        } catch (e: JSONException) {
            throw PluginLoadException("Could not parse capacitor.plugins.json as JSON", e)
        } catch (e: ClassNotFoundException) {
            throw PluginLoadException("Could not find class by class path: " + e.message, e)
        }

        return pluginList
    }

    private fun parsePluginsJSON(): JSONArray {
        val jsonString =
            try {
                FileUtils.readFileFromAssets(assetManager, "capacitor.plugins.json")
            } catch (e: IOException) {
                throw PluginLoadException("Could not load capacitor.plugins.json", e)
            }

        try {
            return JSONArray(jsonString)
        } catch (e: JSONException) {
            throw PluginLoadException("Could not parse capacitor.plugins.json as JSON", e)
        }
    }
}
