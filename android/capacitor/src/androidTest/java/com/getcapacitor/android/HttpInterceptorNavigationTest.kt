package com.getcapacitor.android

import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getcapacitor.Bridge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The proxy path shares the app's host and scheme, so the host/scheme guard alone would let it
 * load in the WebView.
 */
@RunWith(AndroidJUnit4::class)
class HttpInterceptorNavigationTest {
    /** A plugin registered by the test host returns "allow" for the proxy path; it must not win. */
    @Test
    fun blocksNavigationToInterceptorPath() {
        ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bridge = checkNotNull(activity.getBridge()) { "TestHostActivity did not create a Bridge" }

                assertTrue("interceptor navigation must be blocked", bridge.launchIntent(Uri.parse(INTERCEPTOR_URL)))
                assertFalse("in-app navigation must stay in the WebView", bridge.launchIntent(Uri.parse(IN_APP_URL)))
                assertTrue("external navigation must leave the WebView", bridge.launchIntent(Uri.parse(EXTERNAL_URL)))
            }
        }
    }

    /** An iframe looks like a fetch to isForMainFrame(), so subframe documents must be refused too. */
    @Test
    fun refusesProxyForDocumentRequests() {
        ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bridge = checkNotNull(activity.getBridge()) { "TestHostActivity did not create a Bridge" }

                // Without this the proxy refuses everything and the assertions below prove nothing.
                assertTrue(
                    "CapacitorHttp must be enabled for this test to mean anything",
                    bridge.config.getPluginConfiguration("CapacitorHttp").getBoolean("enabled", false)
                )

                val navHeaders = mapOf(
                    "Accept" to "text/html,application/xhtml+xml",
                    "Upgrade-Insecure-Requests" to "1"
                )

                assertNull(
                    "main frame document must be refused",
                    bridge.localServer.shouldInterceptRequest(FakeRequest(INTERCEPTOR_URL, true, navHeaders))
                )
                assertNull(
                    "iframe document must be refused",
                    bridge.localServer.shouldInterceptRequest(FakeRequest(INTERCEPTOR_URL, false, navHeaders))
                )
            }
        }
    }

    private class FakeRequest(url: String, private val mainFrame: Boolean, private val headers: Map<String, String>) : WebResourceRequest {
        private val url: Uri = Uri.parse(url)

        override fun getUrl(): Uri = url

        override fun isForMainFrame(): Boolean = mainFrame

        override fun isRedirect(): Boolean = false

        override fun hasGesture(): Boolean = false

        override fun getMethod(): String = "GET"

        override fun getRequestHeaders(): Map<String, String> = headers
    }

    private companion object {
        const val INTERCEPTOR_URL =
            "https://localhost" + Bridge.CAPACITOR_HTTP_INTERCEPTOR_START + "?u=https://example.com/payload.html"
        const val IN_APP_URL = "https://localhost/index.html"
        const val EXTERNAL_URL = "https://example.com/"
    }
}
