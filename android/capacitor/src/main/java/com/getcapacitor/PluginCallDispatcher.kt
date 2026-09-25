package com.getcapacitor

import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.startCoroutine

/**
 * Posts a task to a thread. False when the thread takes no more work, as [android.os.Handler.post] reports it.
 */
internal fun interface TaskPoster {
    fun post(task: Runnable): Boolean
}

/**
 * Runs the plugin methods the [Bridge] is asked to call, and answers the calls whose method fails.
 *
 * A suspend method is started as a coroutine that runs, and resumes after each suspension, on the thread its
 * `@PluginMethod` names. When it returns, its call is resolved: with the [JSObject] it returned, or without data
 * if it returned Unit and did not answer the call itself. Whatever it throws rejects the call.
 *
 * @param pluginThread where plugin methods run by default: the bridge's plugin HandlerThread
 * @param mainThread where methods declared with `@PluginMethod(thread = PluginThread.MAIN)` run
 * @param saveCall keeps a call the method kept alive, so it can be found by its callback id later
 */
internal class PluginCallDispatcher(
    private val pluginThread: TaskPoster,
    private val mainThread: TaskPoster,
    private val saveCall: (PluginCall) -> Unit
) {
    // The calls of suspend methods that have not returned yet, so that cancelRunningCalls can answer them.
    private val runningCalls = HashSet<PluginCall>()

    fun dispatch(plugin: PluginHandle, methodName: String, call: PluginCall) {
        // A method that does not exist is reported from the plugin thread, where invoke throws for it.
        val method = plugin.findMethod(methodName)
        val onMainThread = method?.thread == PluginThread.MAIN
        val thread = if (onMainThread) mainThread else pluginThread

        if (method != null && method.isSuspend) {
            startSuspending(plugin, method, call, thread) { rejectUnavailable(call, onMainThread) }
            return
        }

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

        if (!thread.post(task)) {
            rejectUnavailable(call, onMainThread)
        }
    }

    /**
     * Rejects the calls of suspend methods that are still running, for when the page they came from is gone. The
     * methods themselves keep running; what they return or throw later is dropped.
     */
    fun cancelRunningCalls() {
        val cancelled =
            synchronized(runningCalls) {
                runningCalls.toList().also { runningCalls.clear() }
            }

        for (call in cancelled) {
            // A method may answer its call before it returns; that answer stands.
            if (!call.isSettled) {
                call.reject("The plugin call was cancelled")
            }
        }
    }

    private fun startSuspending(
        plugin: PluginHandle,
        method: PluginMethodHandle,
        call: PluginCall,
        thread: TaskPoster,
        onUnavailable: () -> Unit
    ) {
        synchronized(runningCalls) { runningCalls.add(call) }

        val completion =
            object : Continuation<Any?> {
                override val context: CoroutineContext =
                    PostingInterceptor(thread) {
                        if (finishRunning(call)) onUnavailable()
                    }

                override fun resumeWith(result: Result<Any?>) {
                    finishSuspending(plugin, method, call, result)
                }
            }

        val body: suspend () -> Any? = {
            // Can throw PluginLoadException, which is reported like the bridge's other failures.
            val instance = plugin.load()
            // The method returns COROUTINE_SUSPENDED if it suspended, or its result if it returned right away.
            suspendCoroutineUninterceptedOrReturn { continuation -> method.method.invoke(instance, call, continuation) }
        }

        // The interceptor posts the start, like any resumption, to the method's thread.
        body.startCoroutine(completion)
    }

    /**
     * Removes [call] from the running calls. False when it was not there: cancelRunningCalls answered it already.
     */
    private fun finishRunning(call: PluginCall): Boolean = synchronized(runningCalls) { runningCalls.remove(call) }

    private fun finishSuspending(plugin: PluginHandle, method: PluginMethodHandle, call: PluginCall, result: Result<Any?>) {
        if (!finishRunning(call)) {
            Logger.debug(Logger.tags("Plugin"), "Dropping the result of ${plugin.id}.${method.name}: the call was cancelled")
            return
        }

        val failure = result.exceptionOrNull()
        if (failure != null) {
            rejectFailedCall(plugin, method.name, call, failure)
            return
        }

        // JavaScript expects no answer from a RETURN_NONE method; its "-1" callback id is for restored calls.
        if (method.returnType != PluginMethod.RETURN_NONE) {
            when (val value = result.getOrNull()) {
                null, Unit -> call.resolveIfUnsettled()

                is JSObject -> call.resolve(value)

                else -> {
                    val message =
                        "Plugin method ${plugin.id}.${method.name} returned a ${value.javaClass.name}; " +
                            "a suspend @PluginMethod must return Unit or a JSObject"
                    Logger.error(message)
                    call.reject(message)
                }
            }
        }

        if (call.keepAlive) {
            saveCall(call)
        }
    }

    private fun rejectUnavailable(call: PluginCall, onMainThread: Boolean) {
        call.reject(if (onMainThread) "Main thread is unavailable" else "Plugin thread is unavailable", code = "UNAVAILABLE")
    }

    /**
     * Rejects [call] for the [failure] of its method. The bridge's own failures (loading the plugin, finding the
     * method) are thrown directly; what the method throws arrives wrapped in an [InvocationTargetException] when
     * it throws before it first suspends.
     */
    private fun rejectFailedCall(plugin: PluginHandle, methodName: String, call: PluginCall, failure: Throwable) {
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

/**
 * Resumes coroutines on [thread]. Every resumption is posted, even one that happens on that thread already, so a
 * resumed method runs after the calls posted before it, in order. [onUnavailable] runs when the thread takes no
 * more work; the coroutine is then never resumed.
 */
private class PostingInterceptor(private val thread: TaskPoster, private val onUnavailable: () -> Unit) :
    AbstractCoroutineContextElement(ContinuationInterceptor),
    ContinuationInterceptor {
    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> = object : Continuation<T> {
        override val context: CoroutineContext
            get() = continuation.context

        override fun resumeWith(result: Result<T>) {
            if (!thread.post { continuation.resumeWith(result) }) {
                onUnavailable()
            }
        }
    }
}
