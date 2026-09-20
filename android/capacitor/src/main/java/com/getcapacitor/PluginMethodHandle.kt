package com.getcapacitor

import java.lang.reflect.Method

public class PluginMethodHandle(
    // The reflect method reference
    public val method: Method,
    methodDecorator: PluginMethod
) {
    // The name of the method
    // (nullable only because unit tests hand in Mockito mocks whose getters return null)
    public val name: String? = method.name

    // The return type of the method (see PluginMethod for constants)
    public val returnType: String? = methodDecorator.returnType
}
