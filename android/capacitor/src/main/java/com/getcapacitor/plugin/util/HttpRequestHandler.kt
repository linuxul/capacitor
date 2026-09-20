package com.getcapacitor.plugin.util

import android.text.TextUtils
import android.util.Base64
import com.getcapacitor.Bridge
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.JSValue
import com.getcapacitor.PluginCall
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.UnsupportedEncodingException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URISyntaxException
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.regex.Pattern

object HttpRequestHandler {
    /**
     * An enum specifying conventional HTTP Response Types
     * See https://developer.mozilla.org/en-US/docs/Web/API/XMLHttpRequest/responseType
     */
    enum class ResponseType(private val typeName: String) {
        ARRAY_BUFFER("arraybuffer"),
        BLOB("blob"),
        DOCUMENT("document"),
        JSON("json"),
        TEXT("text"),
        ;

        companion object {
            @JvmField
            val DEFAULT: ResponseType = TEXT

            @JvmStatic
            fun parse(value: String?): ResponseType {
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
    open class HttpURLConnectionBuilder {
        @JvmField
        var connectTimeout: Int? = null

        @JvmField
        var readTimeout: Int? = null

        @JvmField
        var disableRedirects: Boolean? = null

        @JvmField
        var headers: JSObject? = null

        @JvmField
        var method: String? = null

        @JvmField
        var url: URL? = null

        @JvmField
        var connection: CapacitorHttpUrlConnection? = null

        fun setConnectTimeout(connectTimeout: Int?): HttpURLConnectionBuilder {
            this.connectTimeout = connectTimeout
            return this
        }

        fun setReadTimeout(readTimeout: Int?): HttpURLConnectionBuilder {
            this.readTimeout = readTimeout
            return this
        }

        fun setDisableRedirects(disableRedirects: Boolean?): HttpURLConnectionBuilder {
            this.disableRedirects = disableRedirects
            return this
        }

        fun setHeaders(headers: JSObject?): HttpURLConnectionBuilder {
            this.headers = headers
            return this
        }

        fun setMethod(method: String?): HttpURLConnectionBuilder {
            this.method = method
            return this
        }

        fun setUrl(url: URL?): HttpURLConnectionBuilder {
            this.url = url
            return this
        }

        @Throws(IOException::class)
        fun openConnection(): HttpURLConnectionBuilder {
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

        @Throws(MalformedURLException::class, URISyntaxException::class, JSONException::class)
        fun setUrlParams(params: JSObject): HttpURLConnectionBuilder = setUrlParams(params, true)

        @Throws(URISyntaxException::class, MalformedURLException::class)
        fun setUrlParams(params: JSObject, shouldEncode: Boolean): HttpURLConnectionBuilder {
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
            while (keys.hasNext()) {
                val key = keys.next()

                // Attempt as JSONArray and fallback to string if it fails
                try {
                    val value = StringBuilder()
                    val arr = params.getJSONArray(key)
                    for (x in 0 until arr.length()) {
                        addUrlParam(value, key, arr.getString(x), shouldEncode)
                        if (x != arr.length() - 1) {
                            value.append("&")
                        }
                    }
                    if (urlQueryBuilder.isNotEmpty()) {
                        urlQueryBuilder.append("&")
                    }
                    urlQueryBuilder.append(value)
                } catch (e: JSONException) {
                    if (urlQueryBuilder.isNotEmpty()) {
                        urlQueryBuilder.append("&")
                    }
                    addUrlParam(urlQueryBuilder, key, params.getString(key), shouldEncode)
                }
            }

            val urlQuery = urlQueryBuilder.toString()

            val uri = url.toURI()
            val unEncodedUrlString =
                uri.scheme +
                    "://" +
                    uri.authority +
                    uri.path +
                    (if (urlQuery != "") "?$urlQuery" else "") +
                    (if (uri.fragment != null) uri.fragment else "")
            this.url = URL(unEncodedUrlString)

            return this
        }

        fun build(): CapacitorHttpUrlConnection? = connection

        private companion object {
            fun addUrlParam(sb: StringBuilder, key: String?, value: String?, shouldEncode: Boolean) {
                var encodedKey = key
                var encodedValue = value
                if (shouldEncode) {
                    try {
                        encodedKey = URLEncoder.encode(key, "UTF-8")
                        encodedValue = URLEncoder.encode(value, "UTF-8")
                    } catch (ex: UnsupportedEncodingException) {
                        throw RuntimeException(ex.cause)
                    }
                }
                sb.append(encodedKey).append("=").append(encodedValue)
            }
        }
    }

    /**
     * Builds an HTTP Response given CapacitorHttpUrlConnection and ResponseType objects.
     * Defaults to ResponseType.DEFAULT
     * @param connection The CapacitorHttpUrlConnection to respond with
     * @throws IOException Thrown if the InputStream is unable to be parsed correctly
     * @throws JSONException Thrown if the JSON is unable to be parsed
     */
    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun buildResponse(connection: CapacitorHttpUrlConnection): JSObject = buildResponse(connection, ResponseType.DEFAULT)

    /**
     * Builds an HTTP Response given CapacitorHttpUrlConnection and ResponseType objects
     * @param connection The CapacitorHttpUrlConnection to respond with
     * @param responseType The requested ResponseType
     * @return A JSObject that contains the HTTPResponse to return to the browser
     * @throws IOException Thrown if the InputStream is unable to be parsed correctly
     * @throws JSONException Thrown if the JSON is unable to be parsed
     */
    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun buildResponse(connection: CapacitorHttpUrlConnection, responseType: ResponseType): JSObject {
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
    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun readData(connection: ICapacitorHttpUrlConnection, responseType: ResponseType): Any {
        val errorStream = connection.getErrorStream()
        val contentType = connection.getHeaderField("Content-Type")

        if (errorStream != null) {
            return if (isOneOf(contentType, MimeType.APPLICATION_JSON, MimeType.APPLICATION_VND_API_JSON)) {
                parseJSON(readStreamAsString(errorStream))
            } else {
                readStreamAsString(errorStream)
            }
        } else if (contentType != null && contentType.contains(MimeType.APPLICATION_JSON.value)) {
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
    @JvmStatic
    fun isOneOf(contentType: String?, vararg mimeTypes: MimeType): Boolean {
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
    @JvmStatic
    fun buildResponseHeaders(connection: CapacitorHttpUrlConnection): JSObject {
        val output = JSObject()

        for ((key, value) in connection.getHeaderFields()) {
            val valuesString = TextUtils.join(", ", value)
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
    @JvmStatic
    @Throws(JSONException::class)
    fun parseJSON(input: String): Any {
        // trim { it <= ' ' } is java.lang.String.trim(); Kotlin's trim() strips Unicode whitespace instead.
        val trimmed = input.trim { it <= ' ' }
        try {
            if ("null" == trimmed) {
                return JSONObject.NULL
            } else if ("true" == trimmed) {
                return true
            } else if ("false" == trimmed) {
                return false
            } else if (trimmed.isEmpty()) {
                return ""
            } else if (QUOTED_STRING.matcher(trimmed).matches()) {
                // a string enclosed in " " is a json value, return the string without the quotes
                return trimmed.substring(1, trimmed.length - 1)
            } else if (INTEGER.matcher(trimmed).matches()) {
                return Integer.parseInt(trimmed)
            } else if (DECIMAL.matcher(trimmed).matches()) {
                return java.lang.Double.parseDouble(trimmed)
            } else {
                try {
                    return JSObject(input)
                } catch (e: JSONException) {
                    return JSArray(input)
                }
            }
        } catch (e: JSONException) {
            return input
        }
    }

    /**
     * Returns a string based on a base64 InputStream
     * @param in The base64 InputStream to convert to a String
     * @return String value of InputStream
     * @throws IOException thrown if the InputStream is unable to be read as base64
     */
    @JvmStatic
    @Throws(IOException::class)
    fun readStreamAsBase64(`in`: InputStream): String {
        ByteArrayOutputStream().use { out ->
            val buffer = ByteArray(1024)
            var readBytes = `in`.read(buffer)
            while (readBytes != -1) {
                out.write(buffer, 0, readBytes)
                readBytes = `in`.read(buffer)
            }
            val result = out.toByteArray()
            return Base64.encodeToString(result, 0, result.size, Base64.DEFAULT)
        }
    }

    /**
     * Returns a string based on an InputStream
     * @param in The InputStream to convert to a String
     * @return String value of InputStream
     * @throws IOException thrown if the InputStream is unable to be read
     */
    @JvmStatic
    @Throws(IOException::class)
    fun readStreamAsString(`in`: InputStream): String {
        BufferedReader(InputStreamReader(`in`)).use { reader ->
            val builder = StringBuilder()
            var line = reader.readLine()
            while (line != null) {
                builder.append(line)
                line = reader.readLine()
                if (line != null) {
                    builder.append(System.getProperty("line.separator"))
                }
            }
            return builder.toString()
        }
    }

    /**
     * Makes an Http Request based on the PluginCall parameters
     * @param call The Capacitor PluginCall that contains the options need for an Http request
     * @param httpMethod The HTTP method that overrides the PluginCall HTTP method
     * @throws IOException throws an IO request when a connection can't be made
     * @throws URISyntaxException thrown when the URI is malformed
     * @throws JSONException thrown when the incoming JSON is malformed
     */
    @JvmStatic
    @Throws(IOException::class, URISyntaxException::class, JSONException::class)
    fun request(call: PluginCall, httpMethod: String?, bridge: Bridge?): JSObject {
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

        call.data.put("activeCapacitorHttpUrlConnection", connection)
        connection.connect()

        val response = buildResponse(connection, responseType)

        connection.disconnect()
        call.data.remove("activeCapacitorHttpUrlConnection")

        return response
    }

    @JvmStatic
    fun isDomainExcludedFromSSL(bridge: Bridge?, url: URL?): Boolean =
        try {
            val sslPinningImpl = Class.forName("io.ionic.sslpinning.SSLPinning")
            val method = sslPinningImpl.getDeclaredMethod("isDomainExcluded", Bridge::class.java, URL::class.java)
            method.invoke(sslPinningImpl.getDeclaredConstructor().newInstance(), bridge, url) as Boolean
        } catch (ignored: Exception) {
            false
        }

    fun interface ProgressEmitter {
        fun emit(bytes: Int?, contentLength: Int?)
    }

    // Pattern.matcher(..).matches() is what java.lang.String.matches(regex) does.
    private val QUOTED_STRING: Pattern = Pattern.compile("^\".*\"$")
    private val INTEGER: Pattern = Pattern.compile("^-?\\d+$")
    private val DECIMAL: Pattern = Pattern.compile("^-?\\d+(\\.\\d+)?$")
}
