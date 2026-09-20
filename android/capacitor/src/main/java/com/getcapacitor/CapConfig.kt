package com.getcapacitor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import com.getcapacitor.Bridge.Companion.CAPACITOR_HTTPS_SCHEME
import com.getcapacitor.Bridge.Companion.DEFAULT_ANDROID_WEBVIEW_VERSION
import com.getcapacitor.Bridge.Companion.DEFAULT_HUAWEI_WEBVIEW_VERSION
import com.getcapacitor.Bridge.Companion.MINIMUM_ANDROID_WEBVIEW_VERSION
import com.getcapacitor.Bridge.Companion.MINIMUM_HUAWEI_WEBVIEW_VERSION
import com.getcapacitor.util.JSONUtils
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Represents the configuration options for Capacitor
 */
class CapConfig
    /**
     * Constructs an empty config file.
     */
    private constructor() {
        // Server Config
        private var html5mode = true
        var serverUrl: String? = null
            private set
        var hostname: String? = "localhost"
            private set
        var androidScheme: String = CAPACITOR_HTTPS_SCHEME
            private set
        var allowNavigation: Array<String>? = null
            private set

        // Android Config
        var overriddenUserAgentString: String? = null
            private set
        var appendedUserAgentString: String? = null
            private set
        var backgroundColor: String? = null
            private set
        private var allowMixedContent = false
        private var captureInput = false
        private var webContentsDebuggingEnabled = false
        private var loggingEnabled = true
        private var initialFocus = true
        private var useLegacyBridge = false
        private var configuredMinWebViewVersion = DEFAULT_ANDROID_WEBVIEW_VERSION
        private var configuredMinHuaweiWebViewVersion = DEFAULT_HUAWEI_WEBVIEW_VERSION
        var errorPath: String? = null
            private set
        private var zoomableWebView = false
        private var resolveServiceWorkerRequests = true

        // Embedded
        var startPath: String? = null
            private set

        // Plugins
        private var pluginsConfiguration: Map<String, PluginConfig>? = null

        // Config Object JSON (legacy)
        private var configJSON = JSONObject()

        /**
         * Loads a Capacitor Configuration JSON file into a Capacitor Configuration object.
         * An optional path string can be provided to look for the config in a subdirectory path.
         */
        private fun loadConfigFromAssets(assetManager: AssetManager, path: String?) {
            val dir = normalizeDirectory(path)

            try {
                val jsonString = FileUtils.readFileFromAssets(assetManager, dir + "capacitor.config.json")
                configJSON = JSONObject(jsonString)
            } catch (ex: IOException) {
                Logger.error("Unable to load capacitor.config.json. Run npx cap copy first", ex)
            } catch (ex: JSONException) {
                Logger.error("Unable to parse capacitor.config.json. Make sure it's valid json", ex)
            }
        }

        /**
         * Loads a Capacitor Configuration JSON file into a Capacitor Configuration object.
         * An optional path string can be provided to look for the config in a subdirectory path.
         */
        private fun loadConfigFromFile(path: String?) {
            val dir = normalizeDirectory(path)

            try {
                val configFile = File(dir + "capacitor.config.json")
                val jsonString = FileUtils.readFileFromDisk(configFile)
                configJSON = JSONObject(jsonString)
            } catch (ex: JSONException) {
                Logger.error("Unable to parse capacitor.config.json. Make sure it's valid json", ex)
            } catch (ex: IOException) {
                Logger.error("Unable to load capacitor.config.json.", ex)
            }
        }

        /**
         * Deserializes the config from JSON into a Capacitor Configuration object.
         */
        private fun deserializeConfig(context: Context?) {
            val isDebug = context != null && (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

            // Server
            html5mode = JSONUtils.getBoolean(configJSON, "server.html5mode", html5mode)
            serverUrl = JSONUtils.getString(configJSON, "server.url", null)
            hostname = JSONUtils.getString(configJSON, "server.hostname", hostname)
            errorPath = JSONUtils.getString(configJSON, "server.errorPath", null)
            startPath = JSONUtils.getString(configJSON, "server.appStartPath", null)

            // Never null: the default passed in is non-null.
            val configSchema = JSONUtils.getString(configJSON, "server.androidScheme", androidScheme) ?: androidScheme
            if (validateScheme(configSchema)) {
                androidScheme = configSchema
            }

            allowNavigation = JSONUtils.getArray(configJSON, "server.allowNavigation", null)

            // Android
            overriddenUserAgentString =
                JSONUtils.getString(
                    configJSON,
                    "android.overrideUserAgent",
                    JSONUtils.getString(configJSON, "overrideUserAgent", null),
                )
            appendedUserAgentString =
                JSONUtils.getString(
                    configJSON,
                    "android.appendUserAgent",
                    JSONUtils.getString(configJSON, "appendUserAgent", null),
                )
            backgroundColor =
                JSONUtils.getString(
                    configJSON,
                    "android.backgroundColor",
                    JSONUtils.getString(configJSON, "backgroundColor", null),
                )
            allowMixedContent =
                JSONUtils.getBoolean(
                    configJSON,
                    "android.allowMixedContent",
                    JSONUtils.getBoolean(configJSON, "allowMixedContent", allowMixedContent),
                )
            configuredMinWebViewVersion = JSONUtils.getInt(configJSON, "android.minWebViewVersion", DEFAULT_ANDROID_WEBVIEW_VERSION)
            configuredMinHuaweiWebViewVersion =
                JSONUtils.getInt(configJSON, "android.minHuaweiWebViewVersion", DEFAULT_HUAWEI_WEBVIEW_VERSION)
            captureInput = JSONUtils.getBoolean(configJSON, "android.captureInput", captureInput)
            useLegacyBridge = JSONUtils.getBoolean(configJSON, "android.useLegacyBridge", useLegacyBridge)
            webContentsDebuggingEnabled = JSONUtils.getBoolean(configJSON, "android.webContentsDebuggingEnabled", isDebug)
            zoomableWebView =
                JSONUtils.getBoolean(configJSON, "android.zoomEnabled", JSONUtils.getBoolean(configJSON, "zoomEnabled", false))
            resolveServiceWorkerRequests = JSONUtils.getBoolean(configJSON, "android.resolveServiceWorkerRequests", true)

            // Never null: the innermost default is non-null.
            val logBehavior =
                JSONUtils.getString(
                    configJSON,
                    "android.loggingBehavior",
                    JSONUtils.getString(configJSON, "loggingBehavior", LOG_BEHAVIOR_DEBUG),
                )
            loggingEnabled =
                when (logBehavior?.lowercase(Locale.ROOT)) {
                    LOG_BEHAVIOR_PRODUCTION -> true
                    LOG_BEHAVIOR_NONE -> false
                    else -> isDebug // LOG_BEHAVIOR_DEBUG
                }

            initialFocus =
                JSONUtils.getBoolean(
                    configJSON,
                    "android.initialFocus",
                    JSONUtils.getBoolean(configJSON, "initialFocus", initialFocus),
                )

            // Plugins
            pluginsConfiguration = deserializePluginsConfig(JSONUtils.getObject(configJSON, "plugins"))
        }

        private fun validateScheme(scheme: String): Boolean {
            val invalidSchemes = listOf("file", "ftp", "ftps", "ws", "wss", "about", "blob", "data")
            if (invalidSchemes.contains(scheme)) {
                Logger.warn("$scheme is not an allowed scheme.  Defaulting to https.")
                return false
            }

            // Non-http(s) schemes are not allowed to modify the URL path as of Android Webview 117
            if (scheme != "http" && scheme != "https") {
                Logger.warn("Using a non-standard scheme: $scheme for Android. This is known to cause issues as of Android Webview 117.")
            }

            return true
        }

        fun isHTML5Mode(): Boolean = html5mode

        fun isMixedContentAllowed(): Boolean = allowMixedContent

        fun isInputCaptured(): Boolean = captureInput

        fun isResolveServiceWorkerRequests(): Boolean = resolveServiceWorkerRequests

        fun isWebContentsDebuggingEnabled(): Boolean = webContentsDebuggingEnabled

        fun isZoomableWebView(): Boolean = zoomableWebView

        fun isLoggingEnabled(): Boolean = loggingEnabled

        fun isInitialFocus(): Boolean = initialFocus

        fun isUsingLegacyBridge(): Boolean = useLegacyBridge

        val minWebViewVersion: Int
            get() {
                if (configuredMinWebViewVersion < MINIMUM_ANDROID_WEBVIEW_VERSION) {
                    Logger.warn("Specified minimum webview version is too low, defaulting to $MINIMUM_ANDROID_WEBVIEW_VERSION")
                    return MINIMUM_ANDROID_WEBVIEW_VERSION
                }

                return configuredMinWebViewVersion
            }

        val minHuaweiWebViewVersion: Int
            get() {
                if (configuredMinHuaweiWebViewVersion < MINIMUM_HUAWEI_WEBVIEW_VERSION) {
                    Logger.warn("Specified minimum Huawei webview version is too low, defaulting to $MINIMUM_HUAWEI_WEBVIEW_VERSION")
                    return MINIMUM_HUAWEI_WEBVIEW_VERSION
                }

                return configuredMinHuaweiWebViewVersion
            }

        fun getPluginConfiguration(pluginId: String?): PluginConfig {
            // Same as the Java original: throws if the config was created without a context
            // (the plugins map is only populated by deserializeConfig or the Builder).
            return pluginsConfiguration!![pluginId] ?: PluginConfig(JSONObject())
        }

        /**
         * Builds a Capacitor Configuration in code
         *
         * @param context The context
         */
        class Builder(private val context: Context?) {
            // Server Config Values
            private var html5mode = true
            private var serverUrl: String? = null
            private var errorPath: String? = null
            private var hostname: String? = "localhost"
            private var androidScheme: String = CAPACITOR_HTTPS_SCHEME
            private var allowNavigation: Array<String>? = null

            // Android Config Values
            private var overriddenUserAgentString: String? = null
            private var appendedUserAgentString: String? = null
            private var backgroundColor: String? = null
            private var allowMixedContent = false
            private var captureInput = false
            private var webContentsDebuggingEnabled: Boolean? = null
            private var loggingEnabled = true
            private var initialFocus = false
            private var useLegacyBridge = false
            private val minWebViewVersion = DEFAULT_ANDROID_WEBVIEW_VERSION
            private val minHuaweiWebViewVersion = DEFAULT_HUAWEI_WEBVIEW_VERSION
            private var zoomableWebView = false
            private var resolveServiceWorkerRequests = true

            // Embedded
            private var startPath: String? = null

            // Plugins Config Object
            private var pluginsConfiguration: Map<String, PluginConfig> = HashMap()

            /**
             * Builds a Capacitor Config from the builder.
             *
             * @return A new Capacitor Config
             */
            fun create(): CapConfig {
                val debuggingEnabled =
                    webContentsDebuggingEnabled
                        // Same as the Java original: throws if no context was given.
                        ?: ((context!!.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0).also {
                            webContentsDebuggingEnabled = it
                        }

                val config = CapConfig()

                // Server Config
                config.html5mode = html5mode
                config.serverUrl = serverUrl
                config.hostname = hostname

                if (config.validateScheme(androidScheme)) {
                    config.androidScheme = androidScheme
                }

                config.allowNavigation = allowNavigation

                // Android Config
                config.overriddenUserAgentString = overriddenUserAgentString
                config.appendedUserAgentString = appendedUserAgentString
                config.backgroundColor = backgroundColor
                config.allowMixedContent = allowMixedContent
                config.captureInput = captureInput
                config.webContentsDebuggingEnabled = debuggingEnabled
                config.loggingEnabled = loggingEnabled
                config.initialFocus = initialFocus
                config.useLegacyBridge = useLegacyBridge
                config.configuredMinWebViewVersion = minWebViewVersion
                config.configuredMinHuaweiWebViewVersion = minHuaweiWebViewVersion
                config.errorPath = errorPath
                config.zoomableWebView = zoomableWebView
                config.resolveServiceWorkerRequests = resolveServiceWorkerRequests

                // Embedded
                config.startPath = startPath

                // Plugins Config
                config.pluginsConfiguration = pluginsConfiguration

                return config
            }

            fun setPluginsConfiguration(pluginsConfiguration: JSONObject?): Builder {
                this.pluginsConfiguration = deserializePluginsConfig(pluginsConfiguration)
                return this
            }

            fun setHTML5mode(html5mode: Boolean): Builder {
                this.html5mode = html5mode
                return this
            }

            fun setServerUrl(serverUrl: String?): Builder {
                this.serverUrl = serverUrl
                return this
            }

            fun setErrorPath(errorPath: String?): Builder {
                this.errorPath = errorPath
                return this
            }

            fun setHostname(hostname: String?): Builder {
                this.hostname = hostname
                return this
            }

            fun setStartPath(path: String?): Builder {
                this.startPath = path
                return this
            }

            fun setAndroidScheme(androidScheme: String): Builder {
                this.androidScheme = androidScheme
                return this
            }

            fun setAllowNavigation(allowNavigation: Array<String>?): Builder {
                this.allowNavigation = allowNavigation
                return this
            }

            fun setOverriddenUserAgentString(overriddenUserAgentString: String?): Builder {
                this.overriddenUserAgentString = overriddenUserAgentString
                return this
            }

            fun setAppendedUserAgentString(appendedUserAgentString: String?): Builder {
                this.appendedUserAgentString = appendedUserAgentString
                return this
            }

            fun setBackgroundColor(backgroundColor: String?): Builder {
                this.backgroundColor = backgroundColor
                return this
            }

            fun setAllowMixedContent(allowMixedContent: Boolean): Builder {
                this.allowMixedContent = allowMixedContent
                return this
            }

            fun setCaptureInput(captureInput: Boolean): Builder {
                this.captureInput = captureInput
                return this
            }

            fun setUseLegacyBridge(useLegacyBridge: Boolean): Builder {
                this.useLegacyBridge = useLegacyBridge
                return this
            }

            fun setResolveServiceWorkerRequests(resolveServiceWorkerRequests: Boolean): Builder {
                this.resolveServiceWorkerRequests = resolveServiceWorkerRequests
                return this
            }

            fun setWebContentsDebuggingEnabled(webContentsDebuggingEnabled: Boolean): Builder {
                this.webContentsDebuggingEnabled = webContentsDebuggingEnabled
                return this
            }

            fun setZoomableWebView(zoomableWebView: Boolean): Builder {
                this.zoomableWebView = zoomableWebView
                return this
            }

            fun setLoggingEnabled(enabled: Boolean): Builder {
                this.loggingEnabled = enabled
                return this
            }

            fun setInitialFocus(focus: Boolean): Builder {
                this.initialFocus = focus
                return this
            }
        }

        companion object {
            private const val LOG_BEHAVIOR_NONE = "none"
            private const val LOG_BEHAVIOR_DEBUG = "debug"
            private const val LOG_BEHAVIOR_PRODUCTION = "production"

            /**
             * Constructs a Capacitor Configuration from config.json file.
             *
             * @param context The context.
             * @return A loaded config file, if successful.
             */
            @JvmStatic
            fun loadDefault(context: Context?): CapConfig {
                val config = CapConfig()

                if (context == null) {
                    Logger.error("Capacitor Config could not be created from file. Context must not be null.")
                    return config
                }

                config.loadConfigFromAssets(context.assets, null)
                config.deserializeConfig(context)
                return config
            }

            /**
             * Constructs a Capacitor Configuration from config.json file within the app assets.
             *
             * @param context The context.
             * @param path A path relative to the root assets directory.
             * @return A loaded config file, if successful.
             */
            @JvmStatic
            fun loadFromAssets(context: Context?, path: String?): CapConfig {
                val config = CapConfig()

                if (context == null) {
                    Logger.error("Capacitor Config could not be created from file. Context must not be null.")
                    return config
                }

                config.loadConfigFromAssets(context.assets, path)
                config.deserializeConfig(context)
                return config
            }

            /**
             * Constructs a Capacitor Configuration from config.json file within the app file-space.
             *
             * @param context The context.
             * @param path A path relative to the root of the app file-space.
             * @return A loaded config file, if successful.
             */
            @JvmStatic
            fun loadFromFile(context: Context?, path: String?): CapConfig {
                val config = CapConfig()

                if (context == null) {
                    Logger.error("Capacitor Config could not be created from file. Context must not be null.")
                    return config
                }

                config.loadConfigFromFile(path)
                config.deserializeConfig(context)
                return config
            }

            /**
             * Null becomes the root; anything else gets a trailing slash so it forms a proper
             * file path when going deeper in the directory.
             */
            private fun normalizeDirectory(path: String?): String =
                if (path == null) {
                    ""
                } else if (path[path.length - 1] != '/') {
                    "$path/"
                } else {
                    path
                }

            private fun deserializePluginsConfig(pluginsConfig: JSONObject?): Map<String, PluginConfig> {
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
                        val pluginConfig = PluginConfig(value)
                        pluginsMap[pluginId] = pluginConfig
                    } catch (e: JSONException) {
                        e.printStackTrace()
                    }
                }

                return pluginsMap
            }
        }
    }
