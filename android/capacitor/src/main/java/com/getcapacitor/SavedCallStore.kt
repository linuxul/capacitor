package com.getcapacitor

import java.util.LinkedList

/**
 * The plugin calls the [Bridge] keeps around between invocations: calls kept alive by callback id, the
 * queue of calls waiting for a permission result per plugin, and the call that launched the last activity.
 */
internal class SavedCallStore {
    // Stored plugin calls that we're keeping around to call again someday
    private var savedCalls: MutableMap<String?, PluginCall> = HashMap()

    // The call IDs of saved plugin calls with associated plugin id for handling permissions
    private val savedPermissionCallIds: MutableMap<String?, LinkedList<String?>> = HashMap()

    // Store a plugin that started a new activity, in case we need to resume
    // the app and return that data back
    private var pluginCallForLastActivity: PluginCall? = null

    /**
     * Retain a call between plugin invocations
     */
    fun save(call: PluginCall) {
        savedCalls[call.callbackId] = call
    }

    /**
     * Get a retained plugin call
     */
    fun get(callbackId: String?): PluginCall? {
        if (callbackId == null) {
            return null
        }

        return savedCalls[callbackId]
    }

    /**
     * Release a retained call by its ID
     */
    fun release(callbackId: String?) {
        savedCalls.remove(callbackId)
    }

    /**
     * Forget every retained call. Permission queues and the last activity call are left alone.
     */
    fun reset() {
        savedCalls = HashMap()
    }

    /**
     * Save a call to be retrieved after requesting permissions. Calls are saved in order.
     */
    fun savePermissionCall(call: PluginCall?) {
        if (call != null) {
            val callIds = savedPermissionCallIds.getOrPut(call.pluginId) { LinkedList() }

            callIds.add(call.callbackId)
            save(call)
        }
    }

    /**
     * Removes the earliest saved call prior to a permissions request for a given plugin and
     * returns it.
     */
    fun takePermissionCall(pluginId: String?): PluginCall? {
        val permissionCallIds = savedPermissionCallIds[pluginId]
        var savedCallId: String? = null
        if (permissionCallIds != null) {
            savedCallId = permissionCallIds.poll()
        }

        return get(savedCallId)
    }

    /**
     * The call that launched the last activity, without clearing it.
     */
    fun peekLastActivityCall(): PluginCall? = pluginCallForLastActivity

    /**
     * The call that launched the last activity. Reading it clears it.
     */
    fun takeLastActivityCall(): PluginCall? {
        val call = pluginCallForLastActivity
        pluginCallForLastActivity = null
        return call
    }

    fun setLastActivityCall(call: PluginCall?) {
        pluginCallForLastActivity = call
    }
}
