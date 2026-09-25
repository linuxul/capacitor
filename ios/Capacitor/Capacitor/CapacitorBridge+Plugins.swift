import Foundation

struct RegistrationList: Codable {
    /// The plugin classes the CLI found, in the order it listed them.
    let packageClassList: [String]
}

// MARK: - Plugins

extension CapacitorBridge {
    public func registerPluginType(_ pluginType: CAPPlugin.Type) {
        if autoRegisterPlugins { return }
        if pluginType is CAPInstancePlugin.Type {
            Swift.fatalError("""

            ⚡️ ❌  Cannot register class \(pluginType): CAPInstancePlugin through registerPluginType(_:).
            ⚡️ ❌  Use `registerPluginInstance(_:)` to register subclasses of CAPInstancePlugin.
            """)
        }
        guard let bridgedType = pluginType as? CapacitorPlugin.Type else { return }
        registerPlugin(bridgedType)
    }

    public func registerPluginInstance(_ pluginInstance: CAPPlugin) {
        guard let pluginInstance = pluginInstance as? CapacitorPlugin else {
            CAPLog.print("""

            ⚡️  Plugin \(pluginInstance.classForCoder) must conform to CAPBridgedPlugin.
            ⚡️  Not loading plugin \(pluginInstance.classForCoder)
            """)
            return
        }

        if pluginRegistry.register(pluginInstance) != nil {
            CAPLog.print("⚡️  Overriding existing registered plugin \(pluginInstance.classForCoder)")
        }
        pluginInstance.load(on: self)

        JSExport.exportJS(for: pluginInstance, in: webViewDelegationHandler.contentController)
    }

    /**
     Register a single plugin.
     */
    func registerPlugin(_ pluginType: CapacitorPlugin.Type) {
        if let plugin = loadPlugin(type: pluginType) {
            JSExport.exportJS(for: plugin, in: webViewDelegationHandler.contentController)
        }
    }

    func loadPlugin(type: CAPPlugin.Type) -> CapacitorPlugin? {
        guard let plugin = type.init() as? CapacitorPlugin else {
            CAPLog.print("⚡️  Unable to load plugin \(type.classForCoder()). No such module found.")
            return nil
        }
        plugin.load(on: self)
        pluginRegistry.register(plugin)
        return plugin
    }

    /**
     Load a plugin that the page calls by a name no registered plugin has.

     This keeps plugins working that are not listed for registration but whose Obj-C class name is their JavaScript
     name. Nothing else is instantiated: the class must be a bridged plugin the bridge creates itself (not a
     `CAPInstancePlugin`), a class that is registered already is never created a second time, and the new instance is
     kept only when its JavaScript name is the requested name. A page that called a plugin by its class identifier
     used to get a second instance registered under the plugin's JavaScript name, replacing the one in use.
     */
    func lazyLoadPlugin(named name: String) -> CapacitorPlugin? {
        guard let type = NSClassFromString(name) as? CapacitorPlugin.Type, !(type is CAPInstancePlugin.Type) else {
            return nil
        }
        guard !pluginRegistry.all.contains(where: { Swift.type(of: $0) == type }) else {
            CAPLog.print("⚡️  \(name) is a registered plugin class; call it by its JavaScript name")
            return nil
        }
        let plugin = type.init()
        guard plugin.jsName == name else {
            CAPLog.print("⚡️  Not loading \(name): its JavaScript name is \(plugin.jsName)")
            return nil
        }
        plugin.load(on: self)
        return pluginRegistry.registerIfAbsent(plugin)
    }

    // MARK: - CAPBridgeProtocol: Plugin Access

    public func plugin(withName: String) -> CAPPlugin? {
        return pluginRegistry[withName]
    }

    func removeAllPluginListeners() {
        for plugin in pluginRegistry.all {
            plugin.removeAllListeners()
        }
    }
}
