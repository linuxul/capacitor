package com.getcapacitor

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets

/**
 * JSInject is responsible for returning Capacitor's core
 * runtime JS and any plugin JS back into HTML page responses
 * to the client.
 */
class JSInjector(
    private val globalJS: String?,
    private val bridgeJS: String?,
    private val pluginJS: String?,
    private val localUrlJS: String?,
    private val miscJS: String?,
) {
    constructor(globalJS: String?, bridgeJS: String?, pluginJS: String?, localUrlJS: String?) :
        this(globalJS, bridgeJS, pluginJS, localUrlJS, null)

    /**
     * Generates injectable JS content.
     * This may be used in other forms of injecting that aren't using an InputStream.
     */
    val scriptString: String
        get() {
            var scriptString = globalJS + "\n\n" + localUrlJS + "\n\n" + bridgeJS + "\n\n" + pluginJS

            if (miscJS != null) {
                scriptString += "\n\n" + miscJS
            }

            return scriptString
        }

    /**
     * Given an InputStream from the web server, prepend it with
     * our JS stream
     */
    fun getInjectedStream(responseStream: InputStream?): InputStream {
        val js = "<script type=\"text/javascript\">$scriptString</script>"
        var html = readAssetStream(responseStream)

        // Insert the js string at the position after <head> or before </head> using StringBuilder
        val modifiedHtml = StringBuilder(html)
        if (html.contains("<head>")) {
            modifiedHtml.insert(html.indexOf("<head>") + "<head>".length, "\n" + js + "\n")
            html = modifiedHtml.toString()
        } else if (html.contains("</head>")) {
            modifiedHtml.insert(html.indexOf("</head>"), "\n" + js + "\n")
            html = modifiedHtml.toString()
        } else {
            Logger.error("Unable to inject Capacitor, Plugins won't work")
        }
        return ByteArrayInputStream(html.toByteArray(StandardCharsets.UTF_8))
    }

    private fun readAssetStream(stream: InputStream?): String {
        try {
            val bufferSize = 1024
            val buffer = CharArray(bufferSize)
            val out = StringBuilder()
            // A null stream throws here and is reported by the catch below, as in the Java original.
            val reader: Reader = InputStreamReader(stream, StandardCharsets.UTF_8)
            while (true) {
                val rsz = reader.read(buffer, 0, buffer.size)
                if (rsz < 0) break
                out.append(buffer, 0, rsz)
            }
            return out.toString()
        } catch (e: Exception) {
            Logger.error("Unable to process HTML asset file. This is a fatal error", e)
        }

        return ""
    }
}
