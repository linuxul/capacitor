package com.getcapacitor

import java.io.InputStream

/**
 * Reads [source] from where it is up to [limit] bytes in total, and reports no more than what is left as available.
 * Closing it closes [source].
 *
 * The local server answers a range request with a stream that ends after the last byte of the range: the WebView
 * skips to the first byte itself (see [WebViewLocalServer]).
 */
internal class BoundedInputStream(private val source: InputStream, private val limit: Long) : InputStream() {
    private var position = 0L

    private val remaining: Long
        get() = (limit - position).coerceAtLeast(0)

    override fun read(): Int {
        if (remaining == 0L) return -1
        val byte = source.read()
        if (byte >= 0) position++
        return byte
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (remaining == 0L) return -1
        val count = source.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (count > 0) position += count
        return count
    }

    override fun skip(n: Long): Long {
        if (n <= 0 || remaining == 0L) return 0
        val skipped = source.skip(minOf(n, remaining))
        if (skipped > 0) position += skipped
        return skipped
    }

    override fun available(): Int = minOf(source.available().toLong(), remaining).toInt()

    override fun close() {
        source.close()
    }
}
