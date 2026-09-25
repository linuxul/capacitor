package com.getcapacitor

import java.lang.reflect.InvocationTargetException

/**
 * Posts a task to a thread. False when the thread takes no more work, as [android.os.Handler.post] reports it.
 */
internal fun interface TaskPoster {
    fun post(task: Runnable): Boolean
}

/**
 * Runs the plugin methods the [Bridge] is asked to call, and answers the calls whose method fails.
 *
 * @param pluginThread where plugin methods run: the bridge's plugin HandlerThread
 * @param saveCall keeps a call the method kept alive, so it can be found by its callback id later
 */
internal class PluginCallDispatcher(private val pluginThread: TaskPoster, private val saveCall: (PluginCall) -> Unit) {
    fun dispatch(plugin: PluginHandle, methodName: String?, call: PluginCall) {
        val task =
            Runnable {
                try {
                    plugin.invoke(methodName, call)

                    if (call.keepAlive) {
                        saveCall(call)
                    }
                } catch (ex: Exception) {
                    rejectFailedCall(plugin, methodName, call, ex)
                }
            }

        if (!pluginThread.post(task)) {
            call.reject("Plugin thread is unavailable", code = "UNAVAILABLE")
        }
    }

    /**
     * Rejects [call] for the [failure] of its method. The bridge's own failures (loading the plugin, finding the
     * method) are thrown directly; what the method throws arrives wrapped in an [InvocationTargetException].
     */
    private fun rejectFailedCall(plugin: PluginHandle, methodName: String?, call: PluginCall, failure: Throwable) {
        val cause = (failure as? InvocationTargetException)?.targetException ?: failure

        when {
            failure is PluginLoadException -> {
                Logger.error("Unable to execute plugin method", failure)
                call.reject("Unable to load plugin ${plugin.id}", code = "UNAVAILABLE")
            }

            failure is InvalidPluginMethodException -> {
                Logger.error("Unable to execute plugin method", failure)
                call.reject(failure.message ?: "No method $methodName found for plugin ${plugin.id}", code = "UNIMPLEMENTED")
            }

            cause is PluginException -> call.rejectWith(cause)

            else -> {
                Logger.error("Serious error executing plugin", cause)
                call.reject("Error executing plugin method $methodName", ex = cause as? Exception ?: failure as? Exception)
            }
        }
    }
}
