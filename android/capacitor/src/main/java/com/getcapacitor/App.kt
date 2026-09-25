package com.getcapacitor

public open class App {
    /**
     * Interface for callbacks when app status changes.
     */
    public fun interface AppStatusChangeListener {
        public fun onAppStatusChanged(isActive: Boolean?)
    }

    /**
     * Interface for callbacks when app is restored with pending plugin call.
     */
    public fun interface AppRestoredListener {
        public fun onAppRestored(result: PluginResult?)
    }

    private var statusChangeListener: AppStatusChangeListener? = null

    private var appRestoredListener: AppRestoredListener? = null

    public var isActive: Boolean = false
        private set

    /**
     * Set the object to receive callbacks.
     */
    public fun setStatusChangeListener(listener: AppStatusChangeListener?) {
        statusChangeListener = listener
    }

    /**
     * Set the object to receive callbacks.
     */
    public fun setAppRestoredListener(listener: AppRestoredListener?) {
        appRestoredListener = listener
    }

    // Internal: MessageHandler reports the results of restored calls through it; apps only listen for them.
    internal fun fireRestoredResult(result: PluginResult?) {
        appRestoredListener?.onAppRestored(result)
    }

    public fun fireStatusChange(isActive: Boolean) {
        this.isActive = isActive
        statusChangeListener?.onAppStatusChanged(isActive)
    }
}
