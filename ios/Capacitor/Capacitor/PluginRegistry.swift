import Foundation

/// The plugins registered with a bridge, by JavaScript name, in registration order.
///
/// Plugins are registered and looked up on the main thread, while the bridge queue walks them to remove listeners, so
/// every access goes through a lock. Plugin code never runs while the lock is held. Iteration returns a snapshot in
/// the order the names were first registered, which makes the plugin that answers `shouldOverrideLoad(_:)` or an
/// authentication challenge first the same on every launch.
internal final class PluginRegistry {
    private let lock = NSLock()
    private var plugins: [String: CapacitorPlugin] = [:]
    private var names: [String] = []

    subscript(jsName: String) -> CapacitorPlugin? {
        lock.withLock { plugins[jsName] }
    }

    /// Every registered plugin, in registration order.
    var all: [CapacitorPlugin] {
        lock.withLock { names.compactMap { plugins[$0] } }
    }

    /// Registers `plugin` under its JavaScript name and returns the plugin it replaced, if any. A replacement takes
    /// the position of the plugin it replaces.
    @discardableResult
    func register(_ plugin: CapacitorPlugin) -> CapacitorPlugin? {
        let name = plugin.jsName
        return lock.withLock {
            let replaced = plugins.updateValue(plugin, forKey: name)
            if replaced == nil {
                names.append(name)
            }
            return replaced
        }
    }
}
