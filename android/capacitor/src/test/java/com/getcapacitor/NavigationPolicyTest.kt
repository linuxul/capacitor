package com.getcapacitor

import com.getcapacitor.NavigationPolicy.Decision
import com.getcapacitor.util.HostMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Which navigations stay in the WebView, which are blocked and which go to another app.
 */
class NavigationPolicyTest {
    private val policy = NavigationPolicy("https", "localhost", HostMask.Parser.parse(arrayOf("*.example.com", "api.test")))

    private fun decide(scheme: String?, host: String?, path: String? = "/", pluginAnswer: Boolean? = null): Decision =
        policy.decide(scheme, host, path) { pluginAnswer }

    @Test
    fun theAppOriginLoadsInTheWebView() {
        assertEquals(Decision.LOAD, decide("https", "localhost", "/page"))
    }

    @Test
    fun allowedHostsLoadInTheWebView() {
        assertEquals(Decision.LOAD, decide("https", "www.example.com"))
        assertEquals(Decision.LOAD, decide("http", "api.test"))
    }

    @Test
    fun otherHostsAndSchemesOpenInAnotherApp() {
        assertEquals(Decision.OPEN_EXTERNALLY, decide("https", "other.org"))
        // Same host, other scheme: not the app origin.
        assertEquals(Decision.OPEN_EXTERNALLY, decide("http", "localhost"))
        assertEquals(Decision.OPEN_EXTERNALLY, decide("tel", null, null))
        assertEquals(Decision.OPEN_EXTERNALLY, decide("mailto", null, null))
    }

    @Test
    fun dataAndBlobUrlsLoadInTheWebView() {
        assertEquals(Decision.LOAD, decide("data", null, "text/html,hi"))
        assertEquals(Decision.LOAD, decide("blob", null, "https://localhost/1234"))
    }

    @Test
    fun pluginsDecideBeforeThePolicy() {
        assertEquals(Decision.BLOCK, decide("https", "localhost", pluginAnswer = true))
        assertEquals(Decision.LOAD, decide("https", "other.org", pluginAnswer = false))
    }

    @Test
    fun theHttpProxyPathIsBlockedEvenIfAPluginAllowsIt() {
        var asked = false

        val decision = policy.decide("https", "localhost", "${Bridge.CAPACITOR_HTTP_INTERCEPTOR_START}?u=https://evil.test") {
            asked = true
            false
        }

        assertEquals(Decision.BLOCK, decision)
        assertFalse(asked)
    }

    @Test
    fun withoutAllowNavigationOnlyTheAppOriginLoads() {
        val strict = NavigationPolicy("capacitor", "localhost", HostMask.Parser.parse(null as Array<String>?))

        assertEquals(Decision.LOAD, strict.decide("capacitor", "localhost", "/") { null })
        assertEquals(Decision.OPEN_EXTERNALLY, strict.decide("https", "localhost", "/") { null })
    }

    @Test
    fun allowedOriginRulesCoverTheAppServerAndAllowNavigation() {
        val rules =
            NavigationPolicy.allowedOriginRules(
                "https",
                "localhost",
                "http://192.168.1.5:8100",
                arrayOf("*.example.com", "http://api.test", "https://secure.test")
            )

        assertEquals(
            setOf("https://localhost", "http://192.168.1.5:8100", "https://*.example.com", "http://api.test", "https://secure.test"),
            rules
        )
    }

    @Test
    fun allowedOriginRulesWithoutServerUrlOrAllowNavigation() {
        assertEquals(setOf("capacitor://localhost"), NavigationPolicy.allowedOriginRules("capacitor", "localhost", null, null))
    }
}
