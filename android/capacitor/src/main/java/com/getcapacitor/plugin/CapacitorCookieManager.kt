package com.getcapacitor.plugin

import com.getcapacitor.Bridge
import com.getcapacitor.Logger
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.CookieStore
import java.net.HttpCookie
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import java.util.regex.Pattern

/**
 * A [CookieManager] backed by the WebView's cookie store, scoped to the bridge's local and server URLs.
 *
 * @constructor Create a new cookie manager with specified cookie store and cookie policy.
 * @param store a `CookieStore` to be used by CookieManager. if `null`, cookie
 * manager will use a default one, which is an in-memory CookieStore implementation.
 * @param policy a `CookiePolicy` instance to be used by cookie manager as policy
 * callback. if `null`, ACCEPT_ORIGINAL_SERVER will be used.
 * @param bridge the bridge whose local and server URLs the requested domain is resolved against
 */
public open class CapacitorCookieManager(store: CookieStore?, policy: CookiePolicy?, bridge: Bridge) : CookieManager(store, policy) {
    private val webkitCookieManager: android.webkit.CookieManager = android.webkit.CookieManager.getInstance()

    private val localUrl: String? = bridge.localUrl

    private val serverUrl: String? = bridge.serverUrl

    /**
     * Create a new cookie manager with the default cookie store and policy
     */
    public constructor(bridge: Bridge) : this(null, null, bridge)

    public fun removeSessionCookies() {
        webkitCookieManager.removeSessionCookies(null)
    }

    /**
     * Resolves [url] against the bridge's server and local URLs, prefixing `https://` when it has no scheme.
     *
     * Unlike the other public functions here this one does not swallow failures: if the result is still not
     * a valid URI the exception (a `URISyntaxException`) propagates to the caller. A null or empty [url] resolves
     * to the server or local URL.
     */
    public fun getSanitizedDomain(url: String?): String? {
        var sanitized = url
        if (!serverUrl.isNullOrEmpty() && (sanitized.isNullOrEmpty() || serverUrl.contains(sanitized))) {
            sanitized = serverUrl
        } else if (!localUrl.isNullOrEmpty() && (sanitized.isNullOrEmpty() || localUrl.contains(sanitized))) {
            sanitized = localUrl
        } else {
            try {
                // Not null: a bridge always has a local URL, which a null url resolved to above.
                val uri = URI(sanitized)
                val scheme = uri.scheme
                if (scheme == null || scheme.isEmpty()) {
                    sanitized = "https://$sanitized"
                }
            } catch (e: URISyntaxException) {
                Logger.error(TAG, "Failed to get scheme from URL.", e)
            }
        }

        try {
            URI(sanitized)
        } catch (error: Exception) {
            Logger.error(TAG, "Failed to get sanitized URL.", error)
            throw error
        }
        return sanitized
    }

    private fun getDomainFromCookieString(cookie: String): String? {
        // Pattern.split keeps java.lang.String.split semantics; trim { it <= ' ' } is java.lang.String.trim().
        val domain = DOMAIN_ATTRIBUTE.split(cookie.lowercase(Locale.ROOT))
        return getSanitizedDomain(if (domain.size <= 1) null else SEMICOLON.split(domain[1])[0].trim { it <= ' ' })
    }

    /**
     * Gets the cookies for the given URL.
     * @param url the URL for which the cookies are requested
     * @return value the cookies as a string, using the format of the 'Cookie' HTTP request header
     */
    public fun getCookieString(url: String?): String? {
        try {
            val sanitized = getSanitizedDomain(url)
            Logger.info(TAG, "Getting cookies at: '$sanitized'")
            return webkitCookieManager.getCookie(sanitized)
        } catch (error: Exception) {
            Logger.error(TAG, "Failed to get cookies at the given URL.", error)
        }

        return null
    }

    /**
     * Gets a cookie value for the given URL and key.
     * @param url the URL for which the cookies are requested
     * @param key the key of the cookie to search for
     * @return the `HttpCookie` value of the cookie at the key,
     * otherwise it will return null
     */
    public fun getCookie(url: String?, key: String?): HttpCookie? = getCookies(url).firstOrNull { it.name == key }

    /**
     * Gets an array of `HttpCookie` given a URL.
     * @param url the URL for which the cookies are requested
     * @return an `HttpCookie` array of non-expired cookies
     */
    public fun getCookies(url: String?): Array<HttpCookie> {
        try {
            val cookieList = ArrayList<HttpCookie>()
            val cookieString = getCookieString(url)
            if (cookieString != null) {
                val singleCookie = SEMICOLON.split(cookieString)
                for (c in singleCookie) {
                    cookieList.add(HttpCookie.parse(c)[0])
                }
            }
            return cookieList.toTypedArray()
        } catch (ex: Exception) {
            return emptyArray()
        }
    }

    /**
     * Sets a cookie for the given URL. Any existing cookie with the same host, path and name will
     * be replaced with the new cookie. The cookie being set will be ignored if it is expired.
     * @param url the URL for which the cookie is to be set
     * @param value the cookie as a string, using the format of the 'Set-Cookie' HTTP response header
     */
    public fun setCookie(url: String?, value: String?) {
        try {
            val sanitized = getSanitizedDomain(url)
            Logger.info(TAG, "Setting cookie '$value' at: '$sanitized'")
            webkitCookieManager.setCookie(sanitized, value)
            flush()
        } catch (error: Exception) {
            Logger.error(TAG, "Failed to set cookie.", error)
        }
    }

    /**
     * Sets a cookie for the given URL. Any existing cookie with the same host, path and name will
     * be replaced with the new cookie. The cookie being set will be ignored if it is expired.
     * @param url the URL for which the cookie is to be set
     * @param key the `HttpCookie` name to use for lookup
     * @param value the value of the `HttpCookie` given a key
     * @param expires optional `expires` attribute; appended together with [path] when either is given
     * @param path optional `path` attribute
     */
    @JvmOverloads
    public fun setCookie(url: String?, key: String?, value: String?, expires: String? = null, path: String? = null) {
        val cookieValue =
            if (expires == null && path == null) {
                "$key=$value"
            } else {
                "$key=$value; expires=$expires; path=$path"
            }
        setCookie(url, cookieValue)
    }

    /**
     * Removes all cookies. This method is asynchronous.
     */
    public fun removeAllCookies() {
        webkitCookieManager.removeAllCookies(null)
        flush()
    }

    /**
     * Ensures all cookies currently accessible through the getCookie API are written to persistent
     * storage. This call will block the caller until it is done and may perform I/O.
     */
    public fun flush() {
        webkitCookieManager.flush()
    }

    override fun put(uri: URI?, responseHeaders: Map<String?, List<String?>?>?) {
        // make sure our args are valid
        if (uri == null || responseHeaders == null) return

        // go over the headers
        for (headerKey in responseHeaders.keys) {
            // ignore headers which aren't cookie related
            if (headerKey == null ||
                !(headerKey.equals("Set-Cookie2", ignoreCase = true) || headerKey.equals("Set-Cookie", ignoreCase = true))
            ) {
                continue
            }

            // process each of the headers
            for (headerValue in responseHeaders[headerKey]!!) {
                try {
                    // Set at the requested server url
                    setCookie(uri.toString(), headerValue)

                    // Set at the defined domain in the response or at default capacitor hosted url
                    // (a null header value has no domain; it throws here and the catch below skips it)
                    setCookie(getDomainFromCookieString(headerValue!!), headerValue)
                } catch (ignored: Exception) {
                }
            }
        }
    }

    override fun get(uri: URI?, requestHeaders: Map<String?, List<String?>?>?): Map<String, List<String>> {
        // make sure our args are valid
        if (uri == null || requestHeaders == null) throw IllegalArgumentException("Argument is null")

        // save our url once
        val url = uri.toString()

        // prepare our response
        val res = HashMap<String, List<String>>()

        // get the cookie
        val cookie = getCookieString(url)

        // return it
        if (cookie != null) res["Cookie"] = listOf(cookie)
        return res
    }

    override fun getCookieStore(): CookieStore {
        // we don't want anyone to work with this cookie store directly
        throw UnsupportedOperationException()
    }

    private companion object {
        const val TAG = "CapacitorCookies"

        val DOMAIN_ATTRIBUTE: Pattern = Pattern.compile("domain=")
        val SEMICOLON: Pattern = Pattern.compile(";")
    }
}
