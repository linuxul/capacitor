package com.getcapacitor

import java.lang.reflect.Method
import kotlin.coroutines.Continuation

public class PluginMethodHandle(
    // The reflect method reference
    public val method: Method,
    methodDecorator: PluginMethod
) {
    // The name of the method
    public val name: String = method.name

    // The return type of the method (see PluginMethod for constants)
    public val returnType: String = methodDecorator.returnType

    // The thread the method runs on
    public val thread: PluginThread = methodDecorator.thread

    // Whether the method is a Kotlin suspend function: on the JVM it takes a Continuation after the PluginCall.
    public val isSuspend: Boolean = isSuspendSignature(method)

    internal companion object {
        fun isPlainSignature(method: Method): Boolean = method.parameterTypes.contentEquals(arrayOf(PluginCall::class.java))

        fun isSuspendSignature(method: Method): Boolean =
            method.parameterTypes.contentEquals(arrayOf(PluginCall::class.java, Continuation::class.java))
    }
}
