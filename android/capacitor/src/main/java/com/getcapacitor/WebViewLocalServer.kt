/*
Copyright 2015 Google Inc. All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package com.getcapacitor

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.getcapacitor.plugin.util.HttpRequestHandler
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern

/**
 * Helper class meant to be used with the android.webkit.WebView class to enable hosting assets,
 * resources and other data on 'virtual' https:// URL.
 * Hosting assets and resources on https:// URLs is desirable as it is compatible with the
 * Same-Origin policy.
 *
 * This class is intended to be used from within the
 * [android.webkit.WebViewClient.shouldInterceptRequest] methods.
 */
public class WebViewLocalServer internal constructor(
    context: Context,
    private val bridge: Bridge,
    private val jsInjector: JSInjector?,
    private val authorities: ArrayList<String?>,
    // Whether to route all requests to paths without extensions back to `index.html`
    private val html5mode: Boolean
) {
    public var basePath: String? = null
        private set

    private val uriMatcher = UriMatcher(null)
    private val protocolHandler = AndroidProtocolHandler(context.applicationContext)
    private var isAsset = false

    /**
     * A handler that produces responses for paths on the virtual asset server.
     *
     * Methods of this handler will be invoked on a background thread and care must be taken to
     * correctly synchronize access to any shared state.
     *
     * These methods may be called on more than one thread, and on a different thread than the one
     * shouldInterceptRequest was invoked on, so blocking here does not block other resources from
     * loading. The number of threads the WebView uses to parallelize loading is an internal
     * implementation detail, so the time spent blocking here should still be kept to a minimum.
     */
    public abstract class PathHandler(
        public val encoding: String? = null,
        public val statusCode: Int = 200,
        public val reasonPhrase: String = "OK",
        responseHeaders: MutableMap<String, String>? = null
    ) {
        private val responseHeaders: MutableMap<String, String>

        init {
            val tempResponseHeaders = responseHeaders ?: HashMap()
            tempResponseHeaders["Cache-Control"] = "no-cache"
            this.responseHeaders = tempResponseHeaders
        }

        public open fun handle(request: WebResourceRequest): InputStream? = handle(request.url)

        public abstract fun handle(url: Uri): InputStream?

        public open fun buildDefaultResponseHeaders(): MutableMap<String, String> = HashMap(responseHeaders)
    }

    /**
     * Attempt to retrieve the WebResourceResponse associated with the given `request`.
     * This method should be invoked from within
     * [android.webkit.WebViewClient.shouldInterceptRequest].
     *
     * @param request the request to process.
     * @return a response if the request URL had a matching handler, null if no handler was found.
     */
    public fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
        val loadingUrl = request.url

        val loadingPath = loadingUrl.path
        if (null != loadingPath && loadingPath.startsWith(Bridge.CAPACITOR_HTTP_INTERCEPTOR_START)) {
            // Only fetch/XHR should reach the proxy; a document would run remote content at the app origin.
            val httpEnabled = bridge.config.getPluginConfiguration("CapacitorHttp").getBoolean("enabled", false)
            if (!httpEnabled || isDocumentRequest(request)) {
                return null
            }
            Logger.debug("Handling CapacitorHttp request: $loadingUrl")
            try {
                return handleCapacitorHttpRequest(request)
            } catch (e: Exception) {
                Logger.error(e.localizedMessage)
                return null
            }
        }

        val handler: PathHandler?
        synchronized(uriMatcher) {
            handler = uriMatcher.match(request.url) as PathHandler?
        }
        if (handler == null) {
            return null
        }

        if (isLocalFile(loadingUrl) || isMainUrl(loadingUrl) || !isAllowedUrl(loadingUrl) || isErrorUrl(loadingUrl)) {
            Logger.debug("Handling local request: " + request.url.toString())
            return handleLocalRequest(request, handler)
        } else {
            return handleProxyRequest(request, handler)
        }
    }

    /** isForMainFrame() is false for an iframe and a fetch alike; only navigations send this header. */
    private fun isDocumentRequest(request: WebResourceRequest): Boolean {
        if (request.isForMainFrame) {
            return true
        }
        // The proxy needs the headers too, so the request fails there anyway.
        val headers: Map<String?, String?> = request.requestHeaders ?: return false
        for (header in headers.keys) {
            if ("Upgrade-Insecure-Requests".equals(header, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun isLocalFile(uri: Uri): Boolean {
        // Same as the Java original: a matched uri without a path throws here.
        val path = uri.path!!
        return path.startsWith(CAPACITOR_CONTENT_START) || path.startsWith(CAPACITOR_FILE_START)
    }

    private fun isErrorUrl(uri: Uri): Boolean {
        val url = uri.toString()
        return url == bridge.errorUrl
    }

    private fun isMainUrl(loadingUrl: Uri): Boolean {
        // Same as the Java original: a matched uri without a host throws here.
        return bridge.serverUrl == null && loadingUrl.host!!.equals(bridge.host, ignoreCase = true)
    }

    private fun isAllowedUrl(loadingUrl: Uri): Boolean =
        !(bridge.serverUrl == null && !bridge.appAllowNavigationMask.matches(loadingUrl.host))

    private fun getReasonPhraseFromResponseCode(code: Int): String = when (code) {
        100 -> "Continue"
        101 -> "Switching Protocols"
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        203 -> "Non-Authoritative Information"
        204 -> "No Content"
        205 -> "Reset Content"
        206 -> "Partial Content"
        300 -> "Multiple Choices"
        301 -> "Moved Permanently"
        302 -> "Found"
        303 -> "See Other"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        406 -> "Not Acceptable"
        407 -> "Proxy Authentication Required"
        408 -> "Request Timeout"
        409 -> "Conflict"
        410 -> "Gone"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        505 -> "HTTP Version Not Supported"
        else -> "Unknown"
    }

    // Every failure in here (including the NullPointerExceptions of the Java original) is caught by the caller.
    private fun handleCapacitorHttpRequest(request: WebResourceRequest): WebResourceResponse {
        val urlString = request.url.getQueryParameter(Bridge.CAPACITOR_HTTP_INTERCEPTOR_URL_PARAM)
        val url = URL(urlString)
        val headers = JSObject()

        for (header in request.requestHeaders.entries) {
            headers.put(header.key, header.value)
        }

        // a workaround for the following android web view issue:
        // https://issues.chromium.org/issues/40450316
        // x-cap-user-agent contains the user agent set in JavaScript
        val userAgentValue = headers.getString("x-cap-user-agent")
        if (userAgentValue != null) {
            headers.put("User-Agent", userAgentValue)
        }
        headers.remove("x-cap-user-agent")

        val connectionBuilder =
            HttpRequestHandler.HttpURLConnectionBuilder()
                .setUrl(url)
                .setMethod(request.method)
                .setHeaders(headers)
                .openConnection()

        // Never null after openConnection().
        val connection = connectionBuilder.build()!!

        if (!HttpRequestHandler.isDomainExcludedFromSSL(bridge, url)) {
            connection.setSSLSocketFactory(bridge)
        }

        connection.connect()

        var mimeType: String? = null
        var encoding: String? = null
        // The status line arrives under a null key and has always been passed through as such.
        val responseHeaders: MutableMap<String?, String> = LinkedHashMap()
        for ((name, values) in connection.getHeaderFields()) {
            val value = values.joinToString(", ")

            if ("Content-Type".equals(name, ignoreCase = true)) {
                // Pattern.split / trim { it <= ' ' } keep java.lang.String.split / trim semantics.
                val contentTypeParts = SEMICOLON.split(value)
                mimeType = contentTypeParts[0].trim { it <= ' ' }
                if (contentTypeParts.size > 1) {
                    val encodingParts = EQUALS.split(contentTypeParts[1])
                    if (encodingParts.size > 1) {
                        encoding = encodingParts[1].trim { it <= ' ' }
                    }
                }
            } else {
                responseHeaders[name] = value
            }
        }

        val inputStream = connection.getErrorStream() ?: connection.getInputStream()

        if (null == mimeType) {
            mimeType = getMimeType(request.url.path, inputStream)
        }

        val responseCode = connection.getResponseCode()
        val reasonPhrase = getReasonPhraseFromResponseCode(responseCode)

        // Nothing should render this. If anything does, sandbox keeps it inert and off the app origin.
        responseHeaders["Content-Security-Policy"] = "sandbox; frame-ancestors 'none'"

        return WebResourceResponse(mimeType, encoding, responseCode, reasonPhrase, responseHeaders, inputStream)
    }

    private fun handleLocalRequest(request: WebResourceRequest, handler: PathHandler): WebResourceResponse? {
        // Non-null: shouldInterceptRequest already dereferenced this path in isLocalFile().
        val path: String = request.url.path!!

        val requestHeaders = request.requestHeaders
        val rangeString = requestHeaders["Range"] ?: requestHeaders["range"]

        if (rangeString != null) {
            val responseStream: InputStream = LazyInputStream(handler, request)
            val mimeType = getMimeType(path, responseStream)
            val tempResponseHeaders = handler.buildDefaultResponseHeaders()
            var statusCode = 206
            try {
                val totalRange = responseStream.available()
                val parts = EQUALS.split(rangeString)
                val streamParts = HYPHEN.split(parts[1])
                val fromRange = streamParts[0]
                var range = totalRange - 1
                if (streamParts.size > 1) {
                    range = Integer.parseInt(streamParts[1])
                }
                tempResponseHeaders["Accept-Ranges"] = "bytes"
                tempResponseHeaders["Content-Range"] = "bytes $fromRange-$range/$totalRange"
            } catch (e: IOException) {
                statusCode = 404
            }
            return WebResourceResponse(
                mimeType,
                handler.encoding,
                statusCode,
                handler.reasonPhrase,
                tempResponseHeaders,
                responseStream
            )
        }

        if (isLocalFile(request.url) || isErrorUrl(request.url)) {
            val responseStream: InputStream = LazyInputStream(handler, request)
            val mimeType = getMimeType(request.url.path, responseStream)
            val statusCode = getStatusCode(responseStream, handler.statusCode)
            return WebResourceResponse(
                mimeType,
                handler.encoding,
                statusCode,
                handler.reasonPhrase,
                handler.buildDefaultResponseHeaders(),
                responseStream
            )
        }

        // Apps may still include a cordova.js script tag; serve it as empty instead of a 404
        if (path == "/cordova.js") {
            return WebResourceResponse(
                "application/javascript",
                handler.encoding,
                handler.statusCode,
                handler.reasonPhrase,
                handler.buildDefaultResponseHeaders(),
                null
            )
        }

        // Same as the Java original: a path without any segment (other than "/") throws here.
        if (path == "/" || (!request.url.lastPathSegment!!.contains(".") && html5mode)) {
            var responseStream: InputStream
            try {
                var startPath: String? = this.basePath + "/index.html"
                val routeProcessor = bridge.routeProcessor
                if (routeProcessor != null) {
                    val processedRoute = routeProcessor.process(this.basePath, "/index.html")!!
                    startPath = processedRoute.path
                    isAsset = processedRoute.isAsset
                }

                // Same as the Java original: a route processor that yields no path throws here.
                responseStream =
                    if (isAsset) {
                        protocolHandler.openAsset(startPath!!)
                    } else {
                        protocolHandler.openFile(startPath!!)
                    }
            } catch (e: IOException) {
                Logger.error("Unable to open index.html", e)
                return null
            }

            if (jsInjector != null) {
                responseStream = jsInjector.getInjectedStream(responseStream)
            }

            val statusCode = getStatusCode(responseStream, handler.statusCode)
            return WebResourceResponse(
                "text/html",
                handler.encoding,
                statusCode,
                handler.reasonPhrase,
                handler.buildDefaultResponseHeaders(),
                responseStream
            )
        }

        // Apps rarely ship a favicon; serve it as empty instead of a 404
        if ("/favicon.ico".equals(path, ignoreCase = true)) {
            return WebResourceResponse("image/png", null, null)
        }

        if ('.' in path) {
            var responseStream: InputStream = LazyInputStream(handler, request)

            // TODO: Conjure up a bit more subtlety than this
            if (path.endsWith(".html") && jsInjector != null) {
                responseStream = jsInjector.getInjectedStream(responseStream)
            }

            val mimeType = getMimeType(path, responseStream)
            val statusCode = getStatusCode(responseStream, handler.statusCode)
            return WebResourceResponse(
                mimeType,
                handler.encoding,
                statusCode,
                handler.reasonPhrase,
                handler.buildDefaultResponseHeaders(),
                responseStream
            )
        }

        return null
    }

    /**
     * Prepends an `InputStream` with the JavaScript required by Capacitor.
     * This method only changes the original `InputStream` if `WebView` does not
     * support the `DOCUMENT_START_SCRIPT` feature.
     * @param original the original `InputStream`
     * @return the modified `InputStream`
     */
    public fun getJavaScriptInjectedStream(original: InputStream?): InputStream? {
        if (jsInjector != null) {
            return jsInjector.getInjectedStream(original)
        }
        return original
    }

    /**
     * Instead of reading files from the filesystem/assets, proxy through to the URL
     * and let an external server handle it.
     */
    private fun handleProxyRequest(request: WebResourceRequest, handler: PathHandler): WebResourceResponse? {
        val injector = jsInjector ?: return null
        val method = request.method
        if (method != "GET") return null

        return try {
            val url = request.url.toString()
            val headers = request.requestHeaders
            // Locale.getDefault() is what the no-argument toLowerCase() used implicitly.
            val isHtmlText = headers.any { (key, value) ->
                key.equals("Accept", ignoreCase = true) &&
                    value.lowercase(Locale.getDefault()).contains("text/html")
            }
            if (!isHtmlText) return null

            val conn = URL(url).openConnection() as HttpURLConnection
            for (header in headers.entries) {
                conn.setRequestProperty(header.key, header.value)
            }
            val getCookie = CookieManager.getInstance().getCookie(url)
            if (getCookie != null) {
                conn.setRequestProperty("Cookie", getCookie)
            }
            conn.requestMethod = method
            conn.readTimeout = 30 * 1000
            conn.connectTimeout = 30 * 1000
            val userInfo = request.url.userInfo
            if (userInfo != null) {
                val userInfoBytes = userInfo.toByteArray(StandardCharsets.UTF_8)
                val base64 = Base64.encodeToString(userInfoBytes, Base64.NO_WRAP)
                conn.setRequestProperty("Authorization", "Basic $base64")
            }

            val cookies = conn.headerFields["Set-Cookie"]
            if (cookies != null) {
                for (cookie in cookies) {
                    CookieManager.getInstance().setCookie(url, cookie)
                }
            }
            val responseStream = injector.getInjectedStream(conn.inputStream)

            WebResourceResponse(
                "text/html",
                handler.encoding,
                handler.statusCode,
                handler.reasonPhrase,
                handler.buildDefaultResponseHeaders(),
                responseStream
            )
        } catch (ex: Exception) {
            bridge.handleAppUrlLoadError(ex)
            null
        }
    }

    private fun getMimeType(path: String?, stream: InputStream?): String? {
        var mimeType: String? = null
        try {
            // Same as the Java original: a null path throws and ends up in the catch below.
            val name = path!!
            mimeType = URLConnection.guessContentTypeFromName(name) // Does not recognize *.js
            if (mimeType != null && name.endsWith(".js") && mimeType == "image/x-icon") {
                Logger.debug("We shouldn't be here")
            }
            if (mimeType == null) {
                if (name.endsWith(".js") || name.endsWith(".mjs")) {
                    // Make sure JS files get the proper mimetype to support ES modules
                    mimeType = "application/javascript"
                } else if (name.endsWith(".wasm")) {
                    mimeType = "application/wasm"
                } else {
                    mimeType = URLConnection.guessContentTypeFromStream(stream)
                }
            }
        } catch (ex: Exception) {
            Logger.error("Unable to get mime type$path", ex)
        }
        return mimeType
    }

    private fun getStatusCode(stream: InputStream, defaultCode: Int): Int {
        var finalStatusCode = defaultCode
        try {
            if (stream.available() == -1) {
                finalStatusCode = 404
            }
        } catch (e: IOException) {
            finalStatusCode = 500
        }
        return finalStatusCode
    }

    /**
     * Registers a handler for the given `uri`. The `handler` will be invoked
     * every time the `shouldInterceptRequest` method of the instance is called with
     * a matching `uri`.
     *
     * @param uri the uri to use the handler for. The scheme and authority (domain) will be matched
     * exactly. The path may contain a '*' element which will match a single element of
     * a path (so a handler registered for /a/\* will be invoked for /a/b and /a/c.html
     * but not for /a/b/b) or the '**' element which will match any number of path
     * elements.
     * @param handler the handler to use for the uri.
     */
    internal fun register(uri: Uri, handler: PathHandler) {
        synchronized(uriMatcher) {
            // UriMatcher has always thrown on a missing scheme or authority.
            uriMatcher.addURI(uri.scheme!!, uri.authority!!, uri.path, handler)
        }
    }

    /**
     * Hosts the application's assets on an https:// URL. Assets from the local path
     * `assetPath/...` will be available under
     * `https://{uuid}.androidplatform.net/assets/...`.
     *
     * @param assetPath the local path in the application's asset folder which will be made
     * available by the server (for example "/www").
     */
    public fun hostAssets(assetPath: String?) {
        isAsset = true
        basePath = assetPath
        createHostingDetails()
    }

    /**
     * Hosts the application's files on an https:// URL. Files from the basePath
     * `basePath/...` will be available under
     * `https://{uuid}.androidplatform.net/...`.
     *
     * @param basePath the local path in the application's data folder which will be made
     * available by the server (for example "/www").
     */
    public fun hostFiles(basePath: String?) {
        isAsset = false
        this.basePath = basePath
        createHostingDetails()
    }

    private fun createHostingDetails() {
        // Same as the Java original: hosting a null path throws here.
        val assetPath = basePath!!

        if (assetPath.indexOf('*') != -1) {
            throw IllegalArgumentException("assetPath cannot contain the '*' character.")
        }

        val handler: PathHandler =
            object : PathHandler() {
                override fun handle(url: Uri): InputStream? {
                    val stream: InputStream?
                    var path = url.path

                    // Pass path to routeProcessor if present
                    val routeProcessor = bridge.routeProcessor
                    var ignoreAssetPath = false
                    if (routeProcessor != null) {
                        val processedRoute = routeProcessor.process("", path)!!
                        path = processedRoute.path
                        isAsset = processedRoute.isAsset
                        ignoreAssetPath = processedRoute.ignoreAssetPath
                    }

                    try {
                        // Same as the Java original: a missing path throws here.
                        if (path!!.startsWith(CAPACITOR_CONTENT_START)) {
                            stream = protocolHandler.openContentUrl(url)
                        } else if (path.startsWith(CAPACITOR_FILE_START)) {
                            stream = protocolHandler.openFile(path)
                        } else if (!isAsset) {
                            if (routeProcessor == null) {
                                path = basePath + url.path
                            }

                            stream = protocolHandler.openFile(path)
                        } else if (ignoreAssetPath) {
                            stream = protocolHandler.openAsset(path)
                        } else {
                            stream = protocolHandler.openAsset(assetPath + path)
                        }
                    } catch (e: IOException) {
                        Logger.error("Unable to open asset URL: $url")
                        return null
                    }

                    return stream
                }
            }

        for (authority in authorities) {
            registerUriForScheme(Bridge.CAPACITOR_HTTP_SCHEME, handler, authority)
            registerUriForScheme(Bridge.CAPACITOR_HTTPS_SCHEME, handler, authority)

            val customScheme = bridge.scheme
            if (customScheme != Bridge.CAPACITOR_HTTP_SCHEME && customScheme != Bridge.CAPACITOR_HTTPS_SCHEME) {
                registerUriForScheme(customScheme, handler, authority)
            }
        }
    }

    private fun registerUriForScheme(scheme: String?, handler: PathHandler, authority: String?) {
        val uriBuilder = Uri.Builder()
        uriBuilder.scheme(scheme)
        uriBuilder.authority(authority)
        uriBuilder.path("")
        val uriPrefix = uriBuilder.build()

        register(Uri.withAppendedPath(uriPrefix, "/"), handler)
        register(Uri.withAppendedPath(uriPrefix, "**"), handler)
    }

    /**
     * The WebView reads the InputStream on a separate threadpool. We can use that to parallelize
     * loading.
     *
     * The wrapped stream is opened lazily, on first use, never in the constructor. A handler that
     * yields nothing is retried on the next call, as it was before.
     */
    private class LazyInputStream(private val handler: PathHandler, private val request: WebResourceRequest) : InputStream() {
        private var inputStream: InputStream? = null

        private fun getInputStream(): InputStream? {
            if (inputStream == null) {
                inputStream = handler.handle(request)
            }
            return inputStream
        }

        override fun available(): Int = getInputStream()?.available() ?: -1

        override fun read(): Int = getInputStream()?.read() ?: -1

        override fun read(b: ByteArray): Int = getInputStream()?.read(b) ?: -1

        override fun read(b: ByteArray, off: Int, len: Int): Int = getInputStream()?.read(b, off, len) ?: -1

        override fun skip(n: Long): Long = getInputStream()?.skip(n) ?: 0
    }

    private companion object {
        const val CAPACITOR_FILE_START = Bridge.CAPACITOR_FILE_START
        const val CAPACITOR_CONTENT_START = Bridge.CAPACITOR_CONTENT_START

        val SEMICOLON: Pattern = Pattern.compile(";")
        val EQUALS: Pattern = Pattern.compile("=")
        val HYPHEN: Pattern = Pattern.compile("-")
    }
}
