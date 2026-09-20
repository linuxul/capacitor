package com.getcapacitor.android

import com.getcapacitor.BridgeActivity
import com.getcapacitor.CapConfig
import org.json.JSONException
import org.json.JSONObject

/**
 * Host for instrumented tests. CapacitorHttp is on so the proxy is reachable, and a plugin that
 * tries to allow the proxy path is registered so tests can prove the guard still wins.
 */
class TestHostActivity : BridgeActivity() {
    override fun load() {
        registerPlugin(InterceptorAllowingPlugin::class.java)
        try {
            val plugins = JSONObject("{\"CapacitorHttp\":{\"enabled\":true}}")
            config = CapConfig.Builder(this).setPluginsConfiguration(plugins).create()
        } catch (e: JSONException) {
            throw IllegalStateException("bad test plugin config", e)
        }
        super.load()
    }
}
