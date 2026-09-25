package com.getcapacitor

/**
 * Rejects the plugin call it is thrown from, instead of the call being answered with a generic error.
 *
 * Throw it from a `@PluginMethod` (a plain or a `suspend` one) or from a `@PermissionCallback` or
 * `@ActivityCallback`; the bridge rejects the call with [message], [code] and [data], as
 * `call.reject(message, code, cause, data)` would, and logs [cause] when there is one.
 *
 * ```kotlin
 * @PluginMethod
 * public fun open(call: PluginCall) {
 *     val path = call.getString("path") ?: throw PluginException("Must provide a path", code = "INVALID_ARGUMENT")
 *     ...
 * }
 * ```
 *
 * Open, so a plugin can declare its own errors, such as `class DeniedException : PluginException("Denied", "DENIED")`.
 */
public open class PluginException @JvmOverloads constructor(
    message: String,
    public val code: String? = null,
    public val data: JSObject? = null,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Rejects the call with what [exception] carries. A cause that is not an [Exception] (an [Error]) is logged
 * separately, because [PluginCall.reject] only takes an [Exception].
 */
internal fun PluginCall.rejectWith(exception: PluginException) {
    val cause = exception.cause
    if (cause != null && cause !is Exception) {
        Logger.error(Logger.tags("Plugin"), exception.message, cause)
    }
    reject(exception.message, exception.code, cause as? Exception, exception.data)
}
