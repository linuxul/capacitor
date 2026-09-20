package com.getcapacitor

import android.content.Context
import android.content.pm.ApplicationInfo
import com.getcapacitor.Bridge.Companion.CAPACITOR_HTTPS_SCHEME
import com.getcapacitor.Bridge.Companion.DEFAULT_ANDROID_WEBVIEW_VERSION
import com.getcapacitor.Bridge.Companion.DEFAULT_HUAWEI_WEBVIEW_VERSION
import com.getcapacitor.Bridge.Companion.MINIMUM_ANDROID_WEBVIEW_VERSION
import com.getcapacitor.Bridge.Companion.MINIMUM_HUAWEI_WEBVIEW_VERSION
import org.json.JSONObject

/**
 * Represents the configuration options for Capacitor.
 *
 * Instances come from [loadDefault], [loadFromAssets], [loadFromFile] (parsed by [CapConfigParser]) or from a [Builder].
 * The defaults below are the ones of an empty config file.
 */
public class CapConfig internal constructor(
    // Server Config
    public val isHTML5Mode: Boolean = true,
    public val serverUrl: String? = null,
    public val hostname: String? = "localhost",
    public val androidScheme: String = CAPACITOR_HTTPS_SCHEME,
    public val allowNavigation: Array<String>? = null,
    // Android Config
    public val overriddenUserAgentString: String? = null,
    public val appendedUserAgentString: String? = null,
    public val backgroundColor: String? = null,
    public val isMixedContentAllowed: Boolean = false,
    public val isInputCaptured: Boolean = false,
    public val isWebContentsDebuggingEnabled: Boolean = false,
    public val isLoggingEnabled: Boolean = true,
    public val isInitialFocus: Boolean = true,
    public val isUsingLegacyBridge: Boolean = false,
    private val configuredMinWebViewVersion: Int = DEFAULT_ANDROID_WEBVIEW_VERSION,
    private val configuredMinHuaweiWebViewVersion: Int = DEFAULT_HUAWEI_WEBVIEW_VERSION,
    public val errorPath: String? = null,
    public val isZoomableWebView: Boolean = false,
    public val isResolveServiceWorkerRequests: Boolean = true,
    // Embedded
    public val startPath: String? = null,
    // Plugins
    private val pluginsConfiguration: Map<String, PluginConfig> = emptyMap(),
) {
    public val minWebViewVersion: Int
        get() {
            if (configuredMinWebViewVersion < MINIMUM_ANDROID_WEBVIEW_VERSION) {
                Logger.warn("Specified minimum webview version is too low, defaulting to $MINIMUM_ANDROID_WEBVIEW_VERSION")
                return MINIMUM_ANDROID_WEBVIEW_VERSION
            }

            return configuredMinWebViewVersion
        }

    public val minHuaweiWebViewVersion: Int
        get() {
            if (configuredMinHuaweiWebViewVersion < MINIMUM_HUAWEI_WEBVIEW_VERSION) {
                Logger.warn("Specified minimum Huawei webview version is too low, defaulting to $MINIMUM_HUAWEI_WEBVIEW_VERSION")
                return MINIMUM_HUAWEI_WEBVIEW_VERSION
            }

            return configuredMinHuaweiWebViewVersion
        }

    /**
     * The configuration of a plugin, or an empty one if the config has none for it.
     */
    public fun getPluginConfiguration(pluginId: String?): PluginConfig = pluginsConfiguration[pluginId] ?: PluginConfig(JSONObject())

    /**
     * Builds a Capacitor Configuration in code
     *
     * @param context The context
     */
    public class Builder(private val context: Context?) {
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
        public fun create(): CapConfig {
            // Unless set explicitly, web contents debugging follows the debuggable flag of the app (off without a context).
            val debuggingEnabled =
                webContentsDebuggingEnabled
                    ?: isDebuggable(context).also { webContentsDebuggingEnabled = it }

            return CapConfig(
                isHTML5Mode = html5mode,
                serverUrl = serverUrl,
                hostname = hostname,
                androidScheme = if (validateScheme(androidScheme)) androidScheme else CAPACITOR_HTTPS_SCHEME,
                allowNavigation = allowNavigation,
                overriddenUserAgentString = overriddenUserAgentString,
                appendedUserAgentString = appendedUserAgentString,
                backgroundColor = backgroundColor,
                isMixedContentAllowed = allowMixedContent,
                isInputCaptured = captureInput,
                isWebContentsDebuggingEnabled = debuggingEnabled,
                isLoggingEnabled = loggingEnabled,
                isInitialFocus = initialFocus,
                isUsingLegacyBridge = useLegacyBridge,
                errorPath = errorPath,
                isZoomableWebView = zoomableWebView,
                isResolveServiceWorkerRequests = resolveServiceWorkerRequests,
                startPath = startPath,
                pluginsConfiguration = pluginsConfiguration,
            )
        }

        public fun setPluginsConfiguration(pluginsConfiguration: JSONObject?): Builder {
            this.pluginsConfiguration = CapConfigParser.parsePluginsConfig(pluginsConfiguration)
            return this
        }

        public fun setHTML5mode(html5mode: Boolean): Builder {
            this.html5mode = html5mode
            return this
        }

        public fun setServerUrl(serverUrl: String?): Builder {
            this.serverUrl = serverUrl
            return this
        }

        public fun setErrorPath(errorPath: String?): Builder {
            this.errorPath = errorPath
            return this
        }

        public fun setHostname(hostname: String?): Builder {
            this.hostname = hostname
            return this
        }

        public fun setStartPath(path: String?): Builder {
            this.startPath = path
            return this
        }

        public fun setAndroidScheme(androidScheme: String): Builder {
            this.androidScheme = androidScheme
            return this
        }

        public fun setAllowNavigation(allowNavigation: Array<String>?): Builder {
            this.allowNavigation = allowNavigation
            return this
        }

        public fun setOverriddenUserAgentString(overriddenUserAgentString: String?): Builder {
            this.overriddenUserAgentString = overriddenUserAgentString
            return this
        }

        public fun setAppendedUserAgentString(appendedUserAgentString: String?): Builder {
            this.appendedUserAgentString = appendedUserAgentString
            return this
        }

        public fun setBackgroundColor(backgroundColor: String?): Builder {
            this.backgroundColor = backgroundColor
            return this
        }

        public fun setAllowMixedContent(allowMixedContent: Boolean): Builder {
            this.allowMixedContent = allowMixedContent
            return this
        }

        public fun setCaptureInput(captureInput: Boolean): Builder {
            this.captureInput = captureInput
            return this
        }

        public fun setUseLegacyBridge(useLegacyBridge: Boolean): Builder {
            this.useLegacyBridge = useLegacyBridge
            return this
        }

        public fun setResolveServiceWorkerRequests(resolveServiceWorkerRequests: Boolean): Builder {
            this.resolveServiceWorkerRequests = resolveServiceWorkerRequests
            return this
        }

        public fun setWebContentsDebuggingEnabled(webContentsDebuggingEnabled: Boolean): Builder {
            this.webContentsDebuggingEnabled = webContentsDebuggingEnabled
            return this
        }

        public fun setZoomableWebView(zoomableWebView: Boolean): Builder {
            this.zoomableWebView = zoomableWebView
            return this
        }

        public fun setLoggingEnabled(enabled: Boolean): Builder {
            this.loggingEnabled = enabled
            return this
        }

        public fun setInitialFocus(focus: Boolean): Builder {
            this.initialFocus = focus
            return this
        }
    }

    public companion object {
        /**
         * Constructs a Capacitor Configuration from config.json file.
         *
         * @param context The context.
         * @return A loaded config file, if successful.
         */
        public fun loadDefault(context: Context?): CapConfig = loadFromAssets(context, null)

        /**
         * Constructs a Capacitor Configuration from config.json file within the app assets.
         *
         * @param context The context.
         * @param path A path relative to the root assets directory.
         * @return A loaded config file, if successful.
         */
        public fun loadFromAssets(context: Context?, path: String?): CapConfig {
            if (context == null) {
                Logger.error("Capacitor Config could not be created from file. Context must not be null.")
                return CapConfig()
            }

            return CapConfigParser(context).fromAssets(path)
        }

        /**
         * Constructs a Capacitor Configuration from config.json file within the app file-space.
         *
         * @param context The context.
         * @param path A path relative to the root of the app file-space.
         * @return A loaded config file, if successful.
         */
        public fun loadFromFile(context: Context?, path: String?): CapConfig {
            if (context == null) {
                Logger.error("Capacitor Config could not be created from file. Context must not be null.")
                return CapConfig()
            }

            return CapConfigParser(context).fromFile(path)
        }

        internal fun isDebuggable(context: Context?): Boolean =
            context != null && (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        /**
         * Whether the scheme may be used to serve the app. Callers fall back to https otherwise.
         */
        internal fun validateScheme(scheme: String): Boolean {
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
    }
}
