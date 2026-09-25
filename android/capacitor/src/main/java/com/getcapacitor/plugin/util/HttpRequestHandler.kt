package com.getcapacitor.plugin.util

import android.util.Base64
import com.getcapacitor.Bridge
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.JSValue
import com.getcapacitor.PluginCall
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URISyntaxException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern
import org.json.JSONException
import org.json.JSONObject

public object HttpRequestHandler {
    /**
     * An enum specifying conventional HTTP Response Types
     * See https://developer.mozilla.org/en-US/docs/Web/API/XMLHttpRequest/responseType
     */
    public enum class ResponseType(private val typeName: String) {
        ARRAY_BUFFER("arraybuffer"),
        BLOB("blob"),
        DOCUMENT("document"),
        JSON("json"),
        TEXT("text")
        ;

        public companion object {
            public val DEFAULT: ResponseType = TEXT

            public fun parse(value: String?): ResponseType {
                for (responseType in entries) {
                    if (responseType.typeName.equals(value, ignoreCase = true)) {
                        return responseType
                    }
                }
                return DEFAULT
            }
        }
    }

    /**
     * Internal builder class for building a CapacitorHttpUrlConnection
     */
    public open class HttpURLConnectionBuilder {
        public var connectTimeout: Int? = null
            private set

        public var readTimeout: Int? = null
            private set

        public var disableRedirects: Boolean? = null
            private set

        public var headers: JSObject? = null
            private set

        public var method: String? = null
            private set

        public var url: URL? = null
            private set

        public var connection: CapacitorHttpUrlConnection? = null
            private set

        public fun setConnectTimeout(connectTimeout: Int?): HttpURLConnectionBuilder {
            this.connectTimeout = connectTimeout
            return this
        }

        public fun setReadTimeout(readTimeout: Int?): HttpURLConnectionBuilder {
            this.readTimeout = readTimeout
            return this
        }

        public fun setDisableRedirects(disableRedirects: Boolean?): HttpURLConnectionBuilder {
            this.disableRedirects = disableRedirects
            return this
        }

        public fun setHeaders(headers: JSObject?): HttpURLConnectionBuilder {
            this.headers = headers
            return this
        }

        public fun setMethod(method: String?): HttpURLConnectionBuilder {
            this.method = method
            return this
        }

        public fun setUrl(url: URL?): HttpURLConnectionBuilder {
            this.url = url
            return this
        }

        public fun openConnection(): HttpURLConnectionBuilder {
            // Same as the Java original: a missing url or headers object throws here.
            val connection = CapacitorHttpUrlConnection(url!!.openConnection() as HttpURLConnection)
            this.connection = connection

            connection.setAllowUserInteraction(false)
            connection.setRequestMethod(method)

            connectTimeout?.let { connection.setConnectTimeout(it) }
            readTimeout?.let { connection.setReadTimeout(it) }
            disableRedirects?.let { connection.setDisableRedirects(it) }

            connection.setRequestHeaders(headers!!)
            return this
        }

        @JvmOverloads
        public fun setUrlParams(params: JSObject, shouldEncode: Boolean = true): HttpURLConnectionBuilder {
            // Same as the Java original: a missing url throws here.
            val url = this.url!!
            val initialQuery = url.query
            val initialQueryBuilderStr = initialQuery ?: ""

            val keys = params.keys()

            if (!keys.hasNext()) {
                return this
            }

            val urlQueryBuilder = StringBuilder(initialQueryBuilderStr)

            // Build the new query string
            for (key in keys) {
                // Attempt as JSONArray and fallback to string if it fails
                val param =
                    try {
                        val arr = params.getJSONArray(key)
                        (0 until arr.length()).joinToString("&") { urlParam(key, arr.getString(it), shouldEncode) }
                    } catch (e: JSONException) {
                        urlParam(key, params.getString(key), shouldEncode)
                    }
                if (urlQueryBuilder.isNotEmpty()) {
                    urlQueryBuilder.append("&")
                }
                urlQueryBuilder.append(param)
            }

            val urlQuery = urlQueryBuilder.toString()

            val uri = url.toURI()
            // URI.getFragment() has no leading "#".
            val unEncodedUrlString =
                uri.scheme +
                    "://" +
                    uri.authority +
                    uri.path +
                    (if (urlQuery != "") "?$urlQuery" else "") +
                    (if (uri.fragment != null) "#" + uri.fragment else "")
            this.url = URL(unEncodedUrlString)

            return this
        }

        public fun build(): CapacitorHttpUrlConnection? = connection

        private companion object {
            fun urlParam(key: String?, value: String?, shouldEncode: Boolean): String = if (shouldEncode) {
                URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
            } else {
                "$key=$value"
            }
        }
    }

    /**
     * Builds an HTTP Response given CapacitorHttpUrlConnection and ResponseType objects
     * @param connection The CapacitorHttpUrlConnection to respond with
     * @param responseType The requested ResponseType
     * @return A JSObject that contains the HTTPResponse to return to the browser
     * @throws IOException Thrown if the InputStream is unable to be parsed correctly
     * @throws JSONException Thrown if the JSON is unable to be parsed
     */
    @JvmOverloads
    public fun buildResponse(connection: CapacitorHttpUrlConnection, responseType: ResponseType = ResponseType.DEFAULT): JSObject {
        val statusCode = connection.getResponseCode()

        val output = JSObject()
        output.put("status", statusCode)
        output.put("headers", buildResponseHeaders(connection))
        output.put("url", connection.getURL())
        output.put("data", readData(connection, responseType))

        val errorStream = connection.getErrorStream()
        if (errorStream != null) {
            output.put("error", true)
        }

        return output
    }

    /**
     * Read the existing ICapacitorHttpUrlConnection data
     * @param connection The ICapacitorHttpUrlConnection object to read in
     * @param responseType The type of HTTP response to return to the API
     * @return The parsed data from the connection
     * @throws IOException Thrown if the InputStreams cannot be properly parsed
     * @throws JSONException Thrown if the JSON is malformed when parsing as JSON
     */
    public fun readData(connection: ICapacitorHttpUrlConnection, responseType: ResponseType): Any {
        val errorStream = connection.getErrorStream()
        val contentType = connection.getHeaderField("Content-Type")

        if (errorStream != null) {
            return if (isOneOf(contentType, MimeType.APPLICATION_JSON, MimeType.APPLICATION_VND_API_JSON)) {
                parseJSON(readStreamAsString(errorStream))
            } else {
                readStreamAsString(errorStream)
            }
        } else if (isOneOf(contentType, MimeType.APPLICATION_JSON)) {
            // backward compatibility
            return parseJSON(readStreamAsString(connection.getInputStream()))
        } else {
            val stream = connection.getInputStream()
            return when (responseType) {
                ResponseType.ARRAY_BUFFER, ResponseType.BLOB -> readStreamAsBase64(stream)
                ResponseType.JSON -> parseJSON(readStreamAsString(stream))
                ResponseType.DOCUMENT, ResponseType.TEXT -> readStreamAsString(stream)
            }
        }
    }

    /**
     * Helper function for determining if the Content-Type is a typeof an existing Mime-Type
     * @param contentType The Content-Type string to check for
     * @param mimeTypes The Mime-Type values to check against
     */
    internal fun isOneOf(contentType: String?, vararg mimeTypes: MimeType): Boolean {
        if (contentType != null) {
            for (mimeType in mimeTypes) {
                if (contentType.contains(mimeType.value)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Build the JSObject response headers based on the connection header map
     * @param connection The CapacitorHttpUrlConnection connection
     * @return A JSObject of the header values from the CapacitorHttpUrlConnection
     */
    public fun buildResponseHeaders(connection: CapacitorHttpUrlConnection): JSObject {
        val output = JSObject()

        for ((key, value) in connection.getHeaderFields()) {
            val valuesString = value.joinToString(", ")
            // The status line is reported under a null key; JSONObject rejects null names and
            // JSObject.put swallows that JSONException, so such entries are skipped.
            if (key != null) {
                output.put(key, valuesString)
            }
        }

        return output
    }

    /**
     * Returns a JSObject or a JSArray based on a string-ified input
     * @param input String-ified JSON that needs parsing
     * @return A JSObject or JSArray
     * @throws JSONException thrown if the JSON is malformed
     */
    public fun parseJSON(input: String): Any {
        // trim { it <= ' ' } is java.lang.String.trim(); Kotlin's trim() strips Unicode whitespace instead.
        val trimmed = input.trim { it <= ' ' }
        return try {
            when {
                trimmed == "null" -> JSONObject.NULL

                trimmed == "true" -> true

                trimmed == "false" -> false

                trimmed.isEmpty() -> ""

                // a string enclosed in " " is a json value, return the string without the quotes
                QUOTED_STRING.matcher(trimmed).matches() -> trimmed.substring(1, trimmed.length - 1)

                INTEGER.matcher(trimmed).matches() -> trimmed.toInt()

                DECIMAL.matcher(trimmed).matches() -> trimmed.toDouble()

                else ->
                    try {
                        JSObject(input)
                    } catch (e: JSONException) {
                        JSArray(input)
                    }
            }
        } catch (e: JSONException) {
            input
        }
    }

    /**
     * Base64-encodes everything the stream yields
     * @param in The InputStream to read and encode
     * @return the stream's bytes as a base64 string
     * @throws IOException thrown if the InputStream is unable to be read
     */
    public fun readStreamAsBase64(`in`: InputStream): String = Base64.encodeToString(`in`.readBytes(), Base64.DEFAULT)

    /**
     * Returns a string based on an InputStream
     * @param in The InputStream to convert to a String
     * @return String value of InputStream
     * @throws IOException thrown if the InputStream is unable to be read
     */
    public fun readStreamAsString(`in`: InputStream): String =
        BufferedReader(InputStreamReader(`in`)).use { reader -> reader.lineSequence().joinToString(System.lineSeparator()) }

    /**
     * Makes an Http Request based on the PluginCall parameters
     * @param call The Capacitor PluginCall that contains the options need for an Http request
     * @param httpMethod The HTTP method that overrides the PluginCall HTTP method
     * @throws IOException throws an IO request when a connection can't be made
     * @throws URISyntaxException thrown when the URI is malformed
     * @throws JSONException thrown when the incoming JSON is malformed
     */
    public fun request(call: PluginCall, httpMethod: String?, bridge: Bridge?): JSObject = request(call, httpMethod, bridge, null)

    /**
     * [request], with the open connection kept in [activeConnections] under [call] while the request runs, so the
     * owner can disconnect it to abort the request. The connection is always disconnected when this returns.
     */
    internal fun request(
        call: PluginCall,
        httpMethod: String?,
        bridge: Bridge?,
        activeConnections: MutableMap<PluginCall, CapacitorHttpUrlConnection>?
    ): JSObject {
        val urlString = call.getString("url", "")
        // getObject/getBoolean/getString return the given default when the key is absent, so these never fall through.
        val headers = call.getObject("headers", JSObject()) ?: JSObject()
        val params = call.getObject("params", JSObject()) ?: JSObject()
        val connectTimeout = call.getInt("connectTimeout")
        val readTimeout = call.getInt("readTimeout")
        val disableRedirects = call.getBoolean("disableRedirects")
        val shouldEncode = call.getBoolean("shouldEncodeUrlParams", true) ?: true
        val responseType = ResponseType.parse(call.getString("responseType"))
        val dataType = call.getString("dataType")

        val method: String = httpMethod?.uppercase(Locale.ROOT) ?: (call.getString("method", "GET") ?: "GET").uppercase(Locale.ROOT)

        val isHttpMutate = method == "DELETE" || method == "PATCH" || method == "POST" || method == "PUT"

        // a workaround for the following android web view issue:
        // https://issues.chromium.org/issues/40450316
        // x-cap-user-agent contains the user agent set in JavaScript
        val userAgentValue = headers.getString("x-cap-user-agent")
        if (userAgentValue != null) {
            headers.put("User-Agent", userAgentValue)
        }
        headers.remove("x-cap-user-agent")

        if (!headers.has("User-Agent") && !headers.has("user-agent")) {
            // Same as the Java original: a null bridge throws here.
            headers.put("User-Agent", bridge!!.config.overriddenUserAgentString)
        }

        val url = URL(urlString)
        val connectionBuilder =
            HttpURLConnectionBuilder()
                .setUrl(url)
                .setMethod(method)
                .setHeaders(headers)
                .setUrlParams(params, shouldEncode)
                .setConnectTimeout(connectTimeout)
                .setReadTimeout(readTimeout)
                .setDisableRedirects(disableRedirects)
                .openConnection()

        // Never null after openConnection().
        val connection = connectionBuilder.build()!!
        activeConnections?.put(call, connection)

        // Whatever fails below (the body, the connection, reading the response), the connection is released.
        try {
            if (null != bridge && !isDomainExcludedFromSSL(bridge, url)) {
                connection.setSSLSocketFactory(bridge)
            }

            // Set HTTP body on a non GET or HEAD request
            if (isHttpMutate) {
                val data = JSValue(call, "data")
                if (data.value != null) {
                    connection.setDoOutput(true)
                    connection.setRequestBody(call, data, dataType)
                }
            }

            connection.connect()

            return buildResponse(connection, responseType)
        } finally {
            activeConnections?.remove(call)
            connection.disconnect()
        }
    }

    public fun isDomainExcludedFromSSL(bridge: Bridge?, url: URL?): Boolean = try {
        val sslPinningImpl = Class.forName("io.ionic.sslpinning.SSLPinning")
        val method = sslPinningImpl.getDeclaredMethod("isDomainExcluded", Bridge::class.java, URL::class.java)
        // A null or non-Boolean result counts as "not excluded", like any other failure below.
        method.invoke(sslPinningImpl.getDeclaredConstructor().newInstance(), bridge, url) as? Boolean ?: false
    } catch (ignored: Exception) {
        false
    }

    // Pattern.matcher(..).matches() is what java.lang.String.matches(regex) does.
    private val QUOTED_STRING: Pattern = Pattern.compile("^\".*\"$")
    private val INTEGER: Pattern = Pattern.compile("^-?\\d+$")
    private val DECIMAL: Pattern = Pattern.compile("^-?\\d+(\\.\\d+)?$")
}
