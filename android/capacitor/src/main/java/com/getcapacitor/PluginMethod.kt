package com.getcapacitor

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class PluginMethod(val returnType: String = RETURN_PROMISE) {
    companion object {
        const val RETURN_PROMISE = "promise"

        const val RETURN_CALLBACK = "callback"

        const val RETURN_NONE = "none"
    }
}
