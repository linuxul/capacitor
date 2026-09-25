import Foundation

// MARK: - Calling Plugins

extension CapacitorBridge {
    /**
     Handle a call from JavaScript: find the plugin and the method it registered under that name, and call the method
     on the bridge queue. Methods registered by reference are called directly; methods registered by selector, and the
     listener methods every plugin inherits, are performed through the Obj-C runtime.
     */
    func handleJSCall(call: JSCall) {
        guard let plugin = pluginRegistry[call.pluginId] ?? lazyLoadPlugin(named: call.pluginId) else {
            rejectJSCall(call, message: "Error loading plugin \(call.pluginId) for call. Check that the pluginId is correct")
            return
        }

        let invocation: CAPPluginMethod.Invocation
        if call.method == "addListener" || call.method == "removeListener" || call.method == "removeAllListeners" {
            invocation = .selector(NSSelectorFromString(call.method + ":"))
        } else {
            guard let method = plugin.getMethod(named: call.method) else {
                CAPLog.print("⚡️  Ensure the method is listed in the pluginMethods of the plugin")
                rejectJSCall(call, message: "Error calling method \(call.method) on plugin \(call.pluginId): No method found.")
                return
            }

            invocation = method.invocation
        }

        if case .selector(let selector) = invocation, !plugin.responds(to: selector) {
            CAPLog.print("⚡️  Ensure plugin method exists, uses @objc in its declaration, and is listed in the pluginMethods of the plugin.")
            CAPLog.print("⚡️  Learn more: \(docLink(DocLinks.CAPPluginMethodSelector.rawValue))")
            rejectJSCall(call, message: "Plugin \(plugin.getId()) does not respond to method \(call.method) using selector \(selector).")
            return
        }

        dispatchQueue.async { [weak self] in
            guard let self else {
                return
            }
            self.invoke(invocation, on: plugin, with: self.makePluginCall(for: call, formattingDatesAsStrings: plugin.shouldStringifyDatesInCalls))
        }
    }

    /// Creates the call object for `call`, whose results are sent to the page.
    private func makePluginCall(for call: JSCall, formattingDatesAsStrings: Bool) -> CAPPluginCall {
        // The error handler has no call parameter, but needs keepAlive to tell the page whether to keep its callback.
        // The call is alive whenever its handler runs, so a weak reference is enough.
        weak var weakPluginCall: CAPPluginCall?
        let options = JSTypes.coerceDictionaryToJSObject(call.options, formattingDatesAsStrings: formattingDatesAsStrings) ?? [:]
        let pluginCall = CAPPluginCall(callbackId: call.callbackId, methodName: call.method, options: options,
                                       success: { [weak self] (result: CAPPluginCallResult, pluginCall: CAPPluginCall) in
                                        self?.toJs(result: JSResult(call: call, callResult: result), save: pluginCall.keepAlive)
                                       }, error: { [weak self] (error: CAPPluginCallError) in
                                        let save = weakPluginCall?.keepAlive ?? false
                                        self?.toJsError(error: JSResultError(call: call, callError: error), save: save)
                                       })
        weakPluginCall = pluginCall
        pluginCall.pluginName = call.pluginId
        return pluginCall
    }

    /// Calls a plugin method on the bridge queue, and saves its call when the method returned and kept the call alive.
    private func invoke(_ invocation: CAPPluginMethod.Invocation, on plugin: CapacitorPlugin, with pluginCall: CAPPluginCall) {
        switch invocation {
        case .selector(let selector):
            plugin.perform(selector, with: pluginCall)
        case .function(let function):
            do {
                try function(plugin, pluginCall)
            } catch {
                // like on Android, a method that throws does not keep its call
                pluginCall.reject(error)
                return
            }
        case .async(let function):
            asyncCalls.start(pluginCall, { try await function(plugin, pluginCall) }, keepAlive: { [weak self] call in
                self?.saveCall(call)
            })
            return
        }
        if pluginCall.keepAlive {
            saveCall(pluginCall)
        }
    }

    private func rejectJSCall(_ call: JSCall, message: String) {
        CAPLog.print("⚡️  \(message)")
        let error = CAPPluginCallError(message: message, code: "UNIMPLEMENTED", error: nil, data: nil)
        toJsError(error: JSResultError(call: call, callError: error), save: false)
    }

    func docLink(_ url: String) -> String {
        return "\(type(of: self).capacitorSite)docs/\(url)"
    }

    /**
     Reset the state of the bridge between navigations to avoid
     sending data back to the page from a previous page.

     Async plugin methods that are still running are cancelled, and their calls rejected, as on Android.
     */
    func reset() {
        asyncCalls.cancelAll()
        storedCalls.withLock { $0.removeAll() }
        removeAllPluginListeners()
    }

    // MARK: - CAPBridgeProtocol: Call Management

    public func saveCall(_ call: CAPPluginCall) {
        storedCalls[call.callbackId] = call
    }

    public func savedCall(withID: String) -> CAPPluginCall? {
        return storedCalls[withID]
    }

    public func releaseCall(_ call: CAPPluginCall) {
        releaseCall(withID: call.callbackId)
    }

    public func releaseCall(withID: String) {
        _ = storedCalls.withLock { $0.removeValue(forKey: withID) }
    }
}
