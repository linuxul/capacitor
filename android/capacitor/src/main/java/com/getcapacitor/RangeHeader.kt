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

    /**
     * How the local server answers a `Range` request header, as on iOS.
     */
    sealed interface Resolution {
        /** Not one byte range the server answers (malformed, another unit, several ranges): serve the whole body. */
        data object Whole : Resolution

        /** Serve [range] with a 206. */
        data class Partial(val range: RangeHeader) : Resolution

        /** A well-formed range that selects no byte of the body: answer with a 416. */
        data class Unsatisfiable(val totalLength: Long) : Resolution {
            /** The `Content-Range` value of the 416 response: an asterisk in place of the range, then the length. */
            val contentRange: String
                get() = "bytes */$totalLength"
        }
    }

    companion object {
        private const val BYTES_UNIT = "bytes="

        /**
         * Resolves a single range of bytes (`bytes=0-`, `bytes=100-199` or the suffix form `bytes=-500`) against a
         * body of [totalLength] bytes. A last position past the end is clamped to the last byte.
         *
         * A range that starts past the end, or the empty suffix `bytes=-0`, is [Resolution.Unsatisfiable]. Anything
         * else that is not one byte range (a malformed header, another unit, several ranges, a last position before
         * the first) is [Resolution.Whole], as is any range of an empty body. The header comes from the page, so this
         * never throws.
         */
        fun resolve(header: String?, totalLength: Long): Resolution {
            if (header == null || totalLength <= 0) return Resolution.Whole

            val value = header.trim()
            if (!value.startsWith(BYTES_UNIT, ignoreCase = true)) return Resolution.Whole

            val range = value.substring(BYTES_UNIT.length).trim()
            val dash = range.indexOf('-')
            if (dash < 0 || ',' in range) return Resolution.Whole

            val firstText = range.substring(0, dash).trim()
            val lastText = range.substring(dash + 1).trim()

            if (firstText.isEmpty()) {
                // The last N bytes.
                val suffixLength = lastText.toPositionOrNull() ?: return Resolution.Whole
                if (suffixLength == 0L) return Resolution.Unsatisfiable(totalLength)
                return Resolution.Partial(RangeHeader(maxOf(0L, totalLength - suffixLength), totalLength - 1, totalLength))
            }

            val first = firstText.toPositionOrNull() ?: return Resolution.Whole
            // A last position before the first makes the header invalid rather than unsatisfiable.
            val last = if (lastText.isEmpty()) null else lastText.toPositionOrNull() ?: return Resolution.Whole
            if (last != null && last < first) return Resolution.Whole
            if (first >= totalLength) return Resolution.Unsatisfiable(totalLength)

            return Resolution.Partial(RangeHeader(first, minOf(last ?: (totalLength - 1), totalLength - 1), totalLength))
        }

        /**
         * The range [resolve] serves with a 206, or null when the request is answered in another way.
         */
        fun parse(header: String?, totalLength: Long): RangeHeader? = (resolve(header, totalLength) as? Resolution.Partial)?.range

        // Only plain digits: toLongOrNull alone would also take a sign.
        private fun String.toPositionOrNull(): Long? = if (isNotEmpty() && all { it in '0'..'9' }) toLongOrNull() else null
    }
}
