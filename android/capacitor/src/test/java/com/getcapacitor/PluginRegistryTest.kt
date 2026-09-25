package com.getcapacitor

import android.net.Uri
import android.util.Log
import com.getcapacitor.annotation.CapacitorPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Registering plugins with the bridge: under which id, what happens to plugins that cannot be registered, and how
 * the registered ones are reached.
 */
class PluginRegistryTest {
    @get:Rule
    val logs = RecordingLogSink()

    @CapacitorPlugin(name = "Named")
    class NamedPlugin : Plugin() {
        var loaded = 0

        override fun load() {
            loaded++
        }
    }

    @CapacitorPlugin
    class UnnamedPlugin : Plugin()

    class NotAnnotatedPlugin : Plugin()

    @CapacitorPlugin(name = "Invalid")
    class InvalidPlugin : Plugin() {
        @PluginMethod
        fun twoArguments(call: PluginCall, extra: String) {}
    }

    @CapacitorPlugin(name = "Failing")
    class FailingPlugin : Plugin() {
        init {
            throw IllegalStateException("constructor failed")
        }
    }

    @CapacitorPlugin(name = "Named")
    class OtherNamedPlugin : Plugin()

    @CapacitorPlugin(name = "Router")
    class RouterPlugin : Plugin() {
        var answer: Boolean? = null

        override fun shouldOverrideLoad(url: Uri?): Boolean? = answer
    }

    private val bridge = mock<Bridge>()
    private val registry = PluginRegistry(bridge)

    @Test
    fun aClassIsRegisteredUnderItsNameAndLoaded() {
        registry.register(NamedPlugin::class.java)

        val handle = registry["Named"]
        assertNotNull(handle)
        val plugin = handle!!.instance as NamedPlugin
        assertEquals(1, plugin.loaded)
        assertSame(bridge, plugin.bridge)
    }

    @Test
    fun aClassWithoutNameIsRegisteredUnderItsClassName() {
        registry.register(UnnamedPlugin::class.java)

        assertNotNull(registry["UnnamedPlugin"])
    }

    @Test
    fun anInstanceIsRegisteredAsItIs() {
        val plugin = NamedPlugin()

        registry.register(plugin)

        assertSame(plugin, registry["Named"]!!.instance)
        assertEquals(1, plugin.loaded)
    }

    @Test
    fun aClassWithoutAnnotationIsLeftOut() {
        registry.register(NotAnnotatedPlugin::class.java)
        registry.register(NotAnnotatedPlugin())

        assertTrue(registry.handles.isEmpty())
        assertEquals(2, logs.count(Log.ERROR, "Plugin doesn't have the @CapacitorPlugin annotation. Please add it"))
    }

    @Test
    fun anInvalidPluginIsLeftOut() {
        registry.register(InvalidPlugin::class.java)

        assertNull(registry["Invalid"])
        val message =
            "Plugin ${InvalidPlugin::class.java.name} is invalid. Ensure the @CapacitorPlugin annotation exists on the plugin class " +
                "and the class extends Plugin"
        assertEquals(1, logs.count(Log.ERROR, message))
    }

    @Test
    fun aPluginThatFailsToLoadIsLeftOutAndTheOthersStay() {
        registry.register(NamedPlugin::class.java)
        registry.register(FailingPlugin::class.java)

        assertNull(registry["Failing"])
        assertNotNull(registry["Named"])
        assertEquals(1, logs.count(Log.ERROR, "Plugin ${FailingPlugin::class.java.name} failed to load"))
    }

    @Test
    fun aLaterPluginWithTheSameIdReplacesTheEarlierOne() {
        registry.register(NamedPlugin::class.java)
        registry.register(OtherNamedPlugin::class.java)

        assertTrue(registry["Named"]!!.instance is OtherNamedPlugin)
        assertEquals(1, registry.handles.size)
    }

    @Test
    fun forEachReachesEveryPlugin() {
        registry.register(NamedPlugin::class.java)
        registry.register(UnnamedPlugin::class.java)
        val reached = ArrayList<Plugin>()

        registry.forEach { reached.add(it) }

        assertEquals(setOf(NamedPlugin::class.java, UnnamedPlugin::class.java), reached.map { it.javaClass }.toSet())
    }

    @Test
    fun theFirstPluginAnswerDecidesALoad() {
        val url = mock<Uri>()
        val router = RouterPlugin()
        registry.register(router)
        registry.register(NamedPlugin::class.java)

        assertNull(registry.shouldOverrideLoad(url))

        router.answer = false
        assertEquals(false, registry.shouldOverrideLoad(url))

        router.answer = true
        assertEquals(true, registry.shouldOverrideLoad(url))
    }
}
