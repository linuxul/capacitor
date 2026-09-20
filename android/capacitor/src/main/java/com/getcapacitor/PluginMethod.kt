package com.getcapacitor

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
public annotation class PluginMethod(val returnType: String = RETURN_PROMISE) {
    public companion object {
        public const val RETURN_PROMISE: String = "promise"

        public const val RETURN_CALLBACK: String = "callback"

        public const val RETURN_NONE: String = "none"
    }
}
