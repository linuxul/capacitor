package com.getcapacitor

import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/**
 * Plugins such as Camera adjust the permission states they report by overriding [Plugin.permissionStates],
 * so the override has to reach both [Plugin.checkPermissions] and [Plugin.getPermissionState].
 */
class PluginPermissionStatesTest {
    @CapacitorPlugin(name = "Adjusted", permissions = [Permission(alias = "camera", strings = ["x"])])
    class AdjustedPlugin : Plugin() {
        override val permissionStates: Map<String, PermissionState>
            get() = mapOf("camera" to PermissionState.GRANTED)
    }

    @Test
    fun overriddenStatesAreReportedByCheckPermissions() {
        val handler = mock<MessageHandler>()
        val plugin = AdjustedPlugin()
        PluginHandle(mock<Bridge>(), plugin)

        plugin.checkPermissions(PluginCall(handler, "Adjusted", "1", "checkPermissions", JSObject()))

        val result = argumentCaptor<PluginResult>()
        verify(handler).sendResponseMessage(any(), result.capture(), isNull())
        // The state is stored as the enum itself. Android's org.json writes it with toString() ("granted"), while
        // the reference implementation used by unit tests writes name(), so compare the state, not its spelling.
        val reported = JSObject(result.firstValue.toString()).getString("camera")
        assertEquals(PermissionState.GRANTED, PermissionState.byState(reported!!))
    }

    @Test
    fun overriddenStatesAreReportedByGetPermissionState() {
        val plugin = AdjustedPlugin()
        PluginHandle(mock<Bridge>(), plugin)

        assertEquals(PermissionState.GRANTED, plugin.getPermissionState("camera"))
    }
}
