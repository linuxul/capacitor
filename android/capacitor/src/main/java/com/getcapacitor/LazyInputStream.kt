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

import android.webkit.WebResourceRequest
import java.io.InputStream

/**
 * The response body of a file served by [WebViewLocalServer]. The WebView reads it on a separate thread pool.
 *
 * The [handler] opens the file on first use, never in the constructor. A handler that yields nothing is asked
 * again on the next call, and the stream reads as empty until then. [close] closes the file if it was opened; a
 * closed stream is never reopened.
 */
internal class LazyInputStream(private val handler: WebViewLocalServer.PathHandler, private val request: WebResourceRequest) :
    InputStream() {
    // Opened on the request thread, then read and closed on the WebView's threads.
    private var inputStream: InputStream? = null
    private var closed = false

    @Synchronized
    private fun getInputStream(): InputStream? {
        if (closed) {
            return null
        }
        if (inputStream == null) {
            inputStream = handler.handle(request)
        }
        return inputStream
    }

    /**
     * Whether the handler has a stream for the request. Opens it.
     */
    fun exists(): Boolean = getInputStream() != null

    // InputStream.available() has no "missing" value, so a missing stream reports 0 like an exhausted one.
    override fun available(): Int = getInputStream()?.available()?.coerceAtLeast(0) ?: 0

    override fun read(): Int = getInputStream()?.read() ?: -1

    override fun read(b: ByteArray): Int = getInputStream()?.read(b) ?: -1

    override fun read(b: ByteArray, off: Int, len: Int): Int = getInputStream()?.read(b, off, len) ?: -1

    override fun skip(n: Long): Long = getInputStream()?.skip(n) ?: 0

    override fun close() {
        val opened =
            synchronized(this) {
                closed = true
                inputStream.also { inputStream = null }
            }
        opened?.close()
    }
}
