package com.getcapacitor.plugin

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.webkit.WebView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import com.getcapacitor.Logger
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.WebViewListener
import com.getcapacitor.annotation.CapacitorPlugin
import java.util.Locale
import java.util.regex.Pattern

@CapacitorPlugin
public class SystemBars : Plugin() {
    private var insetsHandling: String = INSETS_HANDLING_CSS
    private var hasViewportCover = false

    private var currentStatusBarStyle: String = STYLE_DEFAULT
    private var currentGestureBarStyle: String = STYLE_DEFAULT

    // Declare variable at this scope to help prevent adding multiple listeners.
    private var webViewListener: WebViewListener? = null

    private fun warnAboutUnsupportedConfigurationValues() {
        val keyboardResizeOnFullScreen = bridge.config.getPluginConfiguration("Keyboard").getBoolean("resizeOnFullScreen", false)

        if (INSETS_HANDLING_DISABLE != insetsHandling && keyboardResizeOnFullScreen) {
            Logger.warn(
                "SystemBars",
                "You should omit `Keyboard.resizeOnFullScreen` in your `capacitor.config.json`. Other values can lead to unexpected behavior.",
            )
        }
    }

    override fun load() {
        super.load()

        initSystemBars()
    }

    override fun handleOnStart() {
        super.handleOnStart()

        if (INSETS_HANDLING_DISABLE == insetsHandling) {
            return
        }

        if (webViewListener == null) {
            val listener =
                object : WebViewListener() {
                    override fun onPageCommitVisible(view: WebView?, url: String?) {
                        super.onPageCommitVisible(view, url)
                        bridge.webView.evaluateJavascript(viewportMetaJSFunction) { res: String? ->
                            hasViewportCover = res == "true"

                            // Request new execution tree of `setOnApplyWindowInsetsListener`
                            bridge.webView.requestApplyInsets()
                        }
                    }
                }
            webViewListener = listener
            bridge.addWebViewListener(listener)
        }
    }

    override fun handleOnConfigurationChanged(newConfig: Configuration?) {
        super.handleOnConfigurationChanged(newConfig)

        setStyle(currentGestureBarStyle, BAR_GESTURE_BAR)
        setStyle(currentStatusBarStyle, BAR_STATUS_BAR)
    }

    private fun initSystemBars() {
        // If you already know what the value of the `viewport-fit=` meta tag is going to be,
        // passing it here through `initialViewportFitValueHint` can help prevent layout shifting.
        val configuredInitialViewportFitValueHint = config.getString("initialViewportFitValueHint", "")
        hasViewportCover = "cover" == configuredInitialViewportFitValueHint

        // PluginConfig.getString returns the given default when the key is absent, so the elvis never fires.
        val style = (config.getString("style", STYLE_DEFAULT) ?: STYLE_DEFAULT).uppercase(Locale.US)
        val hidden = config.getBoolean("hidden", false)

        val configuredInsetsHandling = config.getString("insetsHandling", INSETS_HANDLING_CSS)
        if (INSETS_HANDLING_CSS == configuredInsetsHandling ||
            INSETS_HANDLING_DISABLE == configuredInsetsHandling ||
            INSETS_HANDLING_NATIVE == configuredInsetsHandling
        ) {
            insetsHandling = configuredInsetsHandling
        } else {
            Logger.warn(
                "SystemBars",
                "Unknown insetsHandling value '$configuredInsetsHandling'. Falling back to '$INSETS_HANDLING_CSS'.",
            )
            insetsHandling = INSETS_HANDLING_CSS
        }

        warnAboutUnsupportedConfigurationValues()

        initWindowInsetsListener()

        bridge.executeOnMainThread {
            setStyle(style, "")
            setHidden(hidden, "")
        }
    }

    @PluginMethod
    public fun setStyle(call: PluginCall) {
        // PluginCall.getString returns the given default when the key is absent, so the elvis never fires.
        val bar = call.getString("bar", "") ?: ""
        val style = call.getString("style", STYLE_DEFAULT) ?: STYLE_DEFAULT

        bridge.executeOnMainThread {
            setStyle(style, bar)
            call.resolve()
        }
    }

    @PluginMethod
    public fun show(call: PluginCall) {
        val bar = call.getString("bar", "") ?: ""

        bridge.executeOnMainThread {
            setHidden(false, bar)
            call.resolve()
        }
    }

    @PluginMethod
    public fun hide(call: PluginCall) {
        val bar = call.getString("bar", "") ?: ""

        bridge.executeOnMainThread {
            setHidden(true, bar)
            call.resolve()
        }
    }

    @PluginMethod
    public fun setAnimation(call: PluginCall) {
        call.resolve()
    }

    private fun initWindowInsetsListener() {
        if (INSETS_HANDLING_DISABLE == insetsHandling) {
            return
        }

        val view = activity.window.decorView

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val shouldPassthroughInsets = getWebViewMajorVersion() >= WEBVIEW_VERSION_WITH_SAFE_AREA_FIX && hasViewportCover

            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            if (shouldPassthroughInsets) {
                // We need to correct for a possible shown IME
                v.setPadding(0, 0, 0, if (keyboardVisible) imeInsets.bottom else 0)

                val newInsets =
                    WindowInsetsCompat.Builder(insets)
                        .setInsets(
                            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                            Insets.of(
                                systemBarsInsets.left,
                                systemBarsInsets.top,
                                systemBarsInsets.right,
                                getBottomInset(systemBarsInsets, keyboardVisible),
                            ),
                        )
                        .build()

                injectSafeAreaCSS(newInsets)

                return@setOnApplyWindowInsetsListener newInsets
            }

            // We need to correct for a possible shown IME
            v.setPadding(
                systemBarsInsets.left,
                systemBarsInsets.top,
                systemBarsInsets.right,
                if (keyboardVisible) imeInsets.bottom else systemBarsInsets.bottom,
            )

            // Returning `WindowInsetsCompat.CONSUMED` breaks recalculation of safe area insets
            // So we have to explicitly set insets to `0`
            // See: https://issues.chromium.org/issues/461332423
            val newInsets =
                WindowInsetsCompat.Builder(insets)
                    .setInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 0, 0, 0))
                    .build()

            injectSafeAreaCSS(newInsets)

            newInsets
        }
    }

    private fun injectSafeAreaCSS(insets: WindowInsetsCompat) {
        if (INSETS_HANDLING_CSS != insetsHandling) {
            return
        }

        val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

        // Convert pixels to density-independent pixels
        val density = activity.resources.displayMetrics.density
        val topPx = systemBarsInsets.top / density
        val rightPx = systemBarsInsets.right / density
        // For native insets the value gets automatically corrected when the IME is visible (in newer WebView versions),
        // but for these injected values we have to handle that manually (for all WebView versions).
        val bottomPx = (if (keyboardVisible) 0 else systemBarsInsets.bottom) / density
        val leftPx = systemBarsInsets.left / density

        // Execute JavaScript to inject the CSS
        bridge.executeOnMainThread {
            val script = String.format(Locale.US, safeAreaCSSFormat, topPx.toInt(), rightPx.toInt(), bottomPx.toInt(), leftPx.toInt())

            bridge.webView.evaluateJavascript(script, null)
        }
    }

    private fun setStyle(style: String, bar: String) {
        val resolvedStyle = if (style == STYLE_DEFAULT) getStyleForTheme() else style

        val window = activity.window
        val windowInsetsControllerCompat = WindowCompat.getInsetsController(window, window.decorView)
        if (bar.isEmpty() || bar == BAR_STATUS_BAR) {
            currentStatusBarStyle = resolvedStyle
            windowInsetsControllerCompat.isAppearanceLightStatusBars = resolvedStyle != STYLE_DARK
        }

        if (bar.isEmpty() || bar == BAR_GESTURE_BAR) {
            currentGestureBarStyle = resolvedStyle
            windowInsetsControllerCompat.isAppearanceLightNavigationBars = resolvedStyle != STYLE_DARK
        }

        activity.window.decorView.setBackgroundColor(getThemeColor(context, android.R.attr.windowBackground))
    }

    // SystemBarsTest looks this up reflectively as setHidden(boolean, String).
    private fun setHidden(hide: Boolean, bar: String) {
        val window = activity.window
        val windowInsetsControllerCompat = WindowCompat.getInsetsController(window, window.decorView)

        if (hide) {
            if (bar.isEmpty()) {
                windowInsetsControllerCompat.hide(WindowInsetsCompat.Type.systemBars())
            } else if (bar == BAR_STATUS_BAR) {
                windowInsetsControllerCompat.hide(WindowInsetsCompat.Type.statusBars())
            } else if (bar == BAR_GESTURE_BAR) {
                windowInsetsControllerCompat.hide(WindowInsetsCompat.Type.navigationBars())
            }
            return
        }

        if (bar.isEmpty()) {
            windowInsetsControllerCompat.show(WindowInsetsCompat.Type.systemBars())
        } else if (bar == BAR_STATUS_BAR) {
            windowInsetsControllerCompat.show(WindowInsetsCompat.Type.statusBars())
        } else if (bar == BAR_GESTURE_BAR) {
            windowInsetsControllerCompat.show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    private fun getStyleForTheme(): String {
        val currentNightMode = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (currentNightMode != Configuration.UI_MODE_NIGHT_YES) {
            return STYLE_LIGHT
        }
        return STYLE_DARK
    }

    public fun getThemeColor(context: Context, attrRes: Int): Int {
        val typedValue = TypedValue()

        val theme = context.theme
        theme.resolveAttribute(attrRes, typedValue, true)
        return typedValue.data
    }

    private fun getWebViewMajorVersion(): Int {
        val info = WebViewCompat.getCurrentWebViewPackage(context)
        val versionName = info?.versionName
        if (versionName != null) {
            // Pattern.split keeps java.lang.String.split semantics.
            val versionSegments = DOT.split(versionName)
            return Integer.parseInt(versionSegments[0])
        }

        return 0
    }

    private fun getBottomInset(systemBarsInsets: Insets, keyboardVisible: Boolean): Int {
        if (getWebViewMajorVersion() < WEBVIEW_VERSION_WITH_SAFE_AREA_KEYBOARD_FIX) {
            // This is a workaround for webview versions that have a bug
            // that causes the bottom inset to be incorrect if the IME is visible
            // See: https://issues.chromium.org/issues/457682720

            if (keyboardVisible) {
                return 0
            }
        }

        return systemBarsInsets.bottom
    }

    private companion object {
        const val STYLE_LIGHT = "LIGHT"
        const val STYLE_DARK = "DARK"
        const val STYLE_DEFAULT = "DEFAULT"
        const val BAR_STATUS_BAR = "StatusBar"
        const val BAR_GESTURE_BAR = "NavigationBar"

        const val INSETS_HANDLING_CSS = "css"
        const val INSETS_HANDLING_DISABLE = "disable"
        const val INSETS_HANDLING_NATIVE = "native"

        // https://issues.chromium.org/issues/40699457
        const val WEBVIEW_VERSION_WITH_SAFE_AREA_FIX = 140

        // https://issues.chromium.org/issues/457682720
        const val WEBVIEW_VERSION_WITH_SAFE_AREA_KEYBOARD_FIX = 144

        val DOT: Pattern = Pattern.compile("\\.")

        // A Java text block ends with a newline; trimIndent() drops it, hence the explicit "\n".
        val viewportMetaJSFunction =
            """
            function capacitorSystemBarsCheckMetaViewport() {
                const meta = document.querySelectorAll("meta[name=viewport]");
                if (meta.length == 0) {
                    return false;
                }
                // get the last found meta viewport tag
                const metaContent = meta[meta.length - 1].content;
                return metaContent.includes("viewport-fit=cover");
            }
            capacitorSystemBarsCheckMetaViewport();
            """.trimIndent() + "\n"

        val safeAreaCSSFormat =
            """
            try {
              document.documentElement.style.setProperty("--safe-area-inset-top", "%dpx");
              document.documentElement.style.setProperty("--safe-area-inset-right", "%dpx");
              document.documentElement.style.setProperty("--safe-area-inset-bottom", "%dpx");
              document.documentElement.style.setProperty("--safe-area-inset-left", "%dpx");
            } catch(e) { console.error('Error injecting safe area CSS:', e); }
            """.trimIndent() + "\n"
    }
}
