import Foundation

/// The configuration of one plugin: the object under `plugins.<jsName>` in `capacitor.config`, as ``CAPPlugin/getConfig()``
/// returns it.
///
/// Keys may be key paths such as `"ios.style"`. Read single values with the getters, or decode the whole object into a
/// `Decodable` type with ``decode(_:decoder:)``:
///
/// ```swift
/// struct SplashConfig: Decodable {
///     var launchShowDuration: Int?
///     var backgroundColor: String?
/// }
///
/// let config = try getConfig().decode(SplashConfig.self)
/// let duration = config.launchShowDuration ?? 500
/// ```
public struct PluginConfig {

    // The object containing the plugin config values
    private let config: JSObject

    init(config: JSObject) {
        self.config = config
    }

    public func getString(_ configKey: String, _ defaultValue: String? = nil) -> String? {
        if let val = (self.config)[keyPath: KeyPath(configKey)] as? String {
            return val
        }
        return defaultValue
    }

    public func getBoolean(_ configKey: String, _ defaultValue: Bool) -> Bool {
        if let val = (self.config)[keyPath: KeyPath(configKey)] as? Bool {
            return val
        }
        return defaultValue
    }

    public func getInt(_ configKey: String, _ defaultValue: Int) -> Int {
        if let val = (self.config)[keyPath: KeyPath(configKey)] as? Int {
            return val
        }
        return defaultValue
    }

    public func getDouble(_ configKey: String, _ defaultValue: Double) -> Double {
        if let val = (self.config)[keyPath: KeyPath(configKey)] as? Double {
            return val
        }
        return defaultValue
    }

    public func getArray(_ configKey: String, _ defaultValue: JSArray? = nil) -> JSArray? {
        if let val = (self.config)[keyPath: KeyPath(configKey)] as? JSArray {
            return val
        }
        return defaultValue
    }

    public func getObject(_ configKey: String) -> JSObject? {
        return (self.config)[keyPath: KeyPath(configKey)] as? JSObject
    }

    public func isEmpty() -> Bool {
        return self.config.isEmpty
    }

    /**
     * Gets the JSObject containing the config of the the provided plugin ID.
     *
     * @return The config for that plugin
     */
    public func getConfigJSON() -> JSObject {
        return self.config
    }

    /// Decodes the whole configuration into `type`.
    ///
    /// - Parameters:
    ///   - type: The type to decode. Keys missing from the configuration need optional properties: a synthesized
    ///     `Decodable` conformance requires the key of every other property, even one with a default value.
    ///   - decoder: The decoder to use. Defaults to `JSValueDecoder()`.
    /// - Throws: `DecodingError` when the configuration does not match `type`.
    public func decode<T: Decodable>(_ type: T.Type = T.self, decoder: JSValueDecoder = JSValueDecoder()) throws -> T {
        try decoder.decode(type, from: config)
    }
}
