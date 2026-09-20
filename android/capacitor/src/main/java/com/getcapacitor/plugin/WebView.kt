package com.getcapacitor.plugin

import android.app.Activity
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin
class WebView : Plugin() {
    @PluginMethod
    fun setServerAssetPath(call: PluginCall) {
        val path = call.getString("path")
        bridge.setServerAssetPath(path)
        call.resolve()
    }

    @PluginMethod
    fun setServerBasePath(call: PluginCall) {
        val path = call.getString("path")
        bridge.serverBasePath = path
        call.resolve()
    }

    @PluginMethod
    fun getServerBasePath(call: PluginCall) {
        val path = bridge.serverBasePath
        val ret = JSObject()
        ret.put("path", path)
        call.resolve(ret)
    }

    @PluginMethod
    fun persistServerBasePath(call: PluginCall) {
        val path = bridge.serverBasePath
        val prefs = context.getSharedPreferences(WEBVIEW_PREFS_NAME, Activity.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(CAP_SERVER_PATH, path)
        editor.apply()
        call.resolve()
    }

    companion object {
        const val WEBVIEW_PREFS_NAME = "CapWebViewSettings"
        const val CAP_SERVER_PATH = "serverBasePath"
    }
}
