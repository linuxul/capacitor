package com.getcapacitor

import android.net.Uri
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * The plugins of a [Bridge], by the id JavaScript calls them with: the `name` of their [CapacitorPlugin]
 * annotation, or their class name when it has none. Registering a plugin loads it.
 *
 * A plugin that cannot be registered (no annotation, an invalid plugin method, a failing constructor) is logged
 * and left out; the other plugins are not affected. A plugin registered under an id already in use replaces the
 * earlier one.
 */
internal class PluginRegistry(private val bridge: Bridge) {
    private val plugins: MutableMap<String, PluginHandle> = HashMap()

    val handles: Collection<PluginHandle>
        get() = plugins.values

    operator fun get(pluginId: String): PluginHandle? = plugins[pluginId]

    /**
     * Creates and loads a plugin of [pluginClass].
     */
    fun register(pluginClass: Class<out Plugin>) {
        val pluginId = pluginId(pluginClass) ?: return

        try {
            plugins[pluginId] = PluginHandle(bridge, pluginClass)
        } catch (ex: InvalidPluginException) {
            logInvalidPlugin(pluginClass)
        } catch (ex: PluginLoadException) {
            Logger.error("Plugin ${pluginClass.name} failed to load", ex)
        }
    }

    /**
     * Loads [plugin], created by the app.
     */
    fun register(plugin: Plugin) {
        val pluginClass = plugin.javaClass
        val pluginId = pluginId(pluginClass) ?: return

        try {
            plugins[pluginId] = PluginHandle(bridge, plugin)
        } catch (ex: InvalidPluginException) {
            logInvalidPlugin(pluginClass)
        }
    }

    fun forEach(action: (Plugin) -> Unit) {
        for (handle in plugins.values) {
            action(handle.instance)
        }
    }

    /**
     * The answer of the first plugin whose [Plugin.shouldOverrideLoad] has one for [url], or null.
     */
    fun shouldOverrideLoad(url: Uri): Boolean? {
        for (handle in plugins.values) {
            handle.instance.shouldOverrideLoad(url)?.let { return it }
        }
        return null
    }

    private fun pluginId(pluginClass: Class<out Plugin>): String? {
        val annotation = pluginClass.getAnnotation(CapacitorPlugin::class.java)
        if (annotation == null) {
            Logger.error("Plugin doesn't have the @CapacitorPlugin annotation. Please add it")
            return null
        }

        val pluginId = annotation.name.ifEmpty { pluginClass.simpleName }
        Logger.debug("Registering plugin instance: $pluginId")
        return pluginId
    }

    private fun logInvalidPlugin(pluginClass: Class<out Plugin>) {
        Logger.error(
            "Plugin ${pluginClass.name} is invalid. Ensure the @CapacitorPlugin annotation exists on the plugin class and" +
                " the class extends Plugin"
        )
    }
}
