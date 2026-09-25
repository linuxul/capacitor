package com.getcapacitor.plugin

import android.webkit.JavascriptInterface
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.net.CookieHandler
import java.net.CookiePolicy
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

@CapacitorPlugin
public class CapacitorCookies : Plugin() {
    // Assigned in load(), right after the JavaScript interface is registered.
    private lateinit var cookieManager: CapacitorCookieManager

    override fun load() {
        bridge.webView.addJavascriptInterface(this, "CapacitorCookiesAndroidInterface")
        cookieManager = CapacitorCookieManager(null, CookiePolicy.ACCEPT_ALL, bridge)
        cookieManager.removeSessionCookies()
        CookieHandler.setDefault(cookieManager)
        super.load()
    }

    override fun handleOnDestroy() {
        super.handleOnDestroy()
        // Same as the Java original: throws if load() never ran.
        cookieManager.removeSessionCookies()
    }

    // Must stay a function: a Kotlin property would not be exposed to JavaScript under this name.
    @JavascriptInterface
    public fun isEnabled(): Boolean {
        val pluginConfig = bridge.config.getPluginConfiguration("CapacitorCookies")
        return pluginConfig.getBoolean("enabled", false)
    }

    // Called from JavaScript: both arguments can arrive as null. CapacitorCookieManager.setCookie
    // tolerates that (it logs "Failed to set cookie." at worst), exactly as it did for the Java original.
    @JavascriptInterface
    public fun setCookie(domain: String?, action: String?) {
        cookieManager.setCookie(domain, action)
    }

    @PluginMethod
    public fun getCookies(call: PluginCall) {
        bridge.eval("document.cookie") { value: String? ->
            val cookieMap = JSObject()

            // evaluateJavascript reports a JS null as the string "null", which yields an empty map below;
            // a null reference is treated the same way instead of crashing the callback.
            if (value != null) {
                val cookies = value.substring(1, value.length - 1)
                // Pattern.split keeps java.lang.String.split semantics (trailing empty parts dropped).
                val cookieArray = SEMICOLON.split(cookies)

                for (cookie in cookieArray) {
                    if (cookie.isNotEmpty()) {
                        val keyValue = EQUALS.split(cookie, 2)

                        if (keyValue.size == 2) {
                            // trim { it <= ' ' } is java.lang.String.trim().
                            // decode(String, Charset) is API 33, which is this fork's minSdk, and throws
                            // no checked exception.
                            val key = URLDecoder.decode(keyValue[0].trim { it <= ' ' }, StandardCharsets.UTF_8)
                            val cookieValue = URLDecoder.decode(keyValue[1].trim { it <= ' ' }, StandardCharsets.UTF_8)

                            cookieMap.put(key, cookieValue)
                        }
                    }
                }
            }

            call.resolve(cookieMap)
        }
    }

    @PluginMethod
    public fun setCookie(call: PluginCall) {
        val key = call.getString("key")
        if (null == key) {
            call.reject("Must provide key")
            return
        }
        val value = call.getString("value")
        if (null == value) {
            call.reject("Must provide value")
            return
        }
        val url = call.getString("url")
        val expires = call.getString("expires", "")
        val path = call.getString("path", "/")
        cookieManager.setCookie(url, key, value, expires, path)
        call.resolve()
    }

    @PluginMethod
    public fun deleteCookie(call: PluginCall) {
        val key = call.getString("key")
        if (null == key) {
            call.reject("Must provide key")
            return
        }
        val url = call.getString("url")
        cookieManager.setCookie(url, "$key=$EXPIRED_ATTRIBUTES")
        call.resolve()
    }

    @PluginMethod
    public fun clearCookies(call: PluginCall) {
        val url = call.getString("url")
        val cookies = cookieManager.getCookies(url)
        for (cookie in cookies) {
            cookieManager.setCookie(url, "${cookie.name}=$EXPIRED_ATTRIBUTES")
        }
        call.resolve()
    }

    @PluginMethod
    public fun clearAllCookies(call: PluginCall) {
        cookieManager.removeAllCookies()
        call.resolve()
    }

    private companion object {
        // Appended to "<name>=" to delete a cookie: an expiry date in the past.
        const val EXPIRED_ATTRIBUTES = "; Expires=Wed, 31 Dec 2000 23:59:59 GMT"

        val SEMICOLON: Pattern = Pattern.compile(";")
        val EQUALS: Pattern = Pattern.compile("=")
    }
}
