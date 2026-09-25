package com.getcapacitor

/**
 * One satisfiable byte range from an HTTP `Range` request header, resolved against the length of the body.
 *
 * @property first the first byte served, counting from 0
 * @property last the last byte served, inclusive
 * @property totalLength the length of the whole body
 */
internal data class RangeHeader(val first: Long, val last: Long, val totalLength: Long) {
    /**
     * The `Content-Range` value of the partial response, such as `bytes 0-99/1000`.
     */
    val contentRange: String
        get() = "bytes $first-$last/$totalLength"

    companion object {
        private const val BYTES_UNIT = "bytes="

        /**
         * Parses a single range of bytes (`bytes=0-`, `bytes=100-199` or the suffix form `bytes=-500`) for a
         * body of [totalLength] bytes. A last position past the end is clamped to the last byte.
         *
         * Returns null instead of throwing for anything that cannot be served as one partial response: a
         * malformed header, another unit, several ranges, or a range that starts past the end of the body.
         * The header comes from the page, so it must never crash the WebView thread that parses it.
         */
        fun parse(header: String?, totalLength: Long): RangeHeader? {
            if (header == null || totalLength <= 0) return null

            val value = header.trim()
            if (!value.startsWith(BYTES_UNIT, ignoreCase = true)) return null

            val range = value.substring(BYTES_UNIT.length).trim()
            val dash = range.indexOf('-')
            if (dash < 0 || ',' in range) return null

            val firstText = range.substring(0, dash).trim()
            val lastText = range.substring(dash + 1).trim()

            if (firstText.isEmpty()) {
                // The last N bytes.
                val suffixLength = lastText.toPositionOrNull() ?: return null
                if (suffixLength == 0L) return null
                return RangeHeader(maxOf(0L, totalLength - suffixLength), totalLength - 1, totalLength)
            }

            val first = firstText.toPositionOrNull() ?: return null
            if (first >= totalLength) return null

            val last = if (lastText.isEmpty()) totalLength - 1 else lastText.toPositionOrNull() ?: return null
            if (last < first) return null

            return RangeHeader(first, minOf(last, totalLength - 1), totalLength)
        }

        // Only plain digits: toLongOrNull alone would also take a sign.
        private fun String.toPositionOrNull(): Long? = if (isNotEmpty() && all { it in '0'..'9' }) toLongOrNull() else null
    }
}
