import Foundation

/// The calls of async plugin methods that are running, so that the bridge can cancel them.
///
/// Each call runs in its own `Task`. When it returns, the call is resolved: with the data the method returned, or
/// without data if it returned nothing and did not answer the call itself. What it throws rejects the call. A call the
/// method kept alive is handed back to the bridge to be saved, as for the other methods.
///
/// When the page that made the calls goes away, or the bridge is released, ``cancelAll()`` rejects the calls that
/// have not settled with "The plugin call was cancelled" and cancels their tasks. A method that does not check for
/// cancellation keeps running; what it returns, throws or sends later is dropped.
internal final class AsyncPluginCalls {
    private struct Running {
        let call: CAPPluginCall
        let task: Task<Void, Never>
    }

    private let lock = NSLock()
    private var running: [ObjectIdentifier: Running] = [:]

    /// The number of calls whose method has not returned yet.
    var count: Int {
        lock.withLock { running.count }
    }

    /// Runs `method` for `call` in a new task and settles the call with its outcome.
    ///
    /// - Parameters:
    ///   - call: The call the method answers.
    ///   - method: The plugin method, applied to its plugin and `call`. It returns the data to resolve the call with, or
    ///     nil if it returned nothing.
    ///   - keepAlive: Called with `call` when the method returned and kept the call alive.
    func start(_ call: CAPPluginCall, _ method: @escaping () async throws -> PluginCallResultData?,
               keepAlive: @escaping (CAPPluginCall) -> Void) {
        let key = ObjectIdentifier(call)
        // The task is created and stored under the lock, so it cannot finish before it is stored.
        lock.withLock {
            let task = Task {
                let outcome: Result<PluginCallResultData?, Error>
                do {
                    outcome = .success(try await method())
                } catch {
                    outcome = .failure(error)
                }
                guard self.finish(key) else {
                    CAPLog.print("⚡️  Dropping the result of \(call.pluginName ?? "Plugin").\(call.methodName) (callbackId \(call.callbackId)): the call was cancelled")
                    return
                }
                switch outcome {
                case .success(let data?):
                    call.resolve(data)
                case .success(nil):
                    call.resolveIfUnsettled()
                case .failure(let error):
                    call.reject(error)
                    return
                }
                if call.keepAlive {
                    keepAlive(call)
                }
            }
            running[key] = Running(call: call, task: task)
        }
    }

    /// Rejects every running call that has not settled and cancels its task.
    func cancelAll() {
        let cancelled: [Running] = lock.withLock {
            let cancelled = Array(running.values)
            running.removeAll()
            return cancelled
        }
        for entry in cancelled {
            entry.call.rejectIfUnsettled("The plugin call was cancelled")
            entry.task.cancel()
        }
    }

    /// Stops tracking the call. False when it was not tracked any more: ``cancelAll()`` answered it already.
    private func finish(_ key: ObjectIdentifier) -> Bool {
        lock.withLock { running.removeValue(forKey: key) != nil }
    }
}
