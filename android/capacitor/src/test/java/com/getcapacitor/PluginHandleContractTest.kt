package com.getcapacitor

import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Pins the reflection contract between Kotlin plugins and [PluginHandle]: which functions get indexed, under
 * which name and return type, and what the annotations look like at runtime.
 */
class PluginHandleContractTest {
    @get:Rule
    val logs = RecordingLogSink()

    @CapacitorPlugin(name = "T", permissions = [Permission(alias = "a", strings = ["x"])])
    class TestPlugin : Plugin() {
        var permissionCallbackCalls = 0

        @PluginMethod
        fun echo(call: PluginCall) {}

        @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
        fun watch(call: PluginCall) {}

        @PluginMethod(thread = PluginThread.MAIN)
        fun present(call: PluginCall) {}

        @PermissionCallback
        private fun permissionsDone(call: PluginCall?) {
            permissionCallbackCalls++
        }
    }

    @CapacitorPlugin
    class UnnamedPlugin : Plugin()

    @CapacitorPlugin(name = "Internal")
    class InternalMethodPlugin : Plugin() {
        @PluginMethod
        internal fun hidden(call: PluginCall) {}

        @PluginMethod
        fun visible(call: PluginCall) {}
    }

    @CapacitorPlugin(name = "Wrong")
    class WrongSignaturePlugin : Plugin() {
        @PluginMethod
        fun twoArguments(call: PluginCall, extra: String) {}
    }

    @CapacitorPlugin(name = "NoArgs")
    class NoArgumentPlugin : Plugin() {
        @PluginMethod
        fun noArguments() {}
    }

    @CapacitorPlugin(name = "Suspending")
    class SuspendingPlugin : Plugin() {
        @PluginMethod
        suspend fun later(call: PluginCall) {}
    }

    class UnannotatedPlugin : Plugin()

    private val ownMethods = setOf("echo", "watch", "present")
    private val inheritedMethods = setOf("addListener", "removeListener", "removeAllListeners", "checkPermissions", "requestPermissions")

    @Test
    fun idComesFromTheAnnotationName() {
        assertEquals("T", PluginHandle(mock<Bridge>(), TestPlugin()).id)
    }

    @Test
    fun idFallsBackToTheSimpleClassName() {
        assertEquals("UnnamedPlugin", PluginHandle(mock<Bridge>(), UnnamedPlugin()).id)
    }

    @Test
    fun indexesPublicPluginMethodsIncludingInheritedOnes() {
        val handle = PluginHandle(mock<Bridge>(), TestPlugin())

        assertEquals(ownMethods + inheritedMethods, handle.methods.map { it.name }.toSet())
    }

    @Test
    fun returnTypesAreReadFromTheAnnotation() {
        val handle = PluginHandle(mock<Bridge>(), TestPlugin())
        val returnTypes = handle.methods.associate { it.name to it.returnType }

        assertEquals(PluginMethod.RETURN_PROMISE, returnTypes["echo"])
        assertEquals(PluginMethod.RETURN_CALLBACK, returnTypes["watch"])
        assertEquals(PluginMethod.RETURN_NONE, returnTypes["addListener"])
        assertEquals(PluginMethod.RETURN_NONE, returnTypes["removeListener"])
        assertEquals(PluginMethod.RETURN_PROMISE, returnTypes["removeAllListeners"])
        assertEquals("promise", PluginMethod.RETURN_PROMISE)
        assertEquals("callback", PluginMethod.RETURN_CALLBACK)
        assertEquals("none", PluginMethod.RETURN_NONE)
    }

    @Test
    fun threadsAreReadFromTheAnnotation() {
        val threads = PluginHandle(mock<Bridge>(), TestPlugin()).methods.associate { it.name to it.thread }

        assertEquals(PluginThread.MAIN, threads["present"])
        assertEquals(PluginThread.PLUGIN, threads["echo"])
        assertEquals(PluginThread.PLUGIN, threads["addListener"])
    }

    @Test
    fun javaPluginsUseTheSameAnnotation() {
        val methods = PluginHandle(mock<Bridge>(), JavaAnnotatedPlugin()).methods.associateBy { it.name }

        assertEquals(PluginMethod.RETURN_NONE, methods["fireAndForget"]?.returnType)
        assertEquals(PluginThread.PLUGIN, methods["fireAndForget"]?.thread)
        assertEquals(PluginThread.MAIN, methods["present"]?.thread)
    }

    @Test
    fun permissionsAreReadableFromTheAnnotation() {
        val annotation = PluginHandle(mock<Bridge>(), TestPlugin()).pluginAnnotation

        assertEquals(1, annotation.permissions.size)
        assertEquals("a", annotation.permissions[0].alias)
        assertEquals(listOf("x"), annotation.permissions[0].strings.toList())
    }

    @Test
    fun handleWiresThePluginToTheBridge() {
        val bridge = mock<Bridge>()
        val plugin = TestPlugin()
        val handle = PluginHandle(bridge, plugin)

        assertSame(plugin, handle.instance)
        assertSame(bridge, plugin.bridge)
        assertSame(handle, plugin.pluginHandle)
    }

    @Test
    fun privatePermissionCallbackIsFoundAndInvocableReflectively() {
        val plugin = TestPlugin()
        val callback =
            TestPlugin::class.java.declaredMethods.single { it.isAnnotationPresent(PermissionCallback::class.java) }

        // Not mangled and taking exactly the saved call: this is what Plugin.initializeActivityLaunchers registers
        // and what the permission result handler invokes after setAccessible(true).
        assertEquals("permissionsDone", callback.name)
        assertEquals(listOf(PluginCall::class.java), callback.parameterTypes.toList())

        callback.isAccessible = true
        callback.invoke(plugin, null)
        assertEquals(1, plugin.permissionCallbackCalls)
    }

    @Test
    fun internalPluginMethodIsNotIndexed() {
        val handle = PluginHandle(mock<Bridge>(), InternalMethodPlugin())
        val names = handle.methods.map { it.name }

        assertTrue(names.contains("visible"))
        // Kotlin mangles the JVM name of internal functions, so JavaScript could never address it by "hidden".
        assertFalse(names.contains("hidden"))
    }

    @Test
    fun pluginMethodWithExtraArgumentsIsRejected() {
        assertInvalidPlugin(WrongSignaturePlugin(), "twoArguments")
    }

    @Test
    fun pluginMethodWithoutArgumentsIsRejected() {
        assertInvalidPlugin(NoArgumentPlugin(), "noArguments")
    }

    @Test
    fun suspendPluginMethodIsRejected() {
        // suspend adds a Continuation parameter, so the JVM signature is no longer (PluginCall).
        assertInvalidPlugin(SuspendingPlugin(), "later")
    }

    @Test
    fun pluginWithoutTheAnnotationIsRejected() {
        try {
            PluginHandle(mock<Bridge>(), UnannotatedPlugin())
            fail("expected InvalidPluginException")
        } catch (e: InvalidPluginException) {
            assertNotNull(e.message)
        }
    }

    private fun assertInvalidPlugin(plugin: Plugin, methodName: String) {
        try {
            PluginHandle(mock<Bridge>(), plugin)
            fail("expected InvalidPluginException")
        } catch (e: InvalidPluginException) {
            val message = e.message ?: ""
            assertTrue(message, message.contains(plugin.javaClass.name))
            assertTrue(message, message.contains(methodName))
            assertTrue(message, message.contains("single PluginCall"))
        }
    }
}
