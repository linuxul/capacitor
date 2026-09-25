package com.getcapacitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeHeaderTest {
    private fun parse(header: String?, totalLength: Long = 1000): RangeHeader? = RangeHeader.parse(header, totalLength)

    @Test
    fun openEndedRangeRunsToTheLastByte() {
        assertEquals(RangeHeader(0, 999, 1000), parse("bytes=0-"))
        assertEquals(RangeHeader(500, 999, 1000), parse("bytes=500-"))
        assertEquals("bytes 0-999/1000", parse("bytes=0-")!!.contentRange)
    }

    @Test
    fun closedRangeIsKept() {
        assertEquals(RangeHeader(100, 199, 1000), parse("bytes=100-199"))
        assertEquals("bytes 100-199/1000", parse("bytes=100-199")!!.contentRange)
        assertEquals(RangeHeader(0, 0, 1000), parse("bytes=0-0"))
    }

    @Test
    fun lastPositionPastTheEndIsClamped() {
        assertEquals(RangeHeader(0, 999, 1000), parse("bytes=0-99999"))
    }

    @Test
    fun suffixRangeCountsFromTheEnd() {
        assertEquals(RangeHeader(900, 999, 1000), parse("bytes=-100"))
        assertEquals(RangeHeader(0, 999, 1000), parse("bytes=-5000"))
    }

    @Test
    fun unitAndSpacingAreTolerated() {
        assertEquals(RangeHeader(1, 2, 1000), parse("Bytes=1-2"))
        assertEquals(RangeHeader(1, 2, 1000), parse(" bytes= 1 - 2 "))
    }

    @Test
    fun malformedHeadersYieldNothing() {
        val malformed =
            listOf(
                "",
                "bytes",
                "bytes=",
                "bytes=-",
                "bytes=abc",
                "bytes=a-b",
                "bytes=1-b",
                "bytes=+1-2",
                "bytes=1--2",
                "bytes=1-2-3",
                "bytes=0x10-",
                "items=0-1",
                "0-1",
                "bytes=99999999999999999999-"
            )
        for (header in malformed) {
            assertNull(header, parse(header))
        }
    }

    @Test
    fun severalRangesYieldNothing() {
        assertNull(parse("bytes=0-1,5-6"))
        assertNull(parse("bytes=0-1, -5"))
    }

    @Test
    fun unsatisfiableRangesYieldNothing() {
        assertNull(parse("bytes=1000-"))
        assertNull(parse("bytes=5000-6000"))
        assertNull(parse("bytes=5-2"))
        assertNull(parse("bytes=-0"))
    }

    @Test
    fun emptyOrMissingBodyYieldsNothing() {
        assertNull(parse("bytes=0-", 0))
        assertNull(parse(null))
    }
}
