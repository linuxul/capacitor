package com.getcapacitor

// Each exception keeps the three Java constructor shapes: (String), (Throwable) and (String, Throwable).
// The (Throwable) form mirrors java.lang.Exception(Throwable), which uses the cause's toString() as the message.

/**
 * Thrown when a plugin fails to instantiate
 */
class PluginLoadException
    @JvmOverloads
    constructor(message: String?, cause: Throwable? = null) : Exception(message, cause) {
        constructor(cause: Throwable?) : this(cause?.toString(), cause)
    }

class InvalidPluginException(message: String?) : Exception(message)

class InvalidPluginMethodException
    @JvmOverloads
    constructor(message: String?, cause: Throwable? = null) : Exception(message, cause) {
        constructor(cause: Throwable?) : this(cause?.toString(), cause)
    }

class PluginInvocationException
    @JvmOverloads
    constructor(message: String?, cause: Throwable? = null) : Exception(message, cause) {
        constructor(cause: Throwable?) : this(cause?.toString(), cause)
    }

class JSExportException
    @JvmOverloads
    constructor(message: String?, cause: Throwable? = null) : Exception(message, cause) {
        constructor(cause: Throwable?) : this(cause?.toString(), cause)
    }
