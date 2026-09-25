package com.getcapacitor

import com.getcapacitor.util.HostMask

/**
 * Decides where a navigation of the WebView goes: into the WebView, nowhere, or out to another app.
 *
 * @param appScheme the scheme of the app URL
 * @param appHost the host of the app URL
 * @param allowNavigation the hosts of `server.allowNavigation`, which stay in the WebView as well
 */
internal class NavigationPolicy(private val appScheme: String?, private val appHost: String?, private val allowNavigation: HostMask) {
    enum class Decision {
        /** The WebView loads the URL. */
        LOAD,

        /** The WebView does not load the URL, and nothing else does. */
        BLOCK,

        /** The WebView does not load the URL; it is handed to another app. */
        OPEN_EXTERNALLY
    }

    /**
     * Decides a navigation to the URL made of [scheme], [host] and [path].
     *
     * @param askPlugins the answer of the first plugin that has one (`true` blocks, `false` loads), or null
     */
    fun decide(scheme: String?, host: String?, path: String?, askPlugins: () -> Boolean?): Decision {
        // The proxy returns a remote body at the app origin, so block it before plugins can allow it.
        if (path != null && path.startsWith(Bridge.CAPACITOR_HTTP_INTERCEPTOR_START)) {
            return Decision.BLOCK
        }

        val pluginAnswer = askPlugins()
        if (pluginAnswer != null) {
            return if (pluginAnswer) Decision.BLOCK else Decision.LOAD
        }

        if (scheme == "data" || scheme == "blob") {
            return Decision.LOAD
        }

        val isAppOrigin = host == appHost && scheme == appScheme
        return if (isAppOrigin || allowNavigation.matches(host)) Decision.LOAD else Decision.OPEN_EXTERNALLY
    }

    companion object {
        /**
         * The origins whose pages may post messages to the bridge: the local origin, `server.url`, and each
         * `server.allowNavigation` entry, which is taken as `https://` when it names no `http` scheme.
         */
        fun allowedOriginRules(scheme: String, hostname: String?, serverUrl: String?, allowNavigation: Array<String>?): Set<String> {
            val rules = HashSet<String>()
            rules.add("$scheme://$hostname")
            serverUrl?.let { rules.add(it) }
            allowNavigation?.forEach { rules.add(if (it.startsWith("http")) it else "https://$it") }
            return rules
        }
    }
}
