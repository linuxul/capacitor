package com.getcapacitor

/**
 * The plugin calls the [Bridge] keeps around between invocations: calls kept alive by callback id, the
 * queue of calls waiting for a permission result per plugin, and the call that launched the last activity.
 *
 * Every function is synchronized: the bridge thread, plugins' own threads and the main thread (activity and
 * permission results) all use the store.
 */
internal class SavedCallStore {
    // Stored plugin calls that we're keeping around to call again someday
    private val savedCalls: MutableMap<String, PluginCall> = HashMap()

    // The call IDs of saved plugin calls with associated plugin id for handling permissions
    private val savedPermissionCallIds: MutableMap<String, ArrayDeque<String>> = HashMap()

    // Store a plugin that started a new activity, in case we need to resume
    // the app and return that data back
    private var pluginCallForLastActivity: PluginCall? = null

    /**
     * Retain a call between plugin invocations
     */
    @Synchronized
    fun save(call: PluginCall) {
        savedCalls[call.callbackId] = call
    }

    /**
     * Get a retained plugin call
     */
    @Synchronized
    fun get(callbackId: String?): PluginCall? {
        if (callbackId == null) {
            return null
        }

        return savedCalls[callbackId]
    }

    /**
     * Release a retained call by its ID
     */
    @Synchronized
    fun release(callbackId: String?) {
        if (callbackId != null) {
            savedCalls.remove(callbackId)
        }
    }

    /**
     * Forget every retained call. Permission queues and the last activity call are left alone.
     */
    @Synchronized
    fun reset() {
        savedCalls.clear()
    }

    /**
     * Save a call to be retrieved after requesting permissions. Calls are saved in order.
     */
    @Synchronized
    fun savePermissionCall(call: PluginCall) {
        savedPermissionCallIds.getOrPut(call.pluginId) { ArrayDeque() }.addLast(call.callbackId)
        save(call)
    }

    /**
     * Removes the earliest saved call prior to a permissions request for a given plugin and
     * returns it.
     */
    @Synchronized
    fun takePermissionCall(pluginId: String): PluginCall? = get(savedPermissionCallIds[pluginId]?.removeFirstOrNull())

    /**
     * The call that launched the last activity, without clearing it.
     */
    @Synchronized
    fun peekLastActivityCall(): PluginCall? = pluginCallForLastActivity

    /**
     * The call that launched the last activity. Reading it clears it.
     */
    @Synchronized
    fun takeLastActivityCall(): PluginCall? {
        val call = pluginCallForLastActivity
        pluginCallForLastActivity = null
        return call
    }

    @Synchronized
    fun setLastActivityCall(call: PluginCall?) {
        pluginCallForLastActivity = call
    }
}
