package com.getcapacitor.plugin

import android.app.Activity
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin
public class WebView : Plugin() {
    @PluginMethod
    public fun setServerAssetPath(call: PluginCall) {
        val path = call.getString("path")
        if (path == null) {
            call.reject("Must provide a path")
            return
        }

        bridge.setServerAssetPath(path)
        call.resolve()
    }

    @PluginMethod
    public fun setServerBasePath(call: PluginCall) {
        val path = call.getString("path")
        if (path == null) {
            call.reject("Must provide a path")
            return
        }

        bridge.serverBasePath = path
        call.resolve()
    }

    @PluginMethod
    public fun getServerBasePath(call: PluginCall) {
        val path = bridge.serverBasePath
        val ret = JSObject()
        ret.put("path", path)
        call.resolve(ret)
    }

    @PluginMethod
    public fun persistServerBasePath(call: PluginCall) {
        val path = bridge.serverBasePath
        val prefs = context.getSharedPreferences(WEBVIEW_PREFS_NAME, Activity.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(CAP_SERVER_PATH, path)
        editor.apply()
        call.resolve()
    }

    public companion object {
        public const val WEBVIEW_PREFS_NAME: String = "CapWebViewSettings"
        public const val CAP_SERVER_PATH: String = "serverBasePath"
    }
}
