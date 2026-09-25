package com.getcapacitor

import java.net.URL

/**
 * The URLs the [Bridge] serves the app from and loads it at, resolved once from the Capacitor config.
 *
 * @property localUrl the origin the web assets are served from: `scheme://hostname`, or the origin of
 *   `server.url` when that is set
 * @property appUrl the URL the WebView loads: `server.url`, or the local origin (with a trailing `/` for a custom
 *   scheme), followed by `server.startPath`
 * @property authorities the hosts the local server answers for: the hostname, the authority of `server.url` and
 *   the `server.allowNavigation` entries, in that order
 */
internal class AppUrls(val localUrl: String, val appUrl: String, val authorities: List<String?>) {
    companion object {
        fun resolve(config: CapConfig): AppUrls =
            resolve(config.androidScheme, config.hostname, config.serverUrl, config.startPath, config.allowNavigation)

        /**
         * @throws IllegalArgumentException when [serverUrl] is set but is not a URL, for example `192.168.1.5:8100`
         *   without a scheme. The app cannot load then.
         */
        fun resolve(scheme: String, hostname: String?, serverUrl: String?, startPath: String?, allowNavigation: Array<String>?): AppUrls {
            val localOrigin = "$scheme://$hostname"
            val authorities = mutableListOf(hostname)
            val localUrl: String
            var appUrl: String

            if (serverUrl != null) {
                val url =
                    try {
                        URL(serverUrl)
                    } catch (ex: Exception) {
                        throw IllegalArgumentException("Provided server url is invalid: ${ex.message}", ex)
                    }
                authorities.add(url.authority)
                localUrl = url.protocol + "://" + url.authority
                appUrl = serverUrl
            } else {
                localUrl = localOrigin
                val isHttpScheme = scheme == Bridge.CAPACITOR_HTTP_SCHEME || scheme == Bridge.CAPACITOR_HTTPS_SCHEME
                // A custom scheme needs a path ending with "/"
                appUrl = if (isHttpScheme) localOrigin else "$localOrigin/"
            }

            // trim { it <= ' ' } is java.lang.String.trim().
            if (startPath != null && startPath.trim { it <= ' ' }.isNotEmpty()) {
                appUrl += startPath
            }

            allowNavigation?.let { authorities.addAll(it) }

            return AppUrls(localUrl, appUrl, authorities)
        }
    }
}
