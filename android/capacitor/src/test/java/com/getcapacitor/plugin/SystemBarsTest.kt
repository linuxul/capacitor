package com.getcapacitor.plugin

import android.view.View
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.getcapacitor.Bridge
import com.getcapacitor.JSObject
import com.getcapacitor.MessageHandler
import com.getcapacitor.PluginCall
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SystemBarsTest {
    @Test
    fun showWithEmptyBarShowsSystemBars() {
        val controller = callBarMethod("", hidden = false)

        verify(controller).show(WindowInsetsCompat.Type.systemBars())
        verify(controller, never()).show(WindowInsetsCompat.Type.statusBars())
        verify(controller, never()).show(WindowInsetsCompat.Type.navigationBars())
    }

    @Test
    fun showWithStatusBarShowsOnlyStatusBars() {
        val controller = callBarMethod("StatusBar", hidden = false)

        verify(controller).show(WindowInsetsCompat.Type.statusBars())
        verify(controller, never()).show(WindowInsetsCompat.Type.systemBars())
        verify(controller, never()).show(WindowInsetsCompat.Type.navigationBars())
    }

    @Test
    fun showWithNavigationBarShowsOnlyNavigationBars() {
        val controller = callBarMethod("NavigationBar", hidden = false)

        verify(controller).show(WindowInsetsCompat.Type.navigationBars())
        verify(controller, never()).show(WindowInsetsCompat.Type.systemBars())
        verify(controller, never()).show(WindowInsetsCompat.Type.statusBars())
    }

    @Test
    fun hideWithEmptyBarHidesSystemBars() {
        val controller = callBarMethod("", hidden = true)

        verify(controller).hide(WindowInsetsCompat.Type.systemBars())
        verify(controller, never()).hide(WindowInsetsCompat.Type.statusBars())
        verify(controller, never()).hide(WindowInsetsCompat.Type.navigationBars())
    }

    @Test
    fun hideWithStatusBarHidesOnlyStatusBars() {
        val controller = callBarMethod("StatusBar", hidden = true)

        verify(controller).hide(WindowInsetsCompat.Type.statusBars())
        verify(controller, never()).hide(WindowInsetsCompat.Type.systemBars())
        verify(controller, never()).hide(WindowInsetsCompat.Type.navigationBars())
    }

    @Test
    fun hideWithNavigationBarHidesOnlyNavigationBars() {
        val controller = callBarMethod("NavigationBar", hidden = true)

        verify(controller).hide(WindowInsetsCompat.Type.navigationBars())
        verify(controller, never()).hide(WindowInsetsCompat.Type.systemBars())
        verify(controller, never()).hide(WindowInsetsCompat.Type.statusBars())
    }

    private fun callBarMethod(bar: String, hidden: Boolean): WindowInsetsControllerCompat {
        val plugin = SystemBars()
        val bridge = mock<Bridge>()
        val activity = mock<AppCompatActivity>()
        val window = mock<Window>()
        val decorView = mock<View>()
        val controller = mock<WindowInsetsControllerCompat>()

        whenever(bridge.activity).thenReturn(activity)
        whenever(activity.window).thenReturn(window)
        whenever(window.decorView).thenReturn(decorView)
        // show()/hide() do their work inside executeOnMainThread; run it inline.
        doAnswer { it.getArgument<Runnable>(0).run() }.whenever(bridge).executeOnMainThread(any())

        plugin.bridge = bridge

        mockStatic(WindowCompat::class.java).use { windowCompat ->
            windowCompat
                .`when`<WindowInsetsControllerCompat> { WindowCompat.getInsetsController(window, decorView) }
                .thenReturn(controller)

            val methodName = if (hidden) "hide" else "show"
            val call = PluginCall(mock<MessageHandler>(), "SystemBars", "1", methodName, JSObject().put("bar", bar))
            if (hidden) plugin.hide(call) else plugin.show(call)
        }

        return controller
    }
}
