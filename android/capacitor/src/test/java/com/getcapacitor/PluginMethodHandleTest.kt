package com.getcapacitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PluginMethodHandleTest {
    class Methods {
        @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
        fun watch(call: PluginCall) {}
    }

    @Test
    fun exposesTheReflectedMethodItsNameAndItsReturnType() {
        val method = Methods::class.java.getMethod("watch", PluginCall::class.java)

        val handle = PluginMethodHandle(method, method.getAnnotation(PluginMethod::class.java)!!)

        assertSame(method, handle.method)
        assertEquals("watch", handle.name)
        assertEquals(PluginMethod.RETURN_CALLBACK, handle.returnType)
    }
}
