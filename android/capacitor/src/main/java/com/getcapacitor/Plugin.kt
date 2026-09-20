package com.getcapacitor

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.PermissionCallback
import com.getcapacitor.util.PermissionHelper
import java.lang.reflect.Method
import org.json.JSONException

/**
 * Plugin is the base class for all plugins, containing a number of
 * convenient features for interacting with the [Bridge], managing
 * plugin permissions, tracking lifecycle events, and more.
 *
 * You should inherit from this class when creating new plugins, along with
 * adding the [CapacitorPlugin] annotation to add additional required
 * metadata about the Plugin
 */
public open class Plugin {
    /**
     * Reference to the Bridge. Set by [PluginHandle] before [load] is called.
     *
     * Java sees the `bridge` field plus `getBridge()`/`setBridge(Bridge)`.
     */
    public lateinit var bridge: Bridge

    /**
     * Reference to the [PluginHandle] wrapper for this Plugin. Set by [PluginHandle] before [load] is called.
     *
     * Java subclasses see the protected `handle` field.
     */
    protected lateinit var handle: PluginHandle

    // Stored event listeners
    private val eventListeners: MutableMap<String?, MutableList<PluginCall>> = HashMap()

    /**
     * Launchers used by the plugin to handle activity results
     */
    private val activityLaunchers: MutableMap<String, ActivityResultLauncher<Intent>> = HashMap()

    /**
     * Launchers used by the plugin to handle permission results
     */
    private val permissionLaunchers: MutableMap<String, ActivityResultLauncher<Array<String>>> = HashMap()

    private var lastPluginCallId: String? = null

    // Stored results of an event if an event was fired and
    // no listeners were attached yet. Only stores the last value.
    private val retainedEventArguments: MutableMap<String?, MutableList<JSObject?>> = HashMap()

    /**
     * Called when the plugin has been connected to the bridge
     * and is ready to start initializing.
     */
    public open fun load() {}

    /**
     * Registers activity result launchers defined on plugins, used for permission requests and
     * activities started for result.
     */
    internal fun initializeActivityLaunchers() {
        val pluginClassMethods = ArrayList<Method>()
        var pluginCursor: Class<*>? = javaClass
        while (pluginCursor != null && pluginCursor.name != Any::class.java.name) {
            pluginClassMethods.addAll(pluginCursor.declaredMethods)
            pluginCursor = pluginCursor.superclass
        }

        for (method in pluginClassMethods) {
            if (method.isAnnotationPresent(ActivityCallback::class.java)) {
                // register callbacks annotated with ActivityCallback for activity results
                val launcher =
                    bridge.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                        triggerActivityCallback(method, result)
                    }

                activityLaunchers[method.name] = launcher
            } else if (method.isAnnotationPresent(PermissionCallback::class.java)) {
                // register callbacks annotated with PermissionCallback for permission results
                val launcher =
                    bridge.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                        triggerPermissionCallback(method, permissions)
                    }

                permissionLaunchers[method.name] = launcher
            }
        }
    }

    private fun triggerPermissionCallback(method: Method, permissionResultMap: Map<String, Boolean>) {
        val savedCall = bridge.getPermissionCall(handle.id)

        // validate permissions and invoke the permission result callback
        if (bridge.validatePermissions(this, savedCall, permissionResultMap)) {
            try {
                method.isAccessible = true
                method.invoke(this, savedCall)
            } catch (e: ReflectiveOperationException) {
                // Method.invoke only declares IllegalAccessException and InvocationTargetException here.
                e.printStackTrace()
            }
        }
    }

    private fun triggerActivityCallback(method: Method, result: ActivityResult?) {
        val savedCall = bridge.getSavedCall(lastPluginCallId) ?: bridge.getPluginCallForLastActivity()

        // invoke the activity result callback
        try {
            method.isAccessible = true
            method.invoke(this, savedCall, result)
        } catch (e: ReflectiveOperationException) {
            // Method.invoke only declares IllegalAccessException and InvocationTargetException here.
            e.printStackTrace()
        }
    }

    /**
     * Start activity for result with the provided Intent and resolve with the provided callback method name.
     *
     * If there is no registered activity callback for the method name passed in, the call will
     * be rejected. Make sure a valid activity result callback method is registered using the
     * [ActivityCallback] annotation.
     *
     * @param call the plugin call
     * @param intent the intent used to start an activity
     * @param callbackName the name of the callback to run when the launched activity is finished
     * @since 3.0.0
     */
    public open fun startActivityForResult(call: PluginCall, intent: Intent, callbackName: String?) {
        // return when null since call was rejected in getLauncherOrReject
        val activityResultLauncher = getActivityLauncherOrReject(call, callbackName) ?: return
        bridge.setPluginCallForLastActivity(call)
        lastPluginCallId = call.callbackId
        bridge.saveCall(call)
        activityResultLauncher.launch(intent)
    }

    private fun permissionActivityResult(call: PluginCall, permissionStrings: Array<String>, callbackName: String?) {
        // return when null since call was rejected in getLauncherOrReject
        val permissionResultLauncher = getPermissionLauncherOrReject(call, callbackName) ?: return

        bridge.savePermissionCall(call)
        permissionResultLauncher.launch(permissionStrings)
    }

    /**
     * Get the main [Context] for the current Activity (your app)
     */
    public val context: Context
        get() = bridge.context

    /**
     * Get the main Activity for the app
     */
    public val activity: AppCompatActivity
        get() = bridge.activity

    /**
     * The wrapper [PluginHandle] instance for this plugin that
     * contains additional metadata about the Plugin instance (such
     * as indexed methods for reflection, and [CapacitorPlugin] annotation data).
     */
    public var pluginHandle: PluginHandle
        get() = handle
        set(pluginHandle) {
            handle = pluginHandle
        }

    /**
     * Get the root App ID
     */
    public val appId: String
        get() = context.packageName

    /**
     * Get the config options for this plugin.
     *
     * @return a config object representing the plugin config options, or an empty config
     * if none exists
     */
    public val config: PluginConfig
        get() = bridge.config.getPluginConfiguration(handle.id)

    /**
     * Checks if the given permission alias is correctly declared in AndroidManifest.xml
     * @param alias a permission alias defined on the plugin
     * @return true only if all permissions associated with the given alias are declared in the manifest
     */
    public open fun isPermissionDeclared(alias: String): Boolean {
        for (perm in handle.pluginAnnotation.permissions) {
            if (alias.equals(perm.alias, ignoreCase = true)) {
                var result = true
                for (permString in perm.strings) {
                    result = result && PermissionHelper.hasDefinedPermission(context, permString)
                }

                return result
            }
        }

        Logger.error(String.format("isPermissionDeclared: No alias defined for %s " + "or missing @CapacitorPlugin annotation.", alias))
        return false
    }

    /**
     * Request all of the specified permissions in the CapacitorPlugin annotation (if any)
     *
     * If there is no registered permission callback for the PluginCall passed in, the call will
     * be rejected. Make sure a valid permission callback method is registered using the
     * [PermissionCallback] annotation.
     *
     * @since 3.0.0
     * @param call the plugin call
     * @param callbackName the name of the callback to run when the permission request is complete
     */
    protected open fun requestAllPermissions(call: PluginCall, callbackName: String) {
        val perms = HashSet<String>()
        for (perm in handle.pluginAnnotation.permissions) {
            perms.addAll(perm.strings)
        }

        permissionActivityResult(call, perms.toTypedArray(), callbackName)
    }

    /**
     * Request permissions using an alias defined on the plugin.
     *
     * If there is no registered permission callback for the PluginCall passed in, the call will
     * be rejected. Make sure a valid permission callback method is registered using the
     * [PermissionCallback] annotation.
     *
     * @param alias an alias defined on the plugin
     * @param call the plugin call involved in originating the request
     * @param callbackName the name of the callback to run when the permission request is complete
     */
    protected open fun requestPermissionForAlias(alias: String, call: PluginCall, callbackName: String) {
        requestPermissionForAliases(arrayOf(alias), call, callbackName)
    }

    /**
     * Request permissions using aliases defined on the plugin.
     *
     * If there is no registered permission callback for the PluginCall passed in, the call will
     * be rejected. Make sure a valid permission callback method is registered using the
     * [PermissionCallback] annotation.
     *
     * @param aliases a set of aliases defined on the plugin
     * @param call the plugin call involved in originating the request
     * @param callbackName the name of the callback to run when the permission request is complete
     */
    protected open fun requestPermissionForAliases(aliases: Array<String>, call: PluginCall, callbackName: String) {
        if (aliases.isEmpty()) {
            Logger.error("No permission alias was provided")
            return
        }

        val permissions = getPermissionStringsForAliases(aliases)

        if (permissions.isNotEmpty()) {
            permissionActivityResult(call, permissions, callbackName)
        }
    }

    /**
     * Gets the Android permission strings defined on the [CapacitorPlugin] annotation with
     * the provided aliases.
     *
     * @param aliases aliases for permissions defined on the plugin
     * @return Android permission strings associated with the provided aliases, if exists
     */
    private fun getPermissionStringsForAliases(aliases: Array<String>): Array<String> {
        val perms = HashSet<String>()
        for (perm in handle.pluginAnnotation.permissions) {
            if (aliases.contains(perm.alias)) {
                perms.addAll(perm.strings)
            }
        }

        return perms.toTypedArray()
    }

    /**
     * Gets the activity launcher associated with the calling methodName, or rejects the call if
     * no registered launcher exists
     *
     * @param call the plugin call
     * @param methodName the name of the activity callback method
     * @return a launcher, or null if none found
     */
    private fun getActivityLauncherOrReject(call: PluginCall, methodName: String?): ActivityResultLauncher<Intent>? {
        val activityLauncher = activityLaunchers[methodName]

        // if there is no registered launcher, reject the call with an error and return null
        if (activityLauncher == null) {
            val registerError =
                "There is no ActivityCallback method registered for the name: $methodName. " +
                    "Please define a callback method annotated with @ActivityCallback " +
                    "that receives arguments: (PluginCall, ActivityResult)"
            Logger.error(registerError)
            call.reject(registerError)
            return null
        }

        return activityLauncher
    }

    /**
     * Gets the permission launcher associated with the calling methodName, or rejects the call if
     * no registered launcher exists
     *
     * @param call the plugin call
     * @param methodName the name of the permission callback method
     * @return a launcher, or null if none found
     */
    private fun getPermissionLauncherOrReject(call: PluginCall, methodName: String?): ActivityResultLauncher<Array<String>>? {
        val permissionLauncher = permissionLaunchers[methodName]

        // if there is no registered launcher, reject the call with an error and return null
        if (permissionLauncher == null) {
            val registerError =
                "There is no PermissionCallback method registered for the name: $methodName. " +
                    "Please define a callback method annotated with @PermissionCallback " +
                    "that receives arguments: (PluginCall)"
            Logger.error(registerError)
            call.reject(registerError)
            return null
        }

        return permissionLauncher
    }

    /**
     * Get the permission state for the provided permission alias.
     *
     * @param alias the permission alias to get
     * @return the state of the provided permission alias or null
     */
    public open fun getPermissionState(alias: String?): PermissionState? = permissionStates[alias]

    /**
     * Helper to check all permissions defined on a plugin and see the state of each.
     *
     * @since 3.0.0
     * Plugins override this to adjust the reported states, which [checkPermissions] and
     * [getPermissionState] both read.
     *
     * @return A mapping of permission aliases to the associated granted status.
     */
    public open val permissionStates: Map<String, PermissionState>
        get() = bridge.getPermissionStates(this)

    /**
     * Add a listener for the given event
     */
    private fun addEventListener(eventName: String?, call: PluginCall) {
        val listeners = eventListeners.getOrPut(eventName) { ArrayList() }

        // Must add the call before sending retained arguments
        listeners.add(call)

        if (listeners.size == 1) {
            sendRetainedArgumentsForEvent(eventName)
        }
    }

    /**
     * Remove a listener from the given event
     */
    private fun removeEventListener(eventName: String?, call: PluginCall) {
        eventListeners[eventName]?.remove(call)
    }

    /**
     * Notify all listeners that an event occurred
     *
     * @param retainUntilConsumed keep the event for the first listener that gets added, if there is none yet
     */
    @JvmOverloads
    protected open fun notifyListeners(eventName: String?, data: JSObject?, retainUntilConsumed: Boolean = false) {
        Logger.verbose(logTag, "Notifying listeners for event $eventName")
        val listeners = eventListeners[eventName]
        if (listeners.isNullOrEmpty()) {
            Logger.debug(logTag, "No listeners found for event $eventName")
            if (retainUntilConsumed) {
                retainedEventArguments.getOrPut(eventName) { ArrayList() }.add(data)
            }
            return
        }

        // Iterate over a snapshot: a listener may add or remove listeners while being resolved.
        for (call in listeners.toList()) {
            call.resolve(data)
        }
    }

    /**
     * Check if there are any listeners for the given event
     */
    protected open fun hasListeners(eventName: String?): Boolean = !eventListeners[eventName].isNullOrEmpty()

    /**
     * Send retained arguments (if any) for this event. This
     * is called only when the first listener for an event is added
     */
    private fun sendRetainedArgumentsForEvent(eventName: String?) {
        // take the retained args and null the source to prevent potential race conditions
        val retainedArgs = retainedEventArguments.remove(eventName) ?: return

        for (retained in retainedArgs) {
            notifyListeners(eventName, retained)
        }
    }

    /**
     * Exported plugin call for adding a listener to this plugin
     */
    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    public open fun addListener(call: PluginCall) {
        val eventName = call.getString("eventName")
        call.keepAlive = true
        addEventListener(eventName, call)
    }

    /**
     * Exported plugin call to remove a listener from this plugin
     */
    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    public open fun removeListener(call: PluginCall) {
        val eventName = call.getString("eventName")
        val callbackId = call.getString("callbackId")
        val savedCall = bridge.getSavedCall(callbackId)
        if (savedCall != null) {
            removeEventListener(eventName, savedCall)
            bridge.releaseCall(savedCall)
        }
    }

    /**
     * Exported plugin call to remove all listeners from this plugin
     */
    @PluginMethod(returnType = PluginMethod.RETURN_PROMISE)
    public open fun removeAllListeners(call: PluginCall) {
        eventListeners.clear()
        call.resolve()
    }

    public open fun removeAllListeners() {
        eventListeners.clear()
    }

    /**
     * Exported plugin call for checking the granted status for each permission
     * declared on the plugin. This plugin call responds with a mapping of permissions to
     * the associated granted status.
     *
     * @since 3.0.0
     */
    @PluginMethod
    @PermissionCallback
    public open fun checkPermissions(pluginCall: PluginCall) {
        val permissionsResult = permissionStates

        if (permissionsResult.isEmpty()) {
            // if no permissions are defined on the plugin, resolve undefined
            pluginCall.resolve()
        } else {
            val permissionsResultJSON = JSObject()
            for ((key, value) in permissionsResult) {
                permissionsResultJSON.put(key, value)
            }

            pluginCall.resolve(permissionsResultJSON)
        }
    }

    /**
     * Exported plugin call to request all permissions for this plugin.
     * To manually request permissions within a plugin use:
     * [requestAllPermissions], or
     * [requestPermissionForAlias], or
     * [requestPermissionForAliases]
     *
     * @param call the plugin call
     */
    @PluginMethod
    public open fun requestPermissions(call: PluginCall) {
        // handle permission requests for plugins defined with @CapacitorPlugin (since 3.0.0)
        val permissions = handle.pluginAnnotation.permissions
        val autoGrantPerms = HashSet<String>()

        // If call was made with a list of specific permission aliases to request, save them
        // to be requested
        val providedPermsList: List<String>? =
            try {
                call.getArray("permissions")?.toList<String>()
            } catch (ignore: JSONException) {
                // do nothing
                null
            }

        // If call was made without any custom permissions, request all from plugin annotation
        val aliasSet = HashSet<String>()
        if (providedPermsList.isNullOrEmpty()) {
            for (perm in permissions) {
                // If a permission is defined with no permission strings, separate it for auto-granting.
                // Otherwise, the alias is added to the list to be requested.
                if (perm.strings.isEmpty() || (perm.strings.size == 1 && perm.strings[0].isEmpty())) {
                    if (perm.alias.isNotEmpty()) {
                        autoGrantPerms.add(perm.alias)
                    }
                } else {
                    aliasSet.add(perm.alias)
                }
            }
        } else {
            for (perm in permissions) {
                if (providedPermsList.contains(perm.alias)) {
                    aliasSet.add(perm.alias)
                }
            }

            if (aliasSet.isEmpty()) {
                // Same as the Java original: the call is rejected here and resolved again below.
                call.reject("No valid permission alias was requested of this plugin.")
            }
        }

        if (aliasSet.isNotEmpty()) {
            // request permissions using provided aliases or all defined on the plugin
            requestPermissionForAliases(aliasSet.toTypedArray(), call, "checkPermissions")
        } else if (autoGrantPerms.isNotEmpty()) {
            // if the plugin only has auto-grant permissions, return all as GRANTED
            val permissionsResults = JSObject()

            for (perm in autoGrantPerms) {
                permissionsResults.put(perm, PermissionState.GRANTED.toString())
            }

            call.resolve(permissionsResults)
        } else {
            // no permissions are defined on the plugin, resolve undefined
            call.resolve()
        }
    }

    /**
     * Called before the app is destroyed to give a plugin the chance to
     * save the last call options for a saved plugin. By default, this
     * method saves the full JSON blob of the options call. Since Bundle sizes
     * may be limited, plugins that expect to be called with large data
     * objects (such as a file), should override this method and selectively
     * store option values in a [Bundle] to avoid exceeding limits.
     * @return a new [Bundle] with fields set from the options of the last saved [PluginCall]
     */
    protected open fun saveInstanceState(): Bundle? {
        val savedCall = bridge.getSavedCall(lastPluginCallId) ?: return null

        val ret = Bundle()
        ret.putString(BUNDLE_PERSISTED_OPTIONS_JSON_KEY, savedCall.data.toString())

        return ret
    }

    /**
     * Called when the app is opened with a previously un-handled
     * activity response. If the plugin that started the activity
     * stored data in [saveInstanceState] then this
     * method will be called to allow the plugin to restore from that.
     */
    protected open fun restoreState(state: Bundle?) {}

    /**
     * Handle onNewIntent
     */
    protected open fun handleOnNewIntent(intent: Intent?) {}

    /**
     * Handle onConfigurationChanged
     */
    protected open fun handleOnConfigurationChanged(newConfig: Configuration?) {}

    /**
     * Handle onStart
     */
    protected open fun handleOnStart() {}

    /**
     * Handle onRestart
     */
    protected open fun handleOnRestart() {}

    /**
     * Handle onResume
     */
    protected open fun handleOnResume() {}

    /**
     * Handle onPause
     */
    protected open fun handleOnPause() {}

    /**
     * Handle onStop
     */
    protected open fun handleOnStop() {}

    /**
     * Handle onDestroy
     */
    protected open fun handleOnDestroy() {}

    /**
     * Give the plugins a chance to take control when a URL is about to be loaded in the WebView.
     * Returning true causes the WebView to abort loading the URL.
     * Returning false causes the WebView to continue loading the URL.
     * Returning null will defer to the default Capacitor policy.
     * Not called for Capacitor's internal HTTP proxy path, which is always blocked.
     */
    public open fun shouldOverrideLoad(url: Uri?): Boolean? = null

    /**
     * Execute the given runnable on the Bridge's task handler
     */
    public open fun execute(runnable: Runnable) {
        bridge.execute(runnable)
    }

    /**
     * Shortcut for getting the plugin log tag.
     *
     * Java resolves a bare `getLogTag()` to the [logTag] property below; Kotlin resolves it to this
     * function with an empty vararg, which is the bare "Capacitor" tag. From Kotlin use [logTag].
     */
    protected fun getLogTag(vararg subTags: String): String = Logger.tags(*subTags)

    /**
     * Gets a plugin log tag with the child's class name as subTag.
     */
    protected val logTag: String
        get() = Logger.tags(javaClass.simpleName)

    // The lifecycle hooks above are protected, which Kotlin (unlike Java) does not open up to the rest of the
    // package. The bridge reaches them through these module-internal trampolines.

    internal fun dispatchSaveInstanceState(): Bundle? = saveInstanceState()

    internal fun dispatchRestoreState(state: Bundle?) = restoreState(state)

    internal fun dispatchOnNewIntent(intent: Intent?) = handleOnNewIntent(intent)

    internal fun dispatchOnConfigurationChanged(newConfig: Configuration?) = handleOnConfigurationChanged(newConfig)

    internal fun dispatchOnStart() = handleOnStart()

    internal fun dispatchOnRestart() = handleOnRestart()

    internal fun dispatchOnResume() = handleOnResume()

    internal fun dispatchOnPause() = handleOnPause()

    internal fun dispatchOnStop() = handleOnStop()

    internal fun dispatchOnDestroy() = handleOnDestroy()

    private companion object {
        // The key we will use inside of a persisted Bundle for the JSON blob
        // for a plugin call options.
        const val BUNDLE_PERSISTED_OPTIONS_JSON_KEY = "_json"
    }
}
