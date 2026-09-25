package com.getcapacitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the bridge serves the app from and which URL the WebView loads, for each shape of the server config.
 */
class AppUrlsTest {
    @Test
    fun defaultConfigServesAndLoadsTheLocalOrigin() {
        val urls = AppUrls.resolve(CapConfig.Builder(null).create())

        assertEquals("https://localhost", urls.localUrl)
        assertEquals("https://localhost", urls.appUrl)
        assertEquals(listOf("localhost"), urls.authorities)
    }

    @Test
    fun customHostnameAndHttpScheme() {
        val urls = AppUrls.resolve("http", "app.local", null, null, null)

        assertEquals("http://app.local", urls.localUrl)
        assertEquals("http://app.local", urls.appUrl)
        assertEquals(listOf("app.local"), urls.authorities)
    }

    @Test
    fun customSchemeLoadsTheRootPath() {
        val urls = AppUrls.resolve("capacitor", "localhost", null, null, null)

        assertEquals("capacitor://localhost", urls.localUrl)
        assertEquals("capacitor://localhost/", urls.appUrl)
    }

    @Test
    fun serverUrlIsLoadedAndItsOriginServed() {
        val urls = AppUrls.resolve("https", "localhost", "http://192.168.1.5:8100/app", null, null)

        assertEquals("http://192.168.1.5:8100", urls.localUrl)
        assertEquals("http://192.168.1.5:8100/app", urls.appUrl)
        assertEquals(listOf("localhost", "192.168.1.5:8100"), urls.authorities)
    }

    @Test
    fun startPathIsAppended() {
        assertEquals("https://localhost/home", AppUrls.resolve("https", "localhost", null, "/home", null).appUrl)
        assertEquals("capacitor://localhost/home", AppUrls.resolve("capacitor", "localhost", null, "home", null).appUrl)
        assertEquals("http://10.0.2.2:3000/home", AppUrls.resolve("https", "localhost", "http://10.0.2.2:3000", "/home", null).appUrl)
    }

    @Test
    fun blankStartPathIsIgnored() {
        assertEquals("https://localhost", AppUrls.resolve("https", "localhost", null, " ", null).appUrl)
    }

    @Test
    fun allowNavigationHostsAreServedAfterTheOthers() {
        val urls = AppUrls.resolve("https", "localhost", "https://dev.example.com", null, arrayOf("*.example.com", "api.test"))

        assertEquals(listOf("localhost", "dev.example.com", "*.example.com", "api.test"), urls.authorities)
    }

    @Test
    fun invalidServerUrlStopsWithTheReason() {
        for (serverUrl in listOf("192.168.1.5:8100", "localhost:8100", "")) {
            val error = assertThrows(serverUrl, IllegalArgumentException::class.java) {
                AppUrls.resolve("https", "localhost", serverUrl, null, null)
            }

            assertTrue(serverUrl, error.message!!.startsWith("Provided server url is invalid: "))
        }
    }
}
