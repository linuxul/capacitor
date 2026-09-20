package com.getcapacitor

import java.lang.reflect.Method

class PluginMethodHandle(
    // The reflect method reference
    val method: Method,
    methodDecorator: PluginMethod,
) {
    // The name of the method
    // (nullable only because unit tests hand in Mockito mocks whose getters return null)
    val name: String? = method.name

    // The return type of the method (see PluginMethod for constants)
    val returnType: String? = methodDecorator.returnType
}
