package com.getcapacitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

internal object JSExport {
    private const val CATCHALL_OPTIONS_PARAM = "_options"
    private const val CALLBACK_PARAM = "_callback"

    fun getGlobalJS(context: Context?, loggingEnabled: Boolean, isDebug: Boolean): String =
        "window.Capacitor = { DEBUG: $isDebug, isLoggingEnabled: $loggingEnabled, Plugins: {} };"

    fun getMiscFileJS(paths: ArrayList<String>, context: Context): String {
        val lines = ArrayList<String>()

        for (path in paths) {
            try {
                val fileContent = FileUtils.readFileFromAssets(context.assets, "public/$path")
                lines.add(fileContent)
            } catch (ex: IOException) {
                Logger.error("Unable to read public/$path")
            }
        }

        return lines.joinToString("\n")
    }

    fun getPluginJS(plugins: Collection<PluginHandle>): String {
        val lines = ArrayList<String>()
        val pluginArray = JSONArray()

        lines.add("// Begin: Capacitor Plugin JS")
        for (plugin in plugins) {
            lines.add(
                "(function(w) {\n" +
                    "var a = (w.Capacitor = w.Capacitor || {});\n" +
                    "var p = (a.Plugins = a.Plugins || {});\n" +
                    "var t = (p['" +
                    plugin.id +
                    "'] = {});\n" +
                    "t.addListener = function(eventName, callback) {\n" +
                    "  return w.Capacitor.addListener('" +
                    plugin.id +
                    "', eventName, callback);\n" +
                    "}",
            )
            val methods = plugin.methods
            for (method in methods) {
                if (method.name == "addListener" || method.name == "removeListener") {
                    // Don't export add/remove listener, we do that automatically above as they are "special snowflakes"
                    continue
                }
                lines.add(generateMethodJS(plugin, method))
            }

            lines.add("})(window);\n")
            pluginArray.put(createPluginHeader(plugin))
        }

        return lines.joinToString("\n") + "\nwindow.Capacitor.PluginHeaders = " + pluginArray.toString() + ";"
    }

    fun getFilesContent(context: Context, path: String): String {
        val builder = StringBuilder()
        try {
            // Same as the Java original: AssetManager.list returning null throws here.
            val content = context.assets.list(path)!!
            if (content.isNotEmpty()) {
                for (file in content) {
                    if (!file.endsWith(".map")) {
                        builder.append(getFilesContent(context, "$path/$file"))
                    }
                }
            } else {
                return FileUtils.readFileFromAssets(context.assets, path)
            }
        } catch (ex: IOException) {
            Logger.warn("Unable to read file at path $path")
        }
        return builder.toString()
    }

    private fun createPluginHeader(plugin: PluginHandle): JSONObject {
        val pluginObj = JSONObject()
        val methods = plugin.methods
        try {
            val id = plugin.id
            val methodArray = JSONArray()
            pluginObj.put("name", id)

            for (method in methods) {
                methodArray.put(createPluginMethodHeader(method))
            }

            pluginObj.put("methods", methodArray)
        } catch (e: JSONException) {
            // ignore
        }
        return pluginObj
    }

    private fun createPluginMethodHeader(method: PluginMethodHandle): JSONObject {
        val methodObj = JSONObject()

        try {
            methodObj.put("name", method.name)
            if (method.returnType != PluginMethod.RETURN_NONE) {
                methodObj.put("rtype", method.returnType)
            }
        } catch (e: JSONException) {
            // ignore
        }

        return methodObj
    }

    fun getBridgeJS(context: Context): String = getFilesContent(context, "native-bridge.js")

    private fun generateMethodJS(plugin: PluginHandle, method: PluginMethodHandle): String {
        val lines = ArrayList<String>()

        val args = ArrayList<String>()
        // Add the catch all param that will take a full javascript object to pass to the plugin
        args.add(CATCHALL_OPTIONS_PARAM)

        val returnType = method.returnType
        if (returnType == PluginMethod.RETURN_CALLBACK) {
            args.add(CALLBACK_PARAM)
        }

        // Create the method function declaration
        lines.add("t['" + method.name + "'] = function(" + args.joinToString(", ") + ") {")

        when (returnType) {
            PluginMethod.RETURN_NONE ->
                lines.add(
                    "return w.Capacitor.nativeCallback('" +
                        plugin.id +
                        "', '" +
                        method.name +
                        "', " +
                        CATCHALL_OPTIONS_PARAM +
                        ")",
                )
            PluginMethod.RETURN_PROMISE ->
                lines.add(
                    "return w.Capacitor.nativePromise('" + plugin.id + "', '" + method.name + "', " + CATCHALL_OPTIONS_PARAM + ")",
                )
            PluginMethod.RETURN_CALLBACK ->
                lines.add(
                    "return w.Capacitor.nativeCallback('" +
                        plugin.id +
                        "', '" +
                        method.name +
                        "', " +
                        CATCHALL_OPTIONS_PARAM +
                        ", " +
                        CALLBACK_PARAM +
                        ")",
                )
            else -> {
                // TODO: Do something here?
            }
        }

        lines.add("}")

        return lines.joinToString("\n")
    }
}
