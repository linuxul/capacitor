package com.getcapacitor

/**
 * The thread a `@PluginMethod` runs on, chosen with `@PluginMethod(thread = ...)`.
 */
public enum class PluginThread {
    /**
     * The bridge's plugin thread, a single background thread that runs the methods of every plugin in the order
     * they were called. The default.
     */
    PLUGIN,

    /**
     * The main (UI) thread, for methods that work with views, windows or dialogs. They need not post to the main
     * thread themselves, and what they throw still rejects the call instead of crashing the app. Keep them short:
     * they block the UI while they run.
     */
    MAIN
}
