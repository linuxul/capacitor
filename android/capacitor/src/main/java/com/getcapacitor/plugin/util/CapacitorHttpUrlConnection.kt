package com.getcapacitor.plugin.util

import android.os.LocaleList
import com.getcapacitor.Bridge
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.JSValue
import com.getcapacitor.PluginCall
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import org.json.JSONException
import org.json.JSONObject

/**
 * Make a new CapacitorHttpUrlConnection instance, which wraps around HttpUrlConnection
 * and provides some helper functions for setting request headers and the request body
 * @param connection the base HttpUrlConnection. You can pass the value from
 * `(HttpUrlConnection) URL.openConnection()`
 */
public class CapacitorHttpUrlConnection(private val connection: HttpURLConnection) : ICapacitorHttpUrlConnection {
    init {
        setDefaultRequestProperties()
    }

    /**
     * Returns the underlying HttpUrlConnection value
     * @return the underlying HttpUrlConnection value
     */
    public fun getHttpConnection(): HttpURLConnection = connection

    public fun disconnect() {
        connection.disconnect()
    }

    /**
     * Set the value of the `allowUserInteraction` field of
     * this `URLConnection`.
     *
     * @param isAllowedInteraction   the new value.
     * @throws IllegalStateException if already connected
     */
    public fun setAllowUserInteraction(isAllowedInteraction: Boolean) {
        connection.allowUserInteraction = isAllowedInteraction
    }

    /**
     * Set the method for the URL request, one of:
     * <UL>
     *  <LI>GET
     *  <LI>POST
     *  <LI>HEAD
     *  <LI>OPTIONS
     *  <LI>PUT
     *  <LI>DELETE
     *  <LI>TRACE
     * </UL> are legal, subject to protocol restrictions.  The default
     * method is GET.
     *
     * @param method the HTTP method
     * @throws ProtocolException if the method cannot be reset or if
     *              the requested method isn't valid for HTTP.
     * @throws SecurityException if a security manager is set and the
     *              method is "TRACE", but the "allowHttpTrace"
     *              NetPermission is not granted.
     */
    public fun setRequestMethod(method: String?) {
        connection.requestMethod = method
    }

    /**
     * Sets a specified timeout value, in milliseconds, to be used
     * when opening a communications link to the resource referenced
     * by this URLConnection.  If the timeout expires before the
     * connection can be established, a
     * java.net.SocketTimeoutException is raised. A timeout of zero is
     * interpreted as an infinite timeout.
     *
     * <p><strong>Warning</strong>: If the hostname resolves to multiple IP
     * addresses, Android's default implementation of [HttpURLConnection]
     * will try each in
     * <a href="http://www.ietf.org/rfc/rfc3484.txt">RFC 3484</a> order. If
     * connecting to each of these addresses fails, multiple timeouts will
     * elapse before the connect attempt throws an exception. Host names
     * that support both IPv6 and IPv4 always have at least 2 IP addresses.
     *
     * @param timeout an `int` that specifies the connect
     *               timeout value in milliseconds
     * @throws IllegalArgumentException if the timeout parameter is negative
     */
    public fun setConnectTimeout(timeout: Int) {
        if (timeout < 0) {
            throw IllegalArgumentException("timeout can not be negative")
        }
        connection.connectTimeout = timeout
    }

    /**
     * Sets the read timeout to a specified timeout, in
     * milliseconds. A non-zero value specifies the timeout when
     * reading from Input stream when a connection is established to a
     * resource. If the timeout expires before there is data available
     * for read, a java.net.SocketTimeoutException is raised. A
     * timeout of zero is interpreted as an infinite timeout.
     *
     * @param timeout an `int` that specifies the timeout
     * value to be used in milliseconds
     * @throws IllegalArgumentException if the timeout parameter is negative
     */
    public fun setReadTimeout(timeout: Int) {
        if (timeout < 0) {
            throw IllegalArgumentException("timeout can not be negative")
        }
        connection.readTimeout = timeout
    }

    /**
     * Sets whether automatic HTTP redirects should be disabled
     * @param disableRedirects the flag to determine if redirects should be followed
     */
    public fun setDisableRedirects(disableRedirects: Boolean) {
        connection.instanceFollowRedirects = !disableRedirects
    }

    /**
     * Sets the request headers given a JSObject of key-value pairs
     * @param headers the JSObject values to map to the HttpUrlConnection request headers
     */
    public fun setRequestHeaders(headers: JSObject) {
        val keys = headers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = headers.getString(key)
            connection.setRequestProperty(key, value)
        }
    }

    /**
     * Sets the value of the `doOutput` field for this
     * `URLConnection` to the specified value.
     * <p>
     * A URL connection can be used for input and/or output.  Set the DoOutput
     * flag to true if you intend to use the URL connection for output,
     * false if not.  The default is false.
     *
     * @param shouldDoOutput   the new value.
     * @throws IllegalStateException if already connected
     */
    public fun setDoOutput(shouldDoOutput: Boolean) {
        connection.doOutput = shouldDoOutput
    }

    public fun setRequestBody(call: PluginCall, body: JSValue?, bodyType: String? = null) {
        val contentType = connection.getRequestProperty("Content-Type")
        var dataString: String? = ""

        if (contentType == null || contentType.isEmpty()) return

        if (contentType.contains("application/json")) {
            var jsArray: JSArray? = null
            if (body != null) {
                dataString = body.toString()
            } else {
                jsArray = call.getArray("data", null)
            }
            if (jsArray != null) {
                dataString = jsArray.toString()
            } else if (body == null) {
                dataString = call.getString("data")
            }
            writeRequestBody(dataString ?: "")
            return
        }

        // Same as the Java original: every remaining branch dereferences the body.
        val requestBody = body!!
        if (bodyType != null && bodyType == "file") {
            DataOutputStream(connection.outputStream).use { os ->
                os.write(Base64.getDecoder().decode(requestBody.toString()))
                os.flush()
            }
        } else if (contentType.contains("application/x-www-form-urlencoded")) {
            try {
                val obj = requestBody.toJSObject()
                writeObjectRequestBody(obj)
            } catch (e: Exception) {
                // Body is not a valid JSON, treat it as an already formatted string
                writeRequestBody(requestBody.toString())
            }
        } else if (bodyType != null && bodyType == "formData") {
            var boundary = extractBoundaryFromContentType(contentType)
            if (boundary == null) {
                // If no boundary is provided, generate a random one and set the Content-Type header accordingly
                // or otherwise servers will not be able to parse the request body. Browsers do this automatically
                // but here we need to do this manually in order to comply with browser api behavior.
                boundary = UUID.randomUUID().toString()
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            writeFormDataRequestBody(boundary, requestBody.toJSArray())
        } else {
            writeRequestBody(requestBody.toString())
        }
    }

    /**
     * Writes the provided string to the HTTP connection managed by this instance.
     *
     * @param body The string value to write to the connection stream.
     */
    private fun writeRequestBody(body: String) {
        DataOutputStream(connection.outputStream).use { os ->
            os.write(body.toByteArray(StandardCharsets.UTF_8))
            os.flush()
        }
    }

    private fun writeObjectRequestBody(obj: JSObject) {
        DataOutputStream(connection.outputStream).use { os ->
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val d = obj.get(key)
                os.writeBytes(URLEncoder.encode(key, "UTF-8"))
                os.writeBytes("=")
                os.writeBytes(URLEncoder.encode(d.toString(), "UTF-8"))

                if (keys.hasNext()) {
                    os.writeBytes("&")
                }
            }
            os.flush()
        }
    }

    private fun writeFormDataRequestBody(boundary: String, entries: JSArray) {
        DataOutputStream(connection.outputStream).use { os ->
            val lineEnd = "\r\n"
            val twoHyphens = "--"

            for (e in entries.toList<Any?>()) {
                if (e is JSONObject) {
                    val type = e.getString("type")
                    val key = e.getString("key")
                    val value = e.getString("value")
                    if (type == "string") {
                        os.writeBytes(twoHyphens + boundary + lineEnd)
                        os.writeBytes("Content-Disposition: form-data; name=\"" + key + "\"" + lineEnd + lineEnd)
                        os.write(value.toByteArray(StandardCharsets.UTF_8))
                        os.writeBytes(lineEnd)
                    } else if (type == "base64File") {
                        val fileName = e.getString("fileName")
                        val fileContentType = e.getString("contentType")

                        os.writeBytes(twoHyphens + boundary + lineEnd)
                        os.writeBytes("Content-Disposition: form-data; name=\"" + key + "\"; filename=\"" + fileName + "\"" + lineEnd)
                        os.writeBytes("Content-Type: $fileContentType$lineEnd")
                        os.writeBytes("Content-Transfer-Encoding: binary$lineEnd")
                        os.writeBytes(lineEnd)

                        os.write(Base64.getDecoder().decode(value))

                        os.writeBytes(lineEnd)
                    }
                }
            }

            os.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd)
            os.flush()
        }
    }

    /**
     * Opens a communications link to the resource referenced by this
     * URL, if such a connection has not already been established.
     * <p>
     * If the `connect` method is called when the connection
     * has already been opened (indicated by the `connected`
     * field having the value `true`), the call is ignored.
     * <p>
     * URLConnection objects go through two phases: first they are
     * created, then they are connected.  After being created, and
     * before being connected, various options can be specified
     * (e.g., doInput and UseCaches).  After connecting, it is an
     * error to try to set them.  Operations that depend on being
     * connected, like getContentLength, will implicitly perform the
     * connection, if necessary.
     *
     * @throws SocketTimeoutException if the timeout expires before
     *               the connection can be established
     * @throws IOException  if an I/O error occurs while opening the
     *               connection.
     */
    public fun connect() {
        connection.connect()
    }

    /**
     * Gets the status code from an HTTP response message.
     * For example, in the case of the following status lines:
     * <PRE>
     * HTTP/1.0 200 OK
     * HTTP/1.0 401 Unauthorized
     * </PRE>
     * It will return 200 and 401 respectively.
     * Returns -1 if no code can be discerned
     * from the response (i.e., the response is not valid HTTP).
     * @throws IOException if an error occurred connecting to the server.
     * @return the HTTP Status-Code, or -1
     */
    public fun getResponseCode(): Int = connection.responseCode

    /**
     * Returns the value of this `URLConnection`'s `URL`
     * field.
     *
     * @return the value of this `URLConnection`'s `URL`
     *          field.
     */
    public fun getURL(): URL? = connection.url

    /**
     * Returns the error stream if the connection failed
     * but the server sent useful data nonetheless. The
     * typical example is when an HTTP server responds
     * with a 404, which will cause a FileNotFoundException
     * to be thrown in connect, but the server sent an HTML
     * help page with suggestions as to what to do.
     *
     * <p>This method will not cause a connection to be initiated.  If
     * the connection was not connected, or if the server did not have
     * an error while connecting or if the server had an error but
     * no error data was sent, this method will return null. This is
     * the default.
     *
     * @return an error stream if any, null if there have been no
     * errors, the connection is not connected or the server sent no
     * useful data.
     */
    override fun getErrorStream(): InputStream? = connection.errorStream

    /**
     * Returns the value of the named header field.
     * <p>
     * If called on a connection that sets the same header multiple times
     * with possibly different values, only the last value is returned.
     *
     *
     * @param name   the name of a header field.
     * @return the value of the named header field, or `null`
     *          if there is no such field in the header.
     */
    override fun getHeaderField(name: String?): String? = connection.getHeaderField(name)

    /**
     * Returns an input stream that reads from this open connection.
     *
     * A SocketTimeoutException can be thrown when reading from the
     * returned input stream if the read timeout expires before data
     * is available for read.
     *
     * @return an input stream that reads from this open connection.
     * @throws IOException              if an I/O error occurs while
     *               creating the input stream.
     * @throws UnknownServiceException  if the protocol does not support
     *               input.
     * @see #setReadTimeout(int)
     */
    override fun getInputStream(): InputStream = connection.inputStream

    /**
     * Returns an unmodifiable Map of the header fields.
     * The Map keys are Strings that represent the
     * response-header field names. Each Map value is an
     * unmodifiable List of Strings that represents
     * the corresponding field values.
     *
     * @return a Map of header fields
     */
    public fun getHeaderFields(): Map<String?, List<String>> = connection.headerFields

    /**
     * Sets the default request properties on the newly created connection.
     * This is called as early as possible to allow overrides by user-provided values.
     */
    private fun setDefaultRequestProperties() {
        val acceptLanguage = buildDefaultAcceptLanguageProperty()
        if (acceptLanguage.isNotEmpty()) {
            connection.setRequestProperty("Accept-Language", acceptLanguage)
        }
    }

    /**
     * Builds and returns a locale string describing the device's current locale preferences.
     */
    private fun buildDefaultAcceptLanguageProperty(): String {
        val locale = LocaleList.getDefault().get(0)
        var result = ""
        val lang = locale.language
        val country = locale.country
        if (!lang.isNullOrEmpty()) {
            result =
                if (!country.isNullOrEmpty()) {
                    String.format("%s-%s,%s;q=0.5", lang, country, lang)
                } else {
                    String.format("%s;q=0.5", lang)
                }
        }
        return result
    }

    public fun setSSLSocketFactory(bridge: Bridge?) {
        // Attach SSL Certificates if Enterprise Plugin is available
        try {
            val sslPinningImpl = Class.forName("io.ionic.sslpinning.SSLPinning")
            val method = sslPinningImpl.getDeclaredMethod("getSSLSocketFactory", Bridge::class.java)
            val sslSocketFactory = method.invoke(sslPinningImpl.getDeclaredConstructor().newInstance(), bridge) as SSLSocketFactory?
            if (sslSocketFactory != null) {
                (connection as HttpsURLConnection).sslSocketFactory = sslSocketFactory
            }
        } catch (ignored: Exception) {
        }
    }

    public companion object {
        /**
         * Extracts the boundary value from the `Content-Type` header for multipart/form-data requests, if provided.
         *
         * The boundary value might be surrounded by double quotes (") which will be stripped away.
         *
         * @param contentType The `Content-Type` header string.
         * @return The boundary value if found, otherwise `null`.
         */
        public fun extractBoundaryFromContentType(contentType: String): String? {
            val boundaryPrefix = "boundary="
            val boundaryIndex = contentType.indexOf(boundaryPrefix)
            if (boundaryIndex == -1) {
                return null
            }

            // Extract the substring starting right after "boundary="
            var boundary = contentType.substring(boundaryIndex + boundaryPrefix.length)

            // Find the end of the boundary value by looking for the next ";"
            val endIndex = boundary.indexOf(";")
            if (endIndex != -1) {
                boundary = boundary.substring(0, endIndex)
            }

            // Remove surrounding double quotes if present
            // (trim { it <= ' ' } is java.lang.String.trim(); Kotlin's trim() strips Unicode whitespace instead)
            boundary = boundary.trim { it <= ' ' }
            if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                boundary = boundary.substring(1, boundary.length - 1)
            }

            return boundary
        }
    }
}
