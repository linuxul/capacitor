package com.getcapacitor

/**
 * Marks a plugin function that JavaScript can call.
 *
 * @property returnType how JavaScript receives the result: [RETURN_PROMISE], [RETURN_CALLBACK] or [RETURN_NONE]
 * @property thread the thread the function runs on, see [PluginThread]
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
public annotation class PluginMethod(val returnType: String = RETURN_PROMISE, val thread: PluginThread = PluginThread.PLUGIN) {
    public companion object {
        public const val RETURN_PROMISE: String = "promise"

        public const val RETURN_CALLBACK: String = "callback"

        public const val RETURN_NONE: String = "none"
    }
}
