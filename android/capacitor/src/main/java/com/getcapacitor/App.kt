package com.getcapacitor

open class App {
    /**
     * Interface for callbacks when app status changes.
     */
    fun interface AppStatusChangeListener {
        fun onAppStatusChanged(isActive: Boolean?)
    }

    /**
     * Interface for callbacks when app is restored with pending plugin call.
     */
    fun interface AppRestoredListener {
        fun onAppRestored(result: PluginResult?)
    }

    private var statusChangeListener: AppStatusChangeListener? = null

    private var appRestoredListener: AppRestoredListener? = null

    var isActive: Boolean = false
        private set

    /**
     * Set the object to receive callbacks.
     */
    fun setStatusChangeListener(listener: AppStatusChangeListener?) {
        statusChangeListener = listener
    }

    /**
     * Set the object to receive callbacks.
     */
    fun setAppRestoredListener(listener: AppRestoredListener?) {
        appRestoredListener = listener
    }

    // Was protected in Java, where the same-package MessageHandler could still call it; Kotlin's protected
    // does not reach package neighbours.
    internal fun fireRestoredResult(result: PluginResult?) {
        appRestoredListener?.onAppRestored(result)
    }

    fun fireStatusChange(isActive: Boolean) {
        this.isActive = isActive
        statusChangeListener?.onAppStatusChanged(isActive)
    }
}
