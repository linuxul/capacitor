package com.getcapacitor.plugin

import android.view.View
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.getcapacitor.Bridge
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SystemBarsTest {
    @Test
    fun showWithEmptyBarShowsSystemBars() {
        val controller = invokeSetHidden("")

        verify(controller).show(WindowInsetsCompat.Type.systemBars())
        verify(controller, never()).show(WindowInsetsCompat.Type.statusBars())
        verify(controller, never()).show(WindowInsetsCompat.Type.navigationBars())
    }

    @Test
    fun showWithStatusBarShowsOnlyStatusBars() {
        val controller = invokeSetHidden("StatusBar")

        verify(controller).show(WindowInsetsCompat.Type.statusBars())
        verify(controller, never()).show(WindowInsetsCompat.Type.systemBars())
        verify(controller, never()).show(WindowInsetsCompat.Type.navigationBars())
    }

    @Test
    fun showWithNavigationBarShowsOnlyNavigationBars() {
        val controller = invokeSetHidden("NavigationBar")

        verify(controller).show(WindowInsetsCompat.Type.navigationBars())
        verify(controller, never()).show(WindowInsetsCompat.Type.systemBars())
        verify(controller, never()).show(WindowInsetsCompat.Type.statusBars())
    }

    private fun invokeSetHidden(bar: String): WindowInsetsControllerCompat {
        val plugin = SystemBars()
        val bridge = mock<Bridge>()
        val activity = mock<AppCompatActivity>()
        val window = mock<Window>()
        val decorView = mock<View>()
        val controller = mock<WindowInsetsControllerCompat>()

        whenever(bridge.activity).thenReturn(activity)
        whenever(activity.window).thenReturn(window)
        whenever(window.decorView).thenReturn(decorView)

        plugin.bridge = bridge

        mockStatic(WindowCompat::class.java).use { windowCompat ->
            windowCompat
                .`when`<WindowInsetsControllerCompat> { WindowCompat.getInsetsController(window, decorView) }
                .thenReturn(controller)

            // setHidden is private; it is looked up by its JVM signature (boolean, String).
            val setHidden = SystemBars::class.java.getDeclaredMethod("setHidden", Boolean::class.javaPrimitiveType, String::class.java)
            setHidden.isAccessible = true
            setHidden.invoke(plugin, false, bar)
        }

        return controller
    }
}
