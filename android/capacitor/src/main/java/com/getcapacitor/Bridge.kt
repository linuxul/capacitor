package com.getcapacitor

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.fragment.app.Fragment
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.getcapacitor.android.R
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.util.HostMask
import com.getcapacitor.util.InternalUtils
import com.getcapacitor.util.PermissionHelper
import com.getcapacitor.util.WebColor
import java.io.File
import java.net.SocketTimeoutException
import java.net.URL
import org.json.JSONException

/**
 * The Bridge class is the main engine of Capacitor. It manages
 * loading and communicating with all Plugins,
 * proxying Native events to Plugins, executing Plugin methods,
 * communicating with the WebView, and a whole lot more.
 *
 * Generally, you'll not use Bridge directly, instead, extend from BridgeActivity
 * to get a WebView instance and proxy native events automatically.
 *
 * If you want to use this Bridge in an existing Android app, please
 * see the source for [BridgeActivity] for the methods you'll need to
 * pass through to Bridge.
 */
public class Bridge private constructor(
    /**
     * Get the activity for the app
     */
    public val activity: AppCompatActivity,
    // A pre-determined path to load the bridge
    internal val serverPath: ServerPath?,
    /**
     * Get the fragment for the app, if applicable. This will likely be null unless Capacitor
     * is being used embedded in a Native Android app.
     */
    public val fragment: Fragment?,
    /**
     * Get the core WebView under Capacitor's control
     */
    public val webView: WebView,
    private val initialPlugins: List<Class<out Plugin>>,
    private val pluginInstances: List<Plugin>,
    config: CapConfig?
) {
    // Loaded Capacitor config
    public val config: CapConfig

    public lateinit var localServer: WebViewLocalServer
        private set
    public var localUrl: String? = null
        private set
    public var appUrl: String? = null
        private set
    public lateinit var appAllowNavigationMask: HostMask
        private set
    public val allowedOriginRules: MutableSet<String> = HashSet()
    private val authorities = ArrayList<String?>()
    private var miscJSFileInjections = ArrayList<String>()
    private var canInjectJS = true

    /**
     * The WebViewClient in use. Setting it also installs it on the WebView.
     */
    public var webViewClient: BridgeWebViewClient = BridgeWebViewClient(this)
        set(client) {
            field = client
            webView.webViewClient = client
        }

    public val app: App = App()

    // Our MessageHandler for sending and receiving data to the WebView
    private val msgHandler: MessageHandler

    // The ThreadHandler for executing plugin calls
    private val handlerThread = HandlerThread("CapacitorPlugins")

    // Our Handler for posting plugin calls. Created from the ThreadHandler
    private val taskHandler: Handler

    // Our Handler for posting to the main thread
    private val mainHandler: Handler = Handler(activity.mainLooper)

    // A map of Plugin Id's to PluginHandle's
    private val plugins: MutableMap<String, PluginHandle> = HashMap()

    // Saved plugin calls: kept alive, waiting for permissions, or waiting for an activity result
    private val savedCallStore = SavedCallStore()

    /**
     * Get the URI that was used to launch the app (if any)
     */
    public val intentUri: Uri?

    // A list of listeners that trigger when webView events occur
    internal var webViewListeners: MutableList<WebViewListener> = ArrayList()

    // An interface to manipulate route resolving
    internal var routeProcessor: RouteProcessor? = null

    init {
        // Start our plugin execution threads and handlers
        handlerThread.start()
        taskHandler = Handler(handlerThread.looper)

        this.config = config ?: CapConfig.loadDefault(activity)
        Logger.loggingEnabled = this.config.isLoggingEnabled

        // Initialize web view and message handler for it
        initWebView()
        setAllowedOriginRules()
        msgHandler = MessageHandler(this, webView)

        // Grab any intent info that our app was launched with
        val intent = activity.intent
        intentUri = intent.data
        // Register our core plugins
        registerAllPlugins()

        loadWebView()
    }

    private fun setAllowedOriginRules() {
        val appAllowNavigationConfig = config.allowNavigation
        val authority = host
        val scheme = scheme
        allowedOriginRules.add("$scheme://$authority")
        serverUrl?.let { allowedOriginRules.add(it) }
        if (appAllowNavigationConfig != null) {
            for (allowNavigation in appAllowNavigationConfig) {
                if (!allowNavigation.startsWith("http")) {
                    allowedOriginRules.add("https://$allowNavigation")
                } else {
                    allowedOriginRules.add(allowNavigation)
                }
            }
            authorities.addAll(appAllowNavigationConfig)
        }
        appAllowNavigationMask = HostMask.Parser.parse(appAllowNavigationConfig)
    }

    private fun loadWebView() {
        val html5mode = config.isHTML5Mode

        // Start the local web server
        var injector = getJSInjector()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Same as the Java original: a missing appUrl (invalid server.url) throws here.
            val allowedOrigin = Uri.parse(appUrl!!).buildUpon().path(null).fragment(null).clearQuery().build().toString()
            try {
                // Same as the Java original: a missing injector throws here.
                WebViewCompat.addDocumentStartJavaScript(webView, injector!!.scriptString, setOf(allowedOrigin))
                injector = null
            } catch (ex: IllegalArgumentException) {
                Logger.warn("Invalid url, using fallback")
            }
        }
        localServer = WebViewLocalServer(activity, this, injector, authorities, html5mode)
        localServer.hostAssets(DEFAULT_WEB_ASSET_DIR)

        Logger.debug("Loading app at $appUrl")

        webView.webChromeClient = BridgeWebChromeClient(this)
        webView.webViewClient = webViewClient

        if (config.isResolveServiceWorkerRequests) {
            val swController = ServiceWorkerController.getInstance()
            swController.setServiceWorkerClient(
                object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest?): WebResourceResponse? {
                        if (request == null) return null
                        return localServer.shouldInterceptRequest(request)
                    }
                }
            )
        }

        if (!isNewBinary()) {
            val prefs = context.getSharedPreferences(com.getcapacitor.plugin.WebView.WEBVIEW_PREFS_NAME, Activity.MODE_PRIVATE)
            val path = prefs.getString(com.getcapacitor.plugin.WebView.CAP_SERVER_PATH, null)
            if (path != null && path.isNotEmpty() && File(path).exists()) {
                serverBasePath = path
            }
        }
        if (!isMinimumWebViewInstalled()) {
            val errorUrl = errorUrl
            if (errorUrl != null) {
                webView.loadUrl(errorUrl)
                return
            } else {
                Logger.error(MINIMUM_ANDROID_WEBVIEW_ERROR)
            }
        }

        // If serverPath configured, start server based on provided path
        if (serverPath != null) {
            if (serverPath.type == ServerPath.PathType.ASSET_PATH) {
                setServerAssetPath(serverPath.path)
            } else {
                serverBasePath = serverPath.path
            }
        } else {
            // Get to work
            // Same as the Java original: a missing appUrl (invalid server.url) throws here.
            webView.loadUrl(appUrl!!)
        }
    }

    public fun isMinimumWebViewInstalled(): Boolean {
        val info = WebView.getCurrentWebViewPackage() ?: return false
        // The Java original threw on a WebView package without a version name; it now counts as unsupported.
        val majorVersion = WEBVIEW_MAJOR_VERSION.find(info.versionName ?: return false)?.value?.toInt() ?: return false
        return if (info.packageName == "com.huawei.webview") {
            majorVersion >= config.minHuaweiWebViewVersion
        } else {
            majorVersion >= config.minWebViewVersion
        }
    }

    public fun launchIntent(url: Uri): Boolean {
        // The proxy returns a remote body at the app origin, so block it before plugins can allow it.
        val path = url.path
        if (path != null && path.startsWith(CAPACITOR_HTTP_INTERCEPTOR_START)) {
            return true
        }

        /*
         * Give plugins the chance to handle the url
         */
        for (entry in plugins.entries) {
            val plugin = entry.value.instance
            val shouldOverrideLoad = plugin.shouldOverrideLoad(url)
            if (shouldOverrideLoad != null) {
                return shouldOverrideLoad
            }
        }

        if (url.scheme == "data" || url.scheme == "blob") {
            return false
        }

        // Same as the Java original: a missing appUrl (invalid server.url) throws here.
        val appUri = Uri.parse(appUrl!!)
        if (!(appUri.host == url.host && url.scheme == appUri.scheme) && !appAllowNavigationMask.matches(url.host)) {
            try {
                val openIntent = Intent(Intent.ACTION_VIEW, url)
                context.startActivity(openIntent)
            } catch (e: ActivityNotFoundException) {
                // TODO - trigger an event
            }
            return true
        }
        return false
    }

    private fun isNewBinary(): Boolean {
        var versionCode = ""
        var versionName = ""
        val prefs = context.getSharedPreferences(com.getcapacitor.plugin.WebView.WEBVIEW_PREFS_NAME, Activity.MODE_PRIVATE)
        val lastVersionCode = prefs.getString(LAST_BINARY_VERSION_CODE, null)
        val lastVersionName = prefs.getString(LAST_BINARY_VERSION_NAME, null)

        try {
            val pm = context.packageManager
            // A missing PackageInfo throws and is reported by the catch below, as in the Java original.
            val pInfo = InternalUtils.getPackageInfo(pm, context.packageName)!!
            versionCode = PackageInfoCompat.getLongVersionCode(pInfo).toInt().toString()
            versionName = pInfo.versionName ?: ""
        } catch (ex: Exception) {
            Logger.error("Unable to get package info", ex)
        }

        if (versionCode != lastVersionCode || versionName != lastVersionName) {
            val editor = prefs.edit()
            editor.putString(LAST_BINARY_VERSION_CODE, versionCode)
            editor.putString(LAST_BINARY_VERSION_NAME, versionName)
            editor.putString(com.getcapacitor.plugin.WebView.CAP_SERVER_PATH, "")
            editor.apply()
            return true
        }
        return false
    }

    public fun handleAppUrlLoadError(ex: Exception?) {
        if (ex is SocketTimeoutException) {
            Logger.error(
                "Unable to load app. Ensure the server is running at " +
                    appUrl +
                    ", or modify the " +
                    "appUrl setting in capacitor.config.json (make sure to npx cap copy after to commit changes).",
                ex
            )
        }
    }

    public val isDevMode: Boolean
        get() = (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Get the Context for the App
     */
    public val context: Context
        get() = activity

    /**
     * Get scheme that is used to serve content
     */
    public val scheme: String
        get() = config.androidScheme

    /**
     * Get host name that is used to serve content
     */
    public val host: String?
        get() = config.hostname

    /**
     * Get the server url that is used to serve content
     */
    public val serverUrl: String?
        get() = config.serverUrl

    public val errorUrl: String?
        get() {
            val errorPath = config.errorPath

            // trim { it <= ' ' } is java.lang.String.trim().
            if (errorPath != null && errorPath.trim { it <= ' ' }.isNotEmpty()) {
                // The error page is always served from the Capacitor origin, not the localUrl property,
                // which follows server.url.
                return "$scheme://$host/$errorPath"
            }

            return null
        }

    public fun reset() {
        savedCallStore.reset()
        eachPlugin { it.removeAllListeners() }
    }

    /**
     * Initialize the WebView, setting required flags
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setGeolocationEnabled(true)
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        if (config.isMixedContentAllowed) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        val appendUserAgent = config.appendedUserAgentString
        if (appendUserAgent != null) {
            val defaultUserAgent = settings.userAgentString
            settings.userAgentString = "$defaultUserAgent $appendUserAgent"
        }
        val overrideUserAgent = config.overriddenUserAgentString
        if (overrideUserAgent != null) {
            settings.userAgentString = overrideUserAgent
        }

        val backgroundColor = config.backgroundColor
        try {
            if (backgroundColor != null) {
                webView.setBackgroundColor(WebColor.parseColor(backgroundColor))
            }
        } catch (ex: IllegalArgumentException) {
            Logger.debug("WebView background color not applied")
        }

        settings.displayZoomControls = false
        settings.builtInZoomControls = config.isZoomableWebView

        if (config.isInitialFocus) {
            webView.requestFocusFromTouch()
        }

        WebView.setWebContentsDebuggingEnabled(config.isWebContentsDebuggingEnabled)

        val appUrlConfig = serverUrl
        val authority = host
        authorities.add(authority)
        val scheme = scheme

        localUrl = "$scheme://$authority"

        if (appUrlConfig != null) {
            try {
                val appUrlObject = URL(appUrlConfig)
                authorities.add(appUrlObject.authority)
                localUrl = appUrlObject.protocol + "://" + appUrlObject.authority
            } catch (ex: Exception) {
                Logger.error("Provided server url is invalid: " + ex.message)
                return
            }
            appUrl = appUrlConfig
        } else {
            appUrl = localUrl
            // custom URL schemes requires path ending with /
            if (scheme != CAPACITOR_HTTP_SCHEME && scheme != CAPACITOR_HTTPS_SCHEME) {
                appUrl += "/"
            }
        }

        val appUrlPath = config.startPath
        if (appUrlPath != null && appUrlPath.trim { it <= ' ' }.isNotEmpty()) {
            appUrl += appUrlPath
        }
    }

    /**
     * Register our core Plugin APIs
     */
    private fun registerAllPlugins() {
        registerPlugin(com.getcapacitor.plugin.CapacitorCookies::class.java)
        registerPlugin(com.getcapacitor.plugin.WebView::class.java)
        registerPlugin(com.getcapacitor.plugin.CapacitorHttp::class.java)
        registerPlugin(com.getcapacitor.plugin.SystemBars::class.java)

        for (pluginClass in initialPlugins) {
            registerPlugin(pluginClass)
        }

        for (plugin in pluginInstances) {
            registerPluginInstance(plugin)
        }
    }

    /**
     * Register additional plugins
     * @param pluginClasses the plugins to register
     */
    public fun registerPlugins(pluginClasses: Array<Class<out Plugin>>) {
        for (plugin in pluginClasses) {
            registerPlugin(plugin)
        }
    }

    public fun registerPluginInstances(pluginInstances: Array<Plugin>) {
        for (plugin in pluginInstances) {
            registerPluginInstance(plugin)
        }
    }

    /**
     * Register a plugin class
     * @param pluginClass a class inheriting from Plugin
     */
    public fun registerPlugin(pluginClass: Class<out Plugin>) {
        val pluginId = pluginId(pluginClass) ?: return

        try {
            plugins[pluginId] = PluginHandle(this, pluginClass)
        } catch (ex: InvalidPluginException) {
            logInvalidPluginException(pluginClass)
        } catch (ex: PluginLoadException) {
            logPluginLoadException(pluginClass, ex)
        }
    }

    public fun registerPluginInstance(plugin: Plugin) {
        val clazz = plugin.javaClass
        val pluginId = pluginId(clazz) ?: return

        try {
            plugins[pluginId] = PluginHandle(this, plugin)
        } catch (ex: InvalidPluginException) {
            logInvalidPluginException(clazz)
        }
    }

    private fun pluginId(clazz: Class<out Plugin>): String? {
        val pluginName = pluginName(clazz) ?: return null
        val pluginId = pluginName.ifEmpty { clazz.simpleName }
        Logger.debug("Registering plugin instance: $pluginId")
        return pluginId
    }

    private fun pluginName(clazz: Class<out Plugin>): String? {
        val pluginAnnotation = clazz.getAnnotation(CapacitorPlugin::class.java)
        if (pluginAnnotation == null) {
            Logger.error("Plugin doesn't have the @CapacitorPlugin annotation. Please add it")
            return null
        }

        return pluginAnnotation.name
    }

    private fun logInvalidPluginException(clazz: Class<out Plugin>) {
        Logger.error(
            "Plugin " +
                clazz.name +
                " is invalid. Ensure the @CapacitorPlugin annotation exists on the plugin class and" +
                " the class extends Plugin"
        )
    }

    private fun logPluginLoadException(clazz: Class<out Plugin>, ex: Exception) {
        Logger.error("Plugin " + clazz.name + " failed to load", ex)
    }

    public fun getPlugin(pluginId: String?): PluginHandle? = plugins[pluginId]

    private inline fun eachPlugin(block: (Plugin) -> Unit) {
        for (handle in plugins.values) {
            block(handle.instance)
        }
    }

    /**
     * Call a method on a plugin.
     * @param pluginId the plugin id to use to lookup the plugin handle
     * @param methodName the name of the method to call
     * @param call the call object to pass to the method
     */
    public fun callPluginMethod(pluginId: String?, methodName: String?, call: PluginCall) {
        try {
            val plugin = getPlugin(pluginId)

            if (plugin == null) {
                Logger.error("unable to find plugin : $pluginId")
                call.errorCallback("unable to find plugin : $pluginId")
                return
            }

            if (Logger.shouldLog()) {
                Logger.verbose(
                    "callback: " +
                        call.callbackId +
                        ", pluginId: " +
                        plugin.id +
                        ", methodName: " +
                        methodName +
                        ", methodData: " +
                        call.data.toString()
                )
            }

            val currentThreadTask =
                Runnable {
                    try {
                        plugin.invoke(methodName, call)

                        if (call.keepAlive) {
                            saveCall(call)
                        }
                    } catch (ex: PluginLoadException) {
                        Logger.error("Unable to execute plugin method", ex)
                    } catch (ex: InvalidPluginMethodException) {
                        Logger.error("Unable to execute plugin method", ex)
                    } catch (ex: Exception) {
                        Logger.error("Serious error executing plugin", ex)
                        throw RuntimeException(ex)
                    }
                }

            taskHandler.post(currentThreadTask)
        } catch (ex: Exception) {
            Logger.error(Logger.tags("callPluginMethod"), "error : $ex", null)
            call.errorCallback(ex.toString())
        }
    }

    /**
     * Evaluate JavaScript in the web view. This method
     * executes on the main thread automatically.
     * @param js the JS to execute
     * @param callback an optional ValueCallback that will synchronously receive a value
     * after calling the JS
     */
    public fun eval(js: String, callback: ValueCallback<String>?) {
        executeOnMainThread { webView.evaluateJavascript(js, callback) }
    }

    @JvmOverloads
    public fun logToJs(message: String?, level: String? = "log") {
        eval("window.Capacitor.logJs(\"$message\", \"$level\")", null)
    }

    public fun triggerJSEvent(eventName: String?, target: String?) {
        eval("window.Capacitor.triggerEvent(\"$eventName\", \"$target\")") { }
    }

    public fun triggerJSEvent(eventName: String?, target: String?, data: String?) {
        eval("window.Capacitor.triggerEvent(\"$eventName\", \"$target\", $data)") { }
    }

    public fun triggerWindowJSEvent(eventName: String?) {
        triggerJSEvent(eventName, "window")
    }

    public fun triggerWindowJSEvent(eventName: String?, data: String?) {
        triggerJSEvent(eventName, "window", data)
    }

    public fun triggerDocumentJSEvent(eventName: String?) {
        triggerJSEvent(eventName, "document")
    }

    public fun triggerDocumentJSEvent(eventName: String?, data: String?) {
        triggerJSEvent(eventName, "document", data)
    }

    public fun execute(runnable: Runnable) {
        taskHandler.post(runnable)
    }

    public fun executeOnMainThread(runnable: Runnable) {
        mainHandler.post(runnable)
    }

    /**
     * Retain a call between plugin invocations
     */
    public fun saveCall(call: PluginCall) {
        savedCallStore.save(call)
    }

    /**
     * Get a retained plugin call
     * @param callbackId the callbackId to use to lookup the call with
     * @return the stored call
     */
    public fun getSavedCall(callbackId: String?): PluginCall? = savedCallStore.get(callbackId)

    // Not a property: reading it clears it.
    internal fun getPluginCallForLastActivity(): PluginCall? = savedCallStore.takeLastActivityCall()

    internal fun setPluginCallForLastActivity(pluginCallForLastActivity: PluginCall?) {
        savedCallStore.setLastActivityCall(pluginCallForLastActivity)
    }

    /**
     * Release a retained call
     * @param call a call to release
     */
    public fun releaseCall(call: PluginCall) {
        releaseCall(call.callbackId)
    }

    /**
     * Release a retained call by its ID
     * @param callbackId an ID of a callback to release
     */
    public fun releaseCall(callbackId: String?) {
        savedCallStore.release(callbackId)
    }

    /**
     * Removes the earliest saved call prior to a permissions request for a given plugin and
     * returns it.
     *
     * @return The saved plugin call
     */
    internal fun getPermissionCall(pluginId: String?): PluginCall? = savedCallStore.takePermissionCall(pluginId)

    /**
     * Save a call to be retrieved after requesting permissions. Calls are saved in order.
     *
     * @param call The plugin call to save.
     */
    internal fun savePermissionCall(call: PluginCall?) {
        savedCallStore.savePermissionCall(call)
    }

    /**
     * Register an Activity Result Launcher to the containing Fragment or Activity.
     *
     * @param contract A contract specifying that an activity can be called with an input of
     * type I and produce an output of type O.
     * @param callback The callback run on Activity Result.
     * @return A registered Activity Result Launcher.
     */
    public fun <I, O> registerForActivityResult(
        contract: ActivityResultContract<I, O>,
        callback: ActivityResultCallback<O>
    ): ActivityResultLauncher<I> = if (fragment != null) {
        fragment.registerForActivityResult(contract, callback)
    } else {
        activity.registerForActivityResult(contract, callback)
    }

    /**
     * Build the JSInjector that will be used to inject JS into files served to the app,
     * to ensure that Capacitor's JS and the JS for all the plugins is loaded each time.
     */
    private fun getJSInjector(): JSInjector? {
        try {
            val globalJS = JSExport.getGlobalJS(activity, config.isLoggingEnabled, isDevMode)
            val bridgeJS = JSExport.getBridgeJS(activity)
            val pluginJS = JSExport.getPluginJS(plugins.values)
            val localUrlJS = "window.WEBVIEW_SERVER_URL = '$localUrl';"
            val miscJS = JSExport.getMiscFileJS(miscJSFileInjections, activity)

            miscJSFileInjections = ArrayList()
            canInjectJS = false

            return JSInjector(globalJS, bridgeJS, pluginJS, localUrlJS, miscJS)
        } catch (ex: Exception) {
            Logger.error("Unable to export Capacitor JS. App will not function!", ex)
        }
        return null
    }

    /**
     * Inject JavaScript from an external file before the WebView loads.
     * @param path relative to public folder
     */
    public fun injectScriptBeforeLoad(path: String) {
        if (canInjectJS) {
            miscJSFileInjections.add(path)
        }
    }

    /**
     * Restore any saved bundle state data
     */
    public fun restoreInstanceState(savedInstanceState: Bundle) {
        val lastPluginId = savedInstanceState.getString(BUNDLE_LAST_PLUGIN_ID_KEY)
        val lastPluginCallMethod = savedInstanceState.getString(BUNDLE_LAST_PLUGIN_CALL_METHOD_NAME_KEY)
        val lastOptionsJson = savedInstanceState.getString(BUNDLE_PLUGIN_CALL_OPTIONS_SAVED_KEY)

        if (lastPluginId != null) {
            // If we have JSON blob saved, create a new plugin call with the original options
            if (lastOptionsJson != null) {
                try {
                    val options = JSObject(lastOptionsJson)

                    savedCallStore.setLastActivityCall(
                        PluginCall(msgHandler, lastPluginId, PluginCall.CALLBACK_ID_DANGLING, lastPluginCallMethod, options)
                    )
                } catch (ex: JSONException) {
                    Logger.error("Unable to restore plugin call, unable to parse persisted JSON object", ex)
                }
            }

            // Let the plugin restore any state it needs
            val bundleData = savedInstanceState.getBundle(BUNDLE_PLUGIN_CALL_BUNDLE_KEY)
            val lastPlugin = getPlugin(lastPluginId)
            if (bundleData != null && lastPlugin != null) {
                lastPlugin.instance.dispatchRestoreState(bundleData)
            } else {
                Logger.error("Unable to restore last plugin call")
            }
        }
    }

    public fun saveInstanceState(outState: Bundle) {
        Logger.debug("Saving instance state!")

        // If there was a last PluginCall for a started activity, we need to
        // persist it so we can load it again in case our app gets terminated
        val call = savedCallStore.peekLastActivityCall()
        if (call != null) {
            val handle = getPlugin(call.pluginId)

            if (handle != null) {
                val bundle = handle.instance.dispatchSaveInstanceState()
                if (bundle != null) {
                    outState.putString(BUNDLE_LAST_PLUGIN_ID_KEY, call.pluginId)
                    outState.putString(BUNDLE_LAST_PLUGIN_CALL_METHOD_NAME_KEY, call.methodName)
                    outState.putString(BUNDLE_PLUGIN_CALL_OPTIONS_SAVED_KEY, call.data.toString())
                    outState.putBundle(BUNDLE_PLUGIN_CALL_BUNDLE_KEY, bundle)
                } else {
                    Logger.error("Couldn't save last " + call.pluginId + "'s Plugin " + call.methodName + " call")
                }
            }
        }
    }

    /**
     * Saves permission states and rejects if permissions were not correctly defined in
     * the AndroidManifest.xml file.
     *
     * @return true if permissions were saved and defined correctly, false if not
     */
    internal fun validatePermissions(plugin: Plugin, savedCall: PluginCall?, permissions: Map<String, Boolean>): Boolean {
        val prefs = context.getSharedPreferences(PERMISSION_PREFS_NAME, Activity.MODE_PRIVATE)

        for ((permString, isGranted) in permissions) {
            if (isGranted) {
                // Permission granted. If previously denied, remove cached state
                val state = prefs.getString(permString, null)

                if (state != null) {
                    val editor = prefs.edit()
                    editor.remove(permString)
                    editor.apply()
                }
            } else {
                val editor = prefs.edit()

                if (ActivityCompat.shouldShowRequestPermissionRationale(activity, permString)) {
                    // Permission denied, can prompt again with rationale
                    editor.putString(permString, PermissionState.PROMPT_WITH_RATIONALE.toString())
                } else {
                    // Permission denied permanently, store this state for future reference
                    editor.putString(permString, PermissionState.DENIED.toString())
                }

                editor.apply()
            }
        }

        val permStrings = permissions.keys.toTypedArray()

        if (!PermissionHelper.hasDefinedPermissions(context, permStrings)) {
            val message =
                buildString {
                    appendLine("Missing the following permissions in AndroidManifest.xml:")
                    PermissionHelper.getUndefinedPermissions(context, permStrings).forEach { appendLine(it) }
                }
            // The Java original threw when no call had been saved for the request; there is nothing to reject then.
            savedCall?.reject(message)
            return false
        }

        return true
    }

    /**
     * Helper to check all permissions and see the current states of each permission.
     *
     * @since 3.0.0
     * @return A mapping of permission aliases to the associated granted status.
     */
    internal fun getPermissionStates(plugin: Plugin): Map<String, PermissionState> {
        val permissionsResults = HashMap<String, PermissionState>()
        // PluginHandle's init throws InvalidPluginException when the annotation is missing, so it is always present here.
        for (perm in plugin.pluginHandle.pluginAnnotation.permissions) {
            // If a permission is defined with no permission constants, return GRANTED for it.
            // Otherwise, get its true state.
            if (perm.strings.isEmpty() || (perm.strings.size == 1 && perm.strings[0].isEmpty())) {
                val key = perm.alias
                if (key.isNotEmpty()) {
                    val existingResult = permissionsResults[key]

                    // auto set permission state to GRANTED if the alias is empty.
                    if (existingResult == null) {
                        permissionsResults[key] = PermissionState.GRANTED
                    }
                }
            } else {
                for (permString in perm.strings) {
                    val key = if (perm.alias.isEmpty()) permString else perm.alias
                    var permissionStatus: PermissionState
                    if (ActivityCompat.checkSelfPermission(context, permString) == PackageManager.PERMISSION_GRANTED) {
                        permissionStatus = PermissionState.GRANTED
                    } else {
                        permissionStatus = PermissionState.PROMPT

                        // Check if there is a cached permission state for the "Never ask again" state
                        val prefs = context.getSharedPreferences(PERMISSION_PREFS_NAME, Activity.MODE_PRIVATE)
                        val state = prefs.getString(permString, null)

                        if (state != null) {
                            permissionStatus = PermissionState.byState(state)
                        }
                    }

                    val existingResult = permissionsResults[key]

                    // multiple permissions with the same alias must all be true, otherwise all false.
                    if (existingResult == null || existingResult == PermissionState.GRANTED) {
                        permissionsResults[key] = permissionStatus
                    }
                }
            }
        }

        return permissionsResults
    }

    /**
     * Handle an onNewIntent lifecycle event and notify the plugins
     */
    public fun onNewIntent(intent: Intent?) {
        eachPlugin { it.dispatchOnNewIntent(intent) }
    }

    /**
     * Handle an onConfigurationChanged event and notify the plugins
     */
    public fun onConfigurationChanged(newConfig: Configuration?) {
        eachPlugin { it.dispatchOnConfigurationChanged(newConfig) }
    }

    /**
     * Handle onRestart lifecycle event and notify the plugins
     */
    public fun onRestart() {
        eachPlugin { it.dispatchOnRestart() }
    }

    /**
     * Handle onStart lifecycle event and notify the plugins
     */
    public fun onStart() {
        eachPlugin { it.dispatchOnStart() }
    }

    /**
     * Handle onResume lifecycle event and notify the plugins
     */
    public fun onResume() {
        eachPlugin { it.dispatchOnResume() }
    }

    /**
     * Handle onPause lifecycle event and notify the plugins
     */
    public fun onPause() {
        eachPlugin { it.dispatchOnPause() }
    }

    /**
     * Handle onStop lifecycle event and notify the plugins
     */
    public fun onStop() {
        eachPlugin { it.dispatchOnStop() }
    }

    /**
     * Handle onDestroy lifecycle event and notify the plugins
     */
    public fun onDestroy() {
        eachPlugin { it.dispatchOnDestroy() }

        handlerThread.quitSafely()
    }

    /**
     * Handle onDetachedFromWindow lifecycle event
     */
    public fun onDetachedFromWindow() {
        webView.removeAllViews()
        webView.destroy()
    }

    /**
     * The path the local server serves from. Setting it tells the local server to load files from the given
     * file path instead of the assets path, and reloads the app.
     */
    public var serverBasePath: String?
        get() = localServer.basePath
        set(path) {
            localServer.hostFiles(path)
            loadAppUrl()
        }

    /**
     * Tell the local server to load files from the given
     * asset path.
     */
    public fun setServerAssetPath(path: String?) {
        localServer.hostAssets(path)
        loadAppUrl()
    }

    /**
     * Reload the WebView
     */
    public fun reload() {
        loadAppUrl()
    }

    // Same as the Java original: appUrl is read when the posted task runs, and a missing appUrl throws there.
    private fun loadAppUrl() {
        webView.post { webView.loadUrl(appUrl!!) }
    }

    /**
     * Add a listener that the WebViewClient can trigger on certain events.
     * @param webViewListener A [WebViewListener] to add.
     */
    public fun addWebViewListener(webViewListener: WebViewListener) {
        webViewListeners.add(webViewListener)
    }

    /**
     * Remove a listener that the WebViewClient triggers on certain events.
     * @param webViewListener A [WebViewListener] to remove.
     */
    public fun removeWebViewListener(webViewListener: WebViewListener) {
        webViewListeners.remove(webViewListener)
    }

    public class Builder {
        private var instanceState: Bundle? = null
        private var config: CapConfig? = null
        private var plugins: MutableList<Class<out Plugin>> = ArrayList()
        private val pluginInstances: MutableList<Plugin> = ArrayList()
        private val activity: AppCompatActivity?
        private var fragment: Fragment? = null
        private var routeProcessor: RouteProcessor? = null
        private val webViewListeners: MutableList<WebViewListener> = ArrayList()
        private var serverPath: ServerPath? = null

        public constructor(activity: AppCompatActivity) {
            this.activity = activity
        }

        public constructor(fragment: Fragment) {
            this.activity = fragment.activity as AppCompatActivity?
            this.fragment = fragment
        }

        public fun setInstanceState(instanceState: Bundle?): Builder {
            this.instanceState = instanceState
            return this
        }

        public fun setConfig(config: CapConfig?): Builder {
            this.config = config
            return this
        }

        public fun setPlugins(plugins: List<Class<out Plugin>>): Builder {
            this.plugins = plugins.toMutableList()
            return this
        }

        public fun addPlugin(plugin: Class<out Plugin>): Builder {
            plugins.add(plugin)
            return this
        }

        public fun addPlugins(plugins: List<Class<out Plugin>>): Builder {
            for (cls in plugins) {
                addPlugin(cls)
            }

            return this
        }

        public fun addPluginInstance(plugin: Plugin): Builder {
            pluginInstances.add(plugin)
            return this
        }

        public fun addPluginInstances(plugins: List<Plugin>): Builder {
            pluginInstances.addAll(plugins)
            return this
        }

        public fun addWebViewListener(webViewListener: WebViewListener): Builder {
            webViewListeners.add(webViewListener)
            return this
        }

        public fun addWebViewListeners(webViewListeners: List<WebViewListener>): Builder {
            for (listener in webViewListeners) {
                addWebViewListener(listener)
            }

            return this
        }

        public fun setRouteProcessor(routeProcessor: RouteProcessor?): Builder {
            this.routeProcessor = routeProcessor
            return this
        }

        public fun setServerPath(serverPath: ServerPath?): Builder {
            this.serverPath = serverPath
            return this
        }

        public fun create(): Bridge {
            // Same as the Java original: a fragment without a view or a detached fragment throws here.
            val fragment = fragment
            val activity = activity!!
            val webView: WebView =
                if (fragment != null) fragment.requireView().findViewById(R.id.webview) else activity.findViewById(R.id.webview)

            val bridge = Bridge(activity, serverPath, fragment, webView, plugins, pluginInstances, config)

            if (webView is CapacitorWebView) {
                webView.setBridge(bridge)
            }

            bridge.webViewListeners = webViewListeners
            bridge.routeProcessor = routeProcessor

            val instanceState = instanceState
            if (instanceState != null) {
                bridge.restoreInstanceState(instanceState)
            }

            return bridge
        }
    }

    public companion object {
        private const val PERMISSION_PREFS_NAME = "PluginPermStates"
        private const val BUNDLE_LAST_PLUGIN_ID_KEY = "capacitorLastActivityPluginId"
        private const val BUNDLE_LAST_PLUGIN_CALL_METHOD_NAME_KEY = "capacitorLastActivityPluginMethod"
        private const val BUNDLE_PLUGIN_CALL_OPTIONS_SAVED_KEY = "capacitorLastPluginCallOptions"
        private const val BUNDLE_PLUGIN_CALL_BUNDLE_KEY = "capacitorLastPluginCallBundle"
        private const val LAST_BINARY_VERSION_CODE = "lastBinaryVersionCode"
        private const val LAST_BINARY_VERSION_NAME = "lastBinaryVersionName"
        private const val MINIMUM_ANDROID_WEBVIEW_ERROR = "System WebView is not supported"

        // The major version at the front of a WebView package's version name
        private val WEBVIEW_MAJOR_VERSION: Regex = Regex("\\d+")

        // The name of the directory we use to look for index.html and the rest of our web assets
        public const val DEFAULT_WEB_ASSET_DIR: String = "public"
        public const val CAPACITOR_HTTP_SCHEME: String = "http"
        public const val CAPACITOR_HTTPS_SCHEME: String = "https"
        public const val CAPACITOR_FILE_START: String = "/_capacitor_file_"
        public const val CAPACITOR_CONTENT_START: String = "/_capacitor_content_"
        public const val CAPACITOR_HTTP_INTERCEPTOR_START: String = "/_capacitor_http_interceptor_"

        public const val CAPACITOR_HTTP_INTERCEPTOR_URL_PARAM: String = "u"

        public const val DEFAULT_ANDROID_WEBVIEW_VERSION: Int = 60
        public const val MINIMUM_ANDROID_WEBVIEW_VERSION: Int = 55
        public const val DEFAULT_HUAWEI_WEBVIEW_VERSION: Int = 10
        public const val MINIMUM_HUAWEI_WEBVIEW_VERSION: Int = 10
    }
}
