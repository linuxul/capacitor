package com.getcapacitor.plugin.util

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler

/**
 * A `captest://` URL scheme whose connections are [FakeHttpConnection]s, so code that builds its URL from a string
 * can be run against a scripted server.
 *
 * URL.setURLStreamHandlerFactory can be called once per JVM, so this object installs it on first use and only
 * answers for its own scheme.
 */
object FakeHttpProtocol {
    const val SCHEME: String = "captest"

    /** Creates the connection for the next URL that is opened. */
    @Volatile
    var connectionFactory: (URL) -> FakeHttpConnection = { FakeHttpConnection(it) }

    init {
        URL.setURLStreamHandlerFactory { protocol ->
            if (protocol == SCHEME) {
                object : URLStreamHandler() {
                    override fun openConnection(u: URL): URLConnection = connectionFactory(u)
                }
            } else {
                null
            }
        }
    }

    fun install(factory: (URL) -> FakeHttpConnection) {
        connectionFactory = factory
    }
}

/**
 * An HTTP connection that answers 200 text/plain with whatever [body] returns, and records [disconnect].
 */
class FakeHttpConnection(url: URL, private val body: () -> InputStream = { throw IOException("no body") }) : HttpURLConnection(url) {
    @Volatile
    var disconnected: Boolean = false

    /** The request headers as they were when the request was sent. */
    var sentHeaders: Map<String, List<String>> = emptyMap()

    override fun connect() {
        sentHeaders = requestProperties
        connected = true
    }

    override fun disconnect() {
        disconnected = true
    }

    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int = 200

    override fun getHeaderFields(): Map<String, List<String>> = mapOf("Content-Type" to listOf("text/plain"))

    override fun getHeaderField(name: String?): String? = if ("Content-Type".equals(name, ignoreCase = true)) "text/plain" else null

    override fun getInputStream(): InputStream = body()

    override fun getErrorStream(): InputStream? = null
}
