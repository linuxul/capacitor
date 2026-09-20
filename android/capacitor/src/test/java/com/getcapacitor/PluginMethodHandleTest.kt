package com.getcapacitor

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.BDDMockito.given
import org.mockito.kotlin.mock
import java.lang.reflect.Method

class PluginMethodHandleTest {
    @Test
    fun getNameReturnsMethodName() {
        val pluginMethod = mock<PluginMethod>()
        val mockMethod = mock<Method>()

        given(mockMethod.name).willReturn("methodName")
        val pluginMethodHandle = PluginMethodHandle(mockMethod, pluginMethod)

        assertEquals(pluginMethodHandle.name, "methodName")
    }

    @Test
    fun getMethodHandleReturnsMethodHandle() {
        val pluginMethod = mock<PluginMethod>()
        val mockMethod = mock<Method>()

        given(pluginMethod.returnType).willReturn("returnType")
        val pluginMethodHandle = PluginMethodHandle(mockMethod, pluginMethod)

        assertEquals(pluginMethodHandle.returnType, "returnType")
    }

    @Test
    fun getMethodReturnsMethod() {
        val pluginMethod = mock<PluginMethod>()
        val mockMethod = mock<Method>()

        val pluginMethodHandle = PluginMethodHandle(mockMethod, pluginMethod)

        assertEquals(pluginMethodHandle.method, mockMethod)
    }
}
