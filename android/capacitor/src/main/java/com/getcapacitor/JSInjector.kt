package com.getcapacitor

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * JSInject is responsible for returning Capacitor's core
 * runtime JS and any plugin JS back into HTML page responses
 * to the client.
 */
internal class JSInjector(globalJS: String, bridgeJS: String, pluginJS: String, localUrlJS: String, miscJS: String?) {
    /**
     * Injectable JS content.
     * This may be used in other forms of injecting that aren't using an InputStream.
     */
    val scriptString: String =
        globalJS + "\n\n" + localUrlJS + "\n\n" + bridgeJS + "\n\n" + pluginJS +
            (if (miscJS != null) "\n\n" + miscJS else "")

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

    // Closes the stream: the injected copy replaces it in the response.
    private fun readAssetStream(stream: InputStream?): String = try {
        // A null stream fails here and is reported by the catch below.
        InputStreamReader(stream, StandardCharsets.UTF_8).use { it.readText() }
    } catch (e: Exception) {
        Logger.error("Unable to process HTML asset file. This is a fatal error", e)
        ""
    }
}
