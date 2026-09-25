package com.getcapacitor.plugin

import android.webkit.WebView
import com.getcapacitor.Bridge
import com.getcapacitor.JSObject
import com.getcapacitor.MessageHandler
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginHandle
import com.getcapacitor.plugin.util.CapacitorHttpUrlConnection
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CapacitorHttpTest {
    @Test
    fun destroyAbortsTheConnectionsOfRunningRequests() {
        val bridge = mock<Bridge>()
        whenever(bridge.webView).thenReturn(mock<WebView>())
        val plugin = CapacitorHttp()
        PluginHandle(bridge, plugin)

        // A request in flight, as http() and HttpRequestHandler.request leave it while they wait for the server.
        val call = PluginCall(mock<MessageHandler>(), "CapacitorHttp", "1", "get", JSObject())
        val connection = mock<CapacitorHttpUrlConnection>()
        privateMap<Runnable, PluginCall>(plugin, "activeRequests")[Runnable {}] = call
        privateMap<PluginCall, CapacitorHttpUrlConnection>(plugin, "activeConnections")[call] = connection

        plugin.dispatchOnDestroy()

        verify(connection).disconnect()
        verify(bridge).releaseCall(call)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <K, V> privateMap(plugin: CapacitorHttp, name: String): MutableMap<K, V> =
        CapacitorHttp::class.java.getDeclaredField(name).apply { isAccessible = true }.get(plugin) as MutableMap<K, V>
}
