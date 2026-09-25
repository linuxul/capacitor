package com.getcapacitor

import android.os.Bundle
import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The call that started an activity for result, kept in the saved instance state across a recreation of the app.
 */
class InstanceStateCodecTest {
    @get:Rule
    val logs = RecordingLogSink()

    private val values = HashMap<String, Any?>()
    private val state = bundleOf(values)

    /** A Bundle that keeps its strings and bundles in [values]. */
    private fun bundleOf(values: MutableMap<String, Any?>): Bundle {
        val bundle = mock<Bundle>()
        doAnswer { values[it.getArgument(0)] = it.getArgument<String?>(1) }.whenever(bundle).putString(any(), anyOrNull())
        doAnswer { values[it.getArgument(0)] = it.getArgument<Bundle?>(1) }.whenever(bundle).putBundle(any(), anyOrNull())
        whenever(bundle.getString(any())).thenAnswer { values[it.getArgument(0)] as String? }
        whenever(bundle.getBundle(any())).thenAnswer { values[it.getArgument(0)] as Bundle? }
        return bundle
    }

    private fun call(): PluginCall = PluginCall(mock(), "Camera", "cb-1", "getPhoto", JSObject().put("quality", 90))

    @Test
    fun aWrittenCallIsReadBack() {
        val pluginState = mock<Bundle>()

        InstanceStateCodec.write(state, call(), pluginState)
        val saved = InstanceStateCodec.read(state)!!

        assertEquals("Camera", saved.pluginId)
        assertEquals("getPhoto", saved.methodName)
        assertEquals(90, saved.options!!.getInt("quality"))
        assertSame(pluginState, saved.pluginState)
    }

    @Test
    fun theKeysAreTheOnesEarlierVersionsWrote() {
        InstanceStateCodec.write(state, call(), mock())

        assertEquals(
            setOf(
                "capacitorLastActivityPluginId",
                "capacitorLastActivityPluginMethod",
                "capacitorLastPluginCallOptions",
                "capacitorLastPluginCallBundle"
            ),
            values.keys
        )
        assertEquals("""{"quality":90}""", values["capacitorLastPluginCallOptions"])
    }

    @Test
    fun aStateWithoutCallReadsAsNothing() {
        assertNull(InstanceStateCodec.read(state))
    }

    @Test
    fun invalidOptionsAreDroppedAndTheRestIsRead() {
        val pluginState = mock<Bundle>()
        values["capacitorLastActivityPluginId"] = "Camera"
        values["capacitorLastActivityPluginMethod"] = "getPhoto"
        values["capacitorLastPluginCallOptions"] = "{not json"
        values["capacitorLastPluginCallBundle"] = pluginState

        val saved = InstanceStateCodec.read(state)!!

        assertNull(saved.options)
        assertEquals("getPhoto", saved.methodName)
        assertSame(pluginState, saved.pluginState)
        assertEquals(1, logs.count(Log.ERROR, "Unable to restore plugin call, unable to parse persisted JSON object"))
    }

    @Test
    fun optionsWithoutMethodAreNotRead() {
        values["capacitorLastActivityPluginId"] = "Camera"
        values["capacitorLastPluginCallOptions"] = "{not json"

        val saved = InstanceStateCodec.read(state)!!

        assertNull(saved.methodName)
        assertNull(saved.options)
        assertNull(saved.pluginState)
        assertEquals(0, logs.entries.size)
    }
}
