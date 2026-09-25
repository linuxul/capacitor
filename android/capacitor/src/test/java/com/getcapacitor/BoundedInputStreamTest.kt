package com.getcapacitor

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedInputStreamTest {
    private val bytes = ByteArray(10) { it.toByte() }

    @Test
    fun readsNoMoreThanTheLimit() {
        val stream = BoundedInputStream(ByteArrayInputStream(bytes), 6)

        assertEquals(6, stream.available())
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5), stream.readBytes())
        assertEquals(-1, stream.read())
        assertEquals(0, stream.available())
    }

    @Test
    fun skippingCountsTowardsTheLimit() {
        val stream = BoundedInputStream(ByteArrayInputStream(bytes), 6)

        assertEquals(2, stream.skip(2))
        assertEquals(4, stream.available())
        assertEquals(2, stream.read())
        assertEquals(3, stream.skip(100))
        assertEquals(0, stream.skip(1))
        assertEquals(-1, stream.read(ByteArray(4), 0, 4))
    }

    @Test
    fun aLimitPastTheEndReadsTheWholeSource() {
        val stream = BoundedInputStream(ByteArrayInputStream(bytes), 100)

        assertEquals(10, stream.available())
        assertArrayEquals(bytes, stream.readBytes())
    }

    @Test
    fun closingClosesTheSource() {
        var closed = false
        val source = object : ByteArrayInputStream(bytes) {
            override fun close() {
                closed = true
            }
        }

        BoundedInputStream(source, 4).close()

        assertTrue(closed)
    }
}
