package com.getcapacitor

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * MessageHandler handles messages from the WebView, dispatching them
 * to plugins.
 */
public class MessageHandler(private val bridge: Bridge, private val webView: WebView) {
    private var javaScriptReplyProxy: JavaScriptReplyProxy? = null

    init {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && !bridge.config.isUsingLegacyBridge) {
            val capListener =
                WebViewCompat.WebMessageListener { _, message, _, isMainFrame, replyProxy ->
                    if (isMainFrame) {
                        postMessage(message.data)
                        javaScriptReplyProxy = replyProxy
                    } else {
                        Logger.warn("Plugin execution is allowed in Main Frame only")
                    }
                }
            try {
                WebViewCompat.addWebMessageListener(webView, "androidBridge", bridge.allowedOriginRules, capListener)
            } catch (ex: Exception) {
                webView.addJavascriptInterface(this, "androidBridge")
            }
        } else {
            webView.addJavascriptInterface(this, "androidBridge")
        }
    }

    /**
     * The main message handler that will be called from JavaScript
     * to send a message to the native bridge.
     *
     * Must stay a function taking a nullable String: it is invoked from JavaScript, where a
     * non-null parameter check would crash the bridge thread instead of reaching the catch below.
     */
    @JavascriptInterface
    public fun postMessage(jsonStr: String?) {
        try {
            // A null message throws here (NullPointerException from JSONTokener, as in the Java original) and is logged below.
            val postData = JSObject(jsonStr!!)

            val type = postData.getString("type")

            val typeIsNotNull = type != null
            val isCordovaPlugin = typeIsNotNull && type == "cordova"
            val isJavaScriptError = typeIsNotNull && type == "js.error"

            val callbackId = postData.getString("callbackId")

            if (isCordovaPlugin) {
                Logger.warn("Cordova plugins are not supported, ignoring call: $callbackId")
            } else if (isJavaScriptError) {
                Logger.error("JavaScript Error: $jsonStr")
            } else {
                val pluginId = postData.getString("pluginId")
                val methodName = postData.getString("methodName")
                // Never null: the default is non-null.
                val methodData = postData.getJSObject("options", JSObject()) ?: JSObject()

                Logger.verbose(
                    Logger.tags("Plugin"),
                    "To native (Capacitor plugin): callbackId: $callbackId, pluginId: $pluginId, methodName: $methodName"
                )

                callPluginMethod(callbackId, pluginId, methodName, methodData)
            }
        } catch (ex: Exception) {
            Logger.error("Post message error:", ex)
        }
    }

    public fun sendResponseMessage(call: PluginCall, successResult: PluginResult?, errorResult: PluginResult?) {
        try {
            val data = PluginResult()
            data.put("save", call.keepAlive)
            data.put("callbackId", call.callbackId)
            data.put("pluginId", call.pluginId)
            data.put("methodName", call.methodName)

            val pluginResultInError = errorResult != null
            if (pluginResultInError) {
                data.put("success", false)
                data.put("error", errorResult)
                Logger.debug("Sending plugin error: $data")
            } else {
                data.put("success", true)
                if (successResult != null) {
                    data.put("data", successResult)
                }
            }

            // Same as the Java original: a null callbackId throws here and is logged by the catch below.
            val isValidCallbackId = call.callbackId!! != PluginCall.CALLBACK_ID_DANGLING
            if (isValidCallbackId) {
                val replyProxy = javaScriptReplyProxy
                if (bridge.config.isUsingLegacyBridge) {
                    legacySendResponseMessage(data)
                } else if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && replyProxy != null) {
                    replyProxy.postMessage(data.toString())
                } else {
                    legacySendResponseMessage(data)
                }
            } else {
                bridge.app.fireRestoredResult(data)
            }
        } catch (ex: Exception) {
            Logger.error("sendResponseMessage: error: $ex")
        }
        if (!call.keepAlive) {
            call.release(bridge)
        }
    }

    private fun legacySendResponseMessage(data: PluginResult) {
        val runScript = "window.Capacitor.fromNative($data)"
        val webView = this.webView
        webView.post { webView.evaluateJavascript(runScript, null) }
    }

    private fun callPluginMethod(callbackId: String?, pluginId: String?, methodName: String?, methodData: JSObject) {
        val call = PluginCall(this, pluginId, callbackId, methodName, methodData)
        bridge.callPluginMethod(pluginId, methodName, call)
    }
}
