package com.getcapacitor

// The (cause) constructors mirror java.lang.Exception(Throwable), which uses the cause's toString() as the message.

/**
 * Thrown when a plugin fails to instantiate
 */
public class PluginLoadException @JvmOverloads public constructor(message: String?, cause: Throwable? = null) : Exception(message, cause) {
    public constructor(cause: Throwable?) : this(cause?.toString(), cause)
}

internal class InvalidPluginException(message: String?) : Exception(message)

internal class InvalidPluginMethodException(message: String?, cause: Throwable? = null) : Exception(message, cause) {
    public constructor(cause: Throwable?) : this(cause?.toString(), cause)
}
