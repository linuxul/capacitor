package com.getcapacitor.plugin

import android.Manifest
import android.webkit.JavascriptInterface
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.plugin.util.CapacitorHttpUrlConnection
import com.getcapacitor.plugin.util.HttpRequestHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@CapacitorPlugin(
    permissions = [
        Permission(strings = [Manifest.permission.WRITE_EXTERNAL_STORAGE], alias = "HttpWrite"),
        Permission(strings = [Manifest.permission.READ_EXTERNAL_STORAGE], alias = "HttpRead")
    ]
)
public class CapacitorHttp : Plugin() {
    private val activeRequests: MutableMap<Runnable, PluginCall> = ConcurrentHashMap()
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    override fun load() {
        bridge.webView.addJavascriptInterface(this, "CapacitorHttpAndroidInterface")
        super.load()
    }

    override fun handleOnDestroy() {
        super.handleOnDestroy()

        for ((_, call) in activeRequests) {
            if (call.data.has("activeCapacitorHttpUrlConnection")) {
                try {
                    val connection = call.data.get("activeCapacitorHttpUrlConnection") as CapacitorHttpUrlConnection
                    connection.disconnect()
                    call.data.remove("activeCapacitorHttpUrlConnection")
                } catch (ignored: Exception) {
                }
            }

            bridge.releaseCall(call)
        }

        activeRequests.clear()
        executor.shutdownNow()
    }

    private fun http(call: PluginCall, httpMethod: String?) {
        val asyncHttpCall =
            object : Runnable {
                override fun run() {
                    try {
                        val response = HttpRequestHandler.request(call, httpMethod, bridge)
                        call.resolve(response)
                    } catch (e: Exception) {
                        call.reject(e.localizedMessage, e.javaClass.simpleName, e)
                    } finally {
                        activeRequests.remove(this)
                    }
                }
            }

        if (!executor.isShutdown) {
            activeRequests[asyncHttpCall] = call
            executor.submit(asyncHttpCall)
        } else {
            call.reject("Failed to execute request - Http Plugin was shutdown")
        }
    }

    // Must stay a function: a Kotlin property would not be exposed to JavaScript under this name.
    @JavascriptInterface
    public fun isEnabled(): Boolean {
        val pluginConfig = bridge.config.getPluginConfiguration("CapacitorHttp")
        return pluginConfig.getBoolean("enabled", false)
    }

    @PluginMethod
    public fun request(call: PluginCall) {
        http(call, null)
    }

    @PluginMethod
    public fun get(call: PluginCall) {
        http(call, "GET")
    }

    @PluginMethod
    public fun post(call: PluginCall) {
        http(call, "POST")
    }

    @PluginMethod
    public fun put(call: PluginCall) {
        http(call, "PUT")
    }

    @PluginMethod
    public fun patch(call: PluginCall) {
        http(call, "PATCH")
    }

    @PluginMethod
    public fun delete(call: PluginCall) {
        http(call, "DELETE")
    }
}
