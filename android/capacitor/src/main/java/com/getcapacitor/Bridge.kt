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
import org.json.JSONException
import java.io.File
import java.net.SocketTimeoutException
import java.net.URL
import java.util.LinkedList
import java.util.regex.Pattern

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
 * see the source for BridgeActivity for the methods you'll need to
 * pass through to Bridge:
 * [BridgeActivity](https://github.com/ionic-team/capacitor/blob/HEAD/android/capacitor/src/main/java/com/getcapacitor/BridgeActivity.java)
 */
class Bridge private constructor(
    /**
     * Get the activity for the app
     */
    val activity: AppCompatActivity,
    // A pre-determined path to load the bridge
    internal val serverPath: ServerPath?,
    /**
     * Get the fragment for the app, if applicable. This will likely be null unless Capacitor
     * is being used embedded in a Native Android app.
     */
    val fragment: Fragment?,
    /**
     * Get the core WebView under Capacitor's control
     */
    val webView: WebView,
    private val initialPlugins: List<Class<out Plugin>>,
    private val pluginInstances: List<Plugin>,
    config: CapConfig?,
) {
    // Loaded Capacitor config
    val config: CapConfig

    lateinit var localServer: WebViewLocalServer
        private set
    var localUrl: String? = null
        private set
    var appUrl: String? = null
        private set
    private var appUrlConfig: String? = null
    lateinit var appAllowNavigationMask: HostMask
        private set
    val allowedOriginRules: MutableSet<String> = HashSet()
    private val authorities = ArrayList<String?>()
    private var miscJSFileInjections = ArrayList<String>()
    private var canInjectJS = true

    /**
     * The WebViewClient in use. Setting it also installs it on the WebView.
     */
    var webViewClient: BridgeWebViewClient = BridgeWebViewClient(this)
        set(client) {
            field = client
            webView.webViewClient = client
        }

    val app: App = App()

    // Our MessageHandler for sending and receiving data to the WebView
    private val msgHandler: MessageHandler

    // The ThreadHandler for executing plugin calls
    private val handlerThread = HandlerThread("CapacitorPlugins")

    // Our Handler for posting plugin calls. Created from the ThreadHandler
    private val taskHandler: Handler

    // A map of Plugin Id's to PluginHandle's
    private val plugins: MutableMap<String, PluginHandle> = HashMap()

    // Stored plugin calls that we're keeping around to call again someday
    private var savedCalls: MutableMap<String?, PluginCall> = HashMap()

    // The call IDs of saved plugin calls with associated plugin id for handling permissions
    private val savedPermissionCallIds: MutableMap<String?, LinkedList<String?>> = HashMap()

    // Store a plugin that started a new activity, in case we need to resume
    // the app and return that data back
    private var pluginCallForLastActivity: PluginCall? = null

    /**
     * Get the URI that was used to launch the app (if any)
     */
    val intentUri: Uri?

    // A list of listeners that trigger when webView events occur
    internal var webViewListeners: MutableList<WebViewListener> = ArrayList()

    // An interface to manipulate route resolving
    internal var routeProcessor: RouteProcessor? = null

    init {
        // Start our plugin execution threads and handlers
        handlerThread.start()
        taskHandler = Handler(handlerThread.looper)

        this.config = config ?: CapConfig.loadDefault(activity)
        Logger.init(this.config)

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
        val html5mode = config.isHTML5Mode()

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

        if (config.isResolveServiceWorkerRequests()) {
            val swController = ServiceWorkerController.getInstance()
            swController.setServiceWorkerClient(
                object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest?): WebResourceResponse? {
                        if (request == null) return null
                        return localServer.shouldInterceptRequest(request)
                    }
                },
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

    fun isMinimumWebViewInstalled(): Boolean {
        val info = WebView.getCurrentWebViewPackage() ?: return false
        val pattern = Pattern.compile("(\\d+)")
        // The Java original threw on a WebView package without a version name; it now counts as unsupported.
        val matcher = pattern.matcher(info.versionName ?: return false)
        if (matcher.find()) {
            // Never null once find() has succeeded.
            val majorVersionStr = matcher.group(0) ?: return false
            val majorVersion = Integer.parseInt(majorVersionStr)
            if (info.packageName == "com.huawei.webview") {
                return majorVersion >= config.minHuaweiWebViewVersion
            }
            return majorVersion >= config.minWebViewVersion
        }
        return false
    }

    fun launchIntent(url: Uri): Boolean {
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

    fun handleAppUrlLoadError(ex: Exception?) {
        if (ex is SocketTimeoutException) {
            Logger.error(
                "Unable to load app. Ensure the server is running at " +
                    appUrl +
                    ", or modify the " +
                    "appUrl setting in capacitor.config.json (make sure to npx cap copy after to commit changes).",
                ex,
            )
        }
    }

    val isDevMode: Boolean
        get() = (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Get the Context for the App
     */
    val context: Context
        get() = activity

    /**
     * Get scheme that is used to serve content
     */
    val scheme: String
        get() = config.androidScheme

    /**
     * Get host name that is used to serve content
     */
    val host: String?
        get() = config.hostname

    /**
     * Get the server url that is used to serve content
     */
    val serverUrl: String?
        get() = config.serverUrl

    val errorUrl: String?
        get() {
            val errorPath = config.errorPath

            // trim { it <= ' ' } is java.lang.String.trim().
            if (errorPath != null && errorPath.trim { it <= ' ' }.isNotEmpty()) {
                val authority = host
                val scheme = scheme

                val localUrl = "$scheme://$authority"

                return "$localUrl/$errorPath"
            }

            return null
        }

    fun reset() {
        savedCalls = HashMap()
        for (handle in plugins.values) {
            handle.instance.removeAllListeners()
        }
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
        if (config.isMixedContentAllowed()) {
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
        settings.builtInZoomControls = config.isZoomableWebView()

        if (config.isInitialFocus()) {
            webView.requestFocusFromTouch()
        }

        WebView.setWebContentsDebuggingEnabled(config.isWebContentsDebuggingEnabled())

        appUrlConfig = serverUrl
        val authority = host
        authorities.add(authority)
        val scheme = scheme

        localUrl = "$scheme://$authority"

        val appUrlConfig = appUrlConfig
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
    fun registerPlugins(pluginClasses: Array<Class<out Plugin>>) {
        for (plugin in pluginClasses) {
            registerPlugin(plugin)
        }
    }

    fun registerPluginInstances(pluginInstances: Array<Plugin>) {
        for (plugin in pluginInstances) {
            registerPluginInstance(plugin)
        }
    }

    /**
     * Register a plugin class
     * @param pluginClass a class inheriting from Plugin
     */
    fun registerPlugin(pluginClass: Class<out Plugin>) {
        val pluginId = pluginId(pluginClass) ?: return

        try {
            plugins[pluginId] = PluginHandle(this, pluginClass)
        } catch (ex: InvalidPluginException) {
            logInvalidPluginException(pluginClass)
        } catch (ex: PluginLoadException) {
            logPluginLoadException(pluginClass, ex)
        }
    }

    fun registerPluginInstance(plugin: Plugin) {
        val clazz = plugin.javaClass
        val pluginId = pluginId(clazz) ?: return

        try {
            plugins[pluginId] = PluginHandle(this, plugin)
        } catch (ex: InvalidPluginException) {
            logInvalidPluginException(clazz)
        }
    }

    private fun pluginId(clazz: Class<out Plugin>): String? {
        val pluginName = pluginName(clazz)
        var pluginId = clazz.simpleName
        if (pluginName == null) return null

        if (pluginName != "") {
            pluginId = pluginName
        }
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
                " the class extends Plugin",
        )
    }

    private fun logPluginLoadException(clazz: Class<out Plugin>, ex: Exception) {
        Logger.error("Plugin " + clazz.name + " failed to load", ex)
    }

    fun getPlugin(pluginId: String?): PluginHandle? = plugins[pluginId]

    /**
     * Call a method on a plugin.
     * @param pluginId the plugin id to use to lookup the plugin handle
     * @param methodName the name of the method to call
     * @param call the call object to pass to the method
     */
    fun callPluginMethod(pluginId: String?, methodName: String?, call: PluginCall) {
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
                        call.data.toString(),
                )
            }

            val currentThreadTask =
                Runnable {
                    try {
                        plugin.invoke(methodName, call)

                        if (call.isKeptAlive()) {
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
    fun eval(js: String, callback: ValueCallback<String>?) {
        val mainHandler = Handler(activity.mainLooper)
        mainHandler.post { webView.evaluateJavascript(js, callback) }
    }

    fun logToJs(message: String?, level: String?) {
        eval("window.Capacitor.logJs(\"$message\", \"$level\")", null)
    }

    fun logToJs(message: String?) {
        logToJs(message, "log")
    }

    fun triggerJSEvent(eventName: String?, target: String?) {
        eval("window.Capacitor.triggerEvent(\"$eventName\", \"$target\")") { }
    }

    fun triggerJSEvent(eventName: String?, target: String?, data: String?) {
        eval("window.Capacitor.triggerEvent(\"$eventName\", \"$target\", $data)") { }
    }

    fun triggerWindowJSEvent(eventName: String?) {
        triggerJSEvent(eventName, "window")
    }

    fun triggerWindowJSEvent(eventName: String?, data: String?) {
        triggerJSEvent(eventName, "window", data)
    }

    fun triggerDocumentJSEvent(eventName: String?) {
        triggerJSEvent(eventName, "document")
    }

    fun triggerDocumentJSEvent(eventName: String?, data: String?) {
        triggerJSEvent(eventName, "document", data)
    }

    fun execute(runnable: Runnable) {
        taskHandler.post(runnable)
    }

    fun executeOnMainThread(runnable: Runnable) {
        val mainHandler = Handler(activity.mainLooper)

        mainHandler.post(runnable)
    }

    /**
     * Retain a call between plugin invocations
     */
    fun saveCall(call: PluginCall) {
        savedCalls[call.callbackId] = call
    }

    /**
     * Get a retained plugin call
     * @param callbackId the callbackId to use to lookup the call with
     * @return the stored call
     */
    fun getSavedCall(callbackId: String?): PluginCall? {
        if (callbackId == null) {
            return null
        }

        return savedCalls[callbackId]
    }

    // Not a property: reading it clears it.
    internal fun getPluginCallForLastActivity(): PluginCall? {
        val pluginCallForLastActivity = this.pluginCallForLastActivity
        this.pluginCallForLastActivity = null
        return pluginCallForLastActivity
    }

    internal fun setPluginCallForLastActivity(pluginCallForLastActivity: PluginCall?) {
        this.pluginCallForLastActivity = pluginCallForLastActivity
    }

    /**
     * Release a retained call
     * @param call a call to release
     */
    fun releaseCall(call: PluginCall) {
        releaseCall(call.callbackId)
    }

    /**
     * Release a retained call by its ID
     * @param callbackId an ID of a callback to release
     */
    fun releaseCall(callbackId: String?) {
        savedCalls.remove(callbackId)
    }

    /**
     * Removes the earliest saved call prior to a permissions request for a given plugin and
     * returns it.
     *
     * @return The saved plugin call
     */
    internal fun getPermissionCall(pluginId: String?): PluginCall? {
        val permissionCallIds = savedPermissionCallIds[pluginId]
        var savedCallId: String? = null
        if (permissionCallIds != null) {
            savedCallId = permissionCallIds.poll()
        }

        return getSavedCall(savedCallId)
    }

    /**
     * Save a call to be retrieved after requesting permissions. Calls are saved in order.
     *
     * @param call The plugin call to save.
     */
    internal fun savePermissionCall(call: PluginCall?) {
        if (call != null) {
            val callIds = savedPermissionCallIds.getOrPut(call.pluginId) { LinkedList() }

            callIds.add(call.callbackId)
            saveCall(call)
        }
    }

    /**
     * Register an Activity Result Launcher to the containing Fragment or Activity.
     *
     * @param contract A contract specifying that an activity can be called with an input of
     * type I and produce an output of type O.
     * @param callback The callback run on Activity Result.
     * @return A registered Activity Result Launcher.
     */
    fun <I, O> registerForActivityResult(contract: ActivityResultContract<I, O>, callback: ActivityResultCallback<O>): ActivityResultLauncher<I> =
        if (fragment != null) {
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
            val globalJS = JSExport.getGlobalJS(activity, config.isLoggingEnabled(), isDevMode)
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
    fun injectScriptBeforeLoad(path: String) {
        if (canInjectJS) {
            miscJSFileInjections.add(path)
        }
    }

    /**
     * Restore any saved bundle state data
     */
    fun restoreInstanceState(savedInstanceState: Bundle) {
        val lastPluginId = savedInstanceState.getString(BUNDLE_LAST_PLUGIN_ID_KEY)
        val lastPluginCallMethod = savedInstanceState.getString(BUNDLE_LAST_PLUGIN_CALL_METHOD_NAME_KEY)
        val lastOptionsJson = savedInstanceState.getString(BUNDLE_PLUGIN_CALL_OPTIONS_SAVED_KEY)

        if (lastPluginId != null) {
            // If we have JSON blob saved, create a new plugin call with the original options
            if (lastOptionsJson != null) {
                try {
                    val options = JSObject(lastOptionsJson)

                    pluginCallForLastActivity =
                        PluginCall(msgHandler, lastPluginId, PluginCall.CALLBACK_ID_DANGLING, lastPluginCallMethod, options)
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

    fun saveInstanceState(outState: Bundle) {
        Logger.debug("Saving instance state!")

        // If there was a last PluginCall for a started activity, we need to
        // persist it so we can load it again in case our app gets terminated
        val call = pluginCallForLastActivity
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

        for (permission in permissions.entries) {
            val permString = permission.key
            val isGranted = permission.value

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
            val builder = StringBuilder()
            builder.append("Missing the following permissions in AndroidManifest.xml:\n")
            val missing = PermissionHelper.getUndefinedPermissions(context, permStrings)
            for (perm in missing) {
                builder.append(perm + "\n")
            }
            // The Java original threw when no call had been saved for the request; there is nothing to reject then.
            savedCall?.reject(builder.toString())
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
        val annotation: CapacitorPlugin? = plugin.pluginHandle.pluginAnnotation
        if (annotation != null) {
            for (perm in annotation.permissions) {
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
        } else {
            Logger.warn(String.format("getPermissionStates: missing @CapacitorPlugin annotation for plugin %s", plugin.pluginHandle.id))
        }

        return permissionsResults
    }

    /**
     * Handle an onNewIntent lifecycle event and notify the plugins
     */
    fun onNewIntent(intent: Intent?) {
        for (plugin in plugins.values) {
            plugin.instance.dispatchOnNewIntent(intent)
        }
    }

    /**
     * Handle an onConfigurationChanged event and notify the plugins
     */
    fun onConfigurationChanged(newConfig: Configuration?) {
        for (plugin in plugins.values) {
            plugin.instance.dispatchOnConfigurationChanged(newConfig)
        }
    }

    /**
     * Handle onRestart lifecycle event and notify the plugins
     */
    fun onRestart() {
        for (plugin in plugins.values) {
            plugin.instance.dispatchOnRestart()
        }
    }

    /**
     * Handle onStart lifecycle event and notify the plugins
     */
    fun onStart() {
        for (plugin in plugins.values) {
            plugin.instance.dispatchOnStart()
        }
    }

    /**
     * Handle onResume lifecycle event and notify the plugins
     */
    fun onResume() {
        for (plugin in plugins.values) {
            plugin.instance.dispatchOnResume()
        }
    }

    /**
     * Handle onPause lifecycle event and notify the plugins
     */
    fun onPause() {
        for (plugin in plugins.values) {
            plugin.instance.dispatchOnPause()
        }
    }

    /**
     * Handle onStop lifecycle event and notify the plugins
     */
    fun onStop() {
        for (plugin in plugins.values) {
            plugin.instance.dispatchOnStop()
        }
    }

    /**
     * Handle onDestroy lifecycle event and notify the plugins
     */
    fun onDestroy() {
        for (plugin in plugins.values) {
            plugin.instance.dispatchOnDestroy()
        }

        handlerThread.quitSafely()
    }

    /**
     * Handle onDetachedFromWindow lifecycle event
     */
    fun onDetachedFromWindow() {
        webView.removeAllViews()
        webView.destroy()
    }

    /**
     * The path the local server serves from. Setting it tells the local server to load files from the given
     * file path instead of the assets path, and reloads the app.
     */
    var serverBasePath: String?
        get() = localServer.basePath
        set(path) {
            localServer.hostFiles(path)
            loadAppUrl()
        }

    /**
     * Tell the local server to load files from the given
     * asset path.
     */
    fun setServerAssetPath(path: String?) {
        localServer.hostAssets(path)
        loadAppUrl()
    }

    /**
     * Reload the WebView
     */
    fun reload() {
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
    fun addWebViewListener(webViewListener: WebViewListener) {
        webViewListeners.add(webViewListener)
    }

    /**
     * Remove a listener that the WebViewClient triggers on certain events.
     * @param webViewListener A [WebViewListener] to remove.
     */
    fun removeWebViewListener(webViewListener: WebViewListener) {
        webViewListeners.remove(webViewListener)
    }

    class Builder {
        private var instanceState: Bundle? = null
        private var config: CapConfig? = null
        private var plugins: MutableList<Class<out Plugin>> = ArrayList()
        private val pluginInstances: MutableList<Plugin> = ArrayList()
        private val activity: AppCompatActivity?
        private var fragment: Fragment? = null
        private var routeProcessor: RouteProcessor? = null
        private val webViewListeners: MutableList<WebViewListener> = ArrayList()
        private var serverPath: ServerPath? = null

        constructor(activity: AppCompatActivity) {
            this.activity = activity
        }

        constructor(fragment: Fragment) {
            this.activity = fragment.activity as AppCompatActivity?
            this.fragment = fragment
        }

        fun setInstanceState(instanceState: Bundle?): Builder {
            this.instanceState = instanceState
            return this
        }

        fun setConfig(config: CapConfig?): Builder {
            this.config = config
            return this
        }

        fun setPlugins(plugins: MutableList<Class<out Plugin>>): Builder {
            this.plugins = plugins
            return this
        }

        fun addPlugin(plugin: Class<out Plugin>): Builder {
            plugins.add(plugin)
            return this
        }

        fun addPlugins(plugins: List<Class<out Plugin>>): Builder {
            for (cls in plugins) {
                addPlugin(cls)
            }

            return this
        }

        fun addPluginInstance(plugin: Plugin): Builder {
            pluginInstances.add(plugin)
            return this
        }

        fun addPluginInstances(plugins: List<Plugin>): Builder {
            pluginInstances.addAll(plugins)
            return this
        }

        fun addWebViewListener(webViewListener: WebViewListener): Builder {
            webViewListeners.add(webViewListener)
            return this
        }

        fun addWebViewListeners(webViewListeners: List<WebViewListener>): Builder {
            for (listener in webViewListeners) {
                addWebViewListener(listener)
            }

            return this
        }

        fun setRouteProcessor(routeProcessor: RouteProcessor?): Builder {
            this.routeProcessor = routeProcessor
            return this
        }

        fun setServerPath(serverPath: ServerPath?): Builder {
            this.serverPath = serverPath
            return this
        }

        fun create(): Bridge {
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

    companion object {
        private const val PERMISSION_PREFS_NAME = "PluginPermStates"
        private const val BUNDLE_LAST_PLUGIN_ID_KEY = "capacitorLastActivityPluginId"
        private const val BUNDLE_LAST_PLUGIN_CALL_METHOD_NAME_KEY = "capacitorLastActivityPluginMethod"
        private const val BUNDLE_PLUGIN_CALL_OPTIONS_SAVED_KEY = "capacitorLastPluginCallOptions"
        private const val BUNDLE_PLUGIN_CALL_BUNDLE_KEY = "capacitorLastPluginCallBundle"
        private const val LAST_BINARY_VERSION_CODE = "lastBinaryVersionCode"
        private const val LAST_BINARY_VERSION_NAME = "lastBinaryVersionName"
        private const val MINIMUM_ANDROID_WEBVIEW_ERROR = "System WebView is not supported"

        // The name of the directory we use to look for index.html and the rest of our web assets
        const val DEFAULT_WEB_ASSET_DIR = "public"
        const val CAPACITOR_HTTP_SCHEME = "http"
        const val CAPACITOR_HTTPS_SCHEME = "https"
        const val CAPACITOR_FILE_START = "/_capacitor_file_"
        const val CAPACITOR_CONTENT_START = "/_capacitor_content_"
        const val CAPACITOR_HTTP_INTERCEPTOR_START = "/_capacitor_http_interceptor_"

        const val CAPACITOR_HTTP_INTERCEPTOR_URL_PARAM = "u"

        const val DEFAULT_ANDROID_WEBVIEW_VERSION = 60
        const val MINIMUM_ANDROID_WEBVIEW_VERSION = 55
        const val DEFAULT_HUAWEI_WEBVIEW_VERSION = 10
        const val MINIMUM_HUAWEI_WEBVIEW_VERSION = 10
    }
}
