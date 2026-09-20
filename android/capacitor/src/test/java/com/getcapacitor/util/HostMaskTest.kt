package com.getcapacitor.util

import com.getcapacitor.util.HostMask.Util
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostMaskTest {
    @Test
    fun testParser() {
        assertEquals(
            HostMask.Any::class.java,
            HostMask.Parser.parse("*,example.org,*.example.org".split(",").toTypedArray()).javaClass,
        )
        assertEquals(HostMask.Simple::class.java, HostMask.Parser.parse("*").javaClass)
        assertEquals(HostMask.Nothing::class.java, HostMask.Parser.parse(null as String?).javaClass)
    }

    @Test
    fun testAny() {
        val mask: HostMask = HostMask.Any.parse("*.example.org", "example.org")
        assertFalse(mask.matches("org"))
        assertTrue(mask.matches("example.org"))
        assertTrue(mask.matches("www.example.org"))
        assertFalse(mask.matches("imap.mail.example.org"))
        assertFalse(mask.matches("another.org"))
        assertFalse(mask.matches("www.another.org"))
        assertFalse(mask.matches(null))
    }

    @Test
    fun testAnyWildcard() {
        val mask: HostMask = HostMask.Any.parse("*")
        assertTrue(mask.matches("org"))
        assertTrue(mask.matches("example.org"))
        assertTrue(mask.matches("www.example.org"))
        assertTrue(mask.matches("imap.mail.example.org"))
        assertTrue(mask.matches("another.org"))
        assertTrue(mask.matches("www.another.org"))
        assertFalse(mask.matches(null))
    }

    @Test
    fun testSimple() {
        val mask: HostMask = HostMask.Simple.parse("*.org")
        assertTrue(mask.matches("example.org"))
        assertFalse(mask.matches("org"))
        assertFalse(mask.matches("www.example.org"))
        assertFalse("Null host never matches", mask.matches(null))
    }

    @Test
    fun testSimpleExample1() {
        val mask: HostMask = HostMask.Simple.parse("*.example.org")
        assertFalse("Null host never matches", mask.matches("example.org"))
    }

    @Test
    fun testSimpleExample2() {
        val mask: HostMask = HostMask.Simple.parse("*")
        assertTrue("Single star matches everything", mask.matches("example.org"))
    }

    @Test
    fun test192168ForLocalTestingSakes() {
        val mask: HostMask = HostMask.Simple.parse("192.168.*.*")
        assertTrue("Matches 192.168.*.*", mask.matches("192.168.2.5"))
        assertFalse("Matches NOT 192.168.*.*", mask.matches("192.66.2.5"))
    }

    @Test
    fun testUtil() {
        assertTrue("Everything matches *", Util.matches("*", "*"))
        assertTrue("Everything matches *", Util.matches("*", "org"))
        assertTrue(Util.matches("org", "org"))
        assertTrue("Match is case insensitive", Util.matches("ORG", "org"))
        assertFalse("Nothing matches null mask", Util.matches(null, "org"))
        assertFalse("Nothing matches null mask", Util.matches(null, null))
    }
}
