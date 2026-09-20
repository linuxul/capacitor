package com.getcapacitor

import android.content.Context
import com.getcapacitor.Bridge.Companion.DEFAULT_ANDROID_WEBVIEW_VERSION
import com.getcapacitor.Bridge.Companion.DEFAULT_HUAWEI_WEBVIEW_VERSION
import com.getcapacitor.util.JSONUtils
import java.io.File
import java.io.IOException
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads capacitor.config.json into a [CapConfig]. An unreadable or invalid file is logged and treated as an empty one.
 *
 * Top-level keys can be overridden per platform under `android.*`; the platform value wins.
 */
internal class CapConfigParser(private val context: Context) {
    /**
     * @param path optional sub directory of the assets to look for the config in
     */
    fun fromAssets(path: String?): CapConfig = parse(
        readConfig("Unable to load capacitor.config.json. Run npx cap copy first") {
            FileUtils.readFileFromAssets(context.assets, normalizeDirectory(path) + CONFIG_FILE_NAME)
        }
    )

    /**
     * @param path optional directory of the app file-space to look for the config in
     */
    fun fromFile(path: String?): CapConfig = parse(
        readConfig("Unable to load capacitor.config.json.") {
            FileUtils.readFileFromDisk(File(normalizeDirectory(path) + CONFIG_FILE_NAME))
        }
    )

    /**
     * Reads the config file and parses it. An unreadable or invalid file is logged and treated as an empty one.
     *
     * @param ioMessage logged when the file cannot be read
     */
    private fun readConfig(ioMessage: String, read: () -> String): JSONObject = try {
        JSONObject(read())
    } catch (ex: IOException) {
        Logger.error(ioMessage, ex)
        JSONObject()
    } catch (ex: JSONException) {
        Logger.error("Unable to parse capacitor.config.json. Make sure it's valid json", ex)
        JSONObject()
    }

    private fun parse(configJSON: JSONObject): CapConfig {
        val isDebug = CapConfig.isDebuggable(context)
        val defaults = CapConfig()

        val configSchema = JSONUtils.getString(configJSON, "server.androidScheme", defaults.androidScheme) ?: defaults.androidScheme

        // Falls back to "debug" (log in debuggable builds only), also for unknown values.
        val logBehavior =
            JSONUtils.getString(
                configJSON,
                "android.loggingBehavior",
                JSONUtils.getString(configJSON, "loggingBehavior", LOG_BEHAVIOR_DEBUG)
            )

        return CapConfig(
            // Server
            isHTML5Mode = JSONUtils.getBoolean(configJSON, "server.html5mode", defaults.isHTML5Mode),
            serverUrl = JSONUtils.getString(configJSON, "server.url", null),
            hostname = JSONUtils.getString(configJSON, "server.hostname", defaults.hostname),
            errorPath = JSONUtils.getString(configJSON, "server.errorPath", null),
            startPath = JSONUtils.getString(configJSON, "server.appStartPath", null),
            androidScheme = if (CapConfig.validateScheme(configSchema)) configSchema else defaults.androidScheme,
            allowNavigation = JSONUtils.getArray(configJSON, "server.allowNavigation", null),
            // Android
            overriddenUserAgentString =
                JSONUtils.getString(
                    configJSON,
                    "android.overrideUserAgent",
                    JSONUtils.getString(configJSON, "overrideUserAgent", null)
                ),
            appendedUserAgentString =
                JSONUtils.getString(
                    configJSON,
                    "android.appendUserAgent",
                    JSONUtils.getString(configJSON, "appendUserAgent", null)
                ),
            backgroundColor =
                JSONUtils.getString(
                    configJSON,
                    "android.backgroundColor",
                    JSONUtils.getString(configJSON, "backgroundColor", null)
                ),
            isMixedContentAllowed =
                JSONUtils.getBoolean(
                    configJSON,
                    "android.allowMixedContent",
                    JSONUtils.getBoolean(configJSON, "allowMixedContent", defaults.isMixedContentAllowed)
                ),
            configuredMinWebViewVersion = JSONUtils.getInt(configJSON, "android.minWebViewVersion", DEFAULT_ANDROID_WEBVIEW_VERSION),
            configuredMinHuaweiWebViewVersion =
                JSONUtils.getInt(configJSON, "android.minHuaweiWebViewVersion", DEFAULT_HUAWEI_WEBVIEW_VERSION),
            isInputCaptured = JSONUtils.getBoolean(configJSON, "android.captureInput", defaults.isInputCaptured),
            isUsingLegacyBridge = JSONUtils.getBoolean(configJSON, "android.useLegacyBridge", defaults.isUsingLegacyBridge),
            isWebContentsDebuggingEnabled = JSONUtils.getBoolean(configJSON, "android.webContentsDebuggingEnabled", isDebug),
            isZoomableWebView =
                JSONUtils.getBoolean(configJSON, "android.zoomEnabled", JSONUtils.getBoolean(configJSON, "zoomEnabled", false)),
            isResolveServiceWorkerRequests = JSONUtils.getBoolean(configJSON, "android.resolveServiceWorkerRequests", true),
            isLoggingEnabled =
                when (logBehavior?.lowercase(Locale.ROOT)) {
                    LOG_BEHAVIOR_PRODUCTION -> true
                    LOG_BEHAVIOR_NONE -> false
                    else -> isDebug // LOG_BEHAVIOR_DEBUG
                },
            isInitialFocus =
                JSONUtils.getBoolean(
                    configJSON,
                    "android.initialFocus",
                    JSONUtils.getBoolean(configJSON, "initialFocus", defaults.isInitialFocus)
                ),
            // Plugins
            pluginsConfiguration = parsePluginsConfig(JSONUtils.getObject(configJSON, "plugins"))
        )
    }

    companion object {
        private const val CONFIG_FILE_NAME = "capacitor.config.json"

        private const val LOG_BEHAVIOR_NONE = "none"
        private const val LOG_BEHAVIOR_DEBUG = "debug"
        private const val LOG_BEHAVIOR_PRODUCTION = "production"

        /**
         * Null becomes the root; anything else gets a trailing slash so it forms a proper
         * file path when going deeper in the directory.
         */
        private fun normalizeDirectory(path: String?): String = if (path == null) {
            ""
        } else if (path[path.length - 1] != '/') {
            "$path/"
        } else {
            path
        }

        /**
         * Maps plugin ids to their config. Entries that are not JSON objects are skipped.
         */
        fun parsePluginsConfig(pluginsConfig: JSONObject?): Map<String, PluginConfig> {
            val pluginsMap = HashMap<String, PluginConfig>()

            // return an empty map if there is no pluginsConfig json
            if (pluginsConfig == null) {
                return pluginsMap
            }

            val pluginIds = pluginsConfig.keys()

            while (pluginIds.hasNext()) {
                val pluginId = pluginIds.next()

                try {
                    val value = pluginsConfig.getJSONObject(pluginId)
                    pluginsMap[pluginId] = PluginConfig(value)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }

            return pluginsMap
        }
    }
}
