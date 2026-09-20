import Foundation
import UIKit

@objc(CAPInstanceConfiguration)
public final class InstanceConfiguration: NSObject {
    @objc public let appendedUserAgentString: String?
    @objc public let overridenUserAgentString: String?
    @objc public let backgroundColor: UIColor?
    @objc public let allowedNavigationHostnames: [String]
    @objc public let localURL: URL
    @objc public let serverURL: URL
    @objc public let errorPath: String?
    @objc public let pluginConfigurations: [AnyHashable: Any]
    @objc public let loggingEnabled: Bool
    @objc public let scrollingEnabled: Bool
    @objc public let zoomingEnabled: Bool
    @objc public let allowLinkPreviews: Bool
    @objc public let handleApplicationNotifications: Bool
    @objc public let isWebDebuggable: Bool
    @objc public let hasInitialFocus: Bool
    @objc public let contentInsetAdjustmentBehavior: UIScrollView.ContentInsetAdjustmentBehavior
    @objc public let appLocation: URL
    @objc public let appStartPath: String?
    @objc public let limitsNavigationsToAppBoundDomains: Bool
    @objc public let preferredContentMode: String?

    @available(*, deprecated, message: "Use direct properties instead")
    @objc public var legacyConfig: [AnyHashable: Any] {
        return storedLegacyConfig
    }

    // backing storage so that the framework itself does not have to touch a deprecated API
    private let storedLegacyConfig: [AnyHashable: Any]

    public init(with descriptor: InstanceDescriptor, isDebug debug: Bool) {
        // first, give the descriptor a chance to make itself internally consistent
        descriptor.normalize()
        // now copy the simple properties
        appendedUserAgentString = descriptor.appendedUserAgentString
        overridenUserAgentString = descriptor.overridenUserAgentString
        backgroundColor = descriptor.backgroundColor
        allowedNavigationHostnames = descriptor.allowedNavigationHostnames
        switch descriptor.loggingBehavior {
        case .production:
            loggingEnabled = true
        case .debug:
            loggingEnabled = debug
        case .none:
            loggingEnabled = false
        }
        scrollingEnabled = descriptor.scrollingEnabled
        zoomingEnabled = descriptor.zoomingEnabled
        allowLinkPreviews = descriptor.allowLinkPreviews
        handleApplicationNotifications = descriptor.handleApplicationNotifications
        contentInsetAdjustmentBehavior = descriptor.contentInsetAdjustmentBehavior
        appLocation = descriptor.appLocation
        appStartPath = descriptor.appStartPath
        limitsNavigationsToAppBoundDomains = descriptor.limitsNavigationsToAppBoundDomains
        preferredContentMode = descriptor.preferredContentMode
        pluginConfigurations = descriptor.pluginConfigurations
        isWebDebuggable = descriptor.isWebDebuggable
        hasInitialFocus = descriptor.hasInitialFocus
        storedLegacyConfig = descriptor.legacyConfig
        // construct the necessary URLs
        let scheme = descriptor.urlScheme ?? InstanceDescriptorDefaults.scheme
        let hostname = descriptor.urlHostname ?? InstanceDescriptorDefaults.hostname
        let local = URL(string: "\(scheme)://\(hostname)") ?? InstanceConfiguration.defaultLocalURL
        localURL = local
        if let serverString = descriptor.serverURL, let server = URL(string: serverString) {
            serverURL = server
        } else {
            serverURL = local
        }
        errorPath = descriptor.errorPath
        super.init()
    }

    private init(with configuration: InstanceConfiguration, location: URL) {
        appendedUserAgentString = configuration.appendedUserAgentString
        overridenUserAgentString = configuration.overridenUserAgentString
        backgroundColor = configuration.backgroundColor
        allowedNavigationHostnames = configuration.allowedNavigationHostnames
        localURL = configuration.localURL
        serverURL = configuration.serverURL
        errorPath = configuration.errorPath
        pluginConfigurations = configuration.pluginConfigurations
        loggingEnabled = configuration.loggingEnabled
        scrollingEnabled = configuration.scrollingEnabled
        zoomingEnabled = configuration.zoomingEnabled
        allowLinkPreviews = configuration.allowLinkPreviews
        handleApplicationNotifications = configuration.handleApplicationNotifications
        isWebDebuggable = configuration.isWebDebuggable
        hasInitialFocus = configuration.hasInitialFocus
        contentInsetAdjustmentBehavior = configuration.contentInsetAdjustmentBehavior
        limitsNavigationsToAppBoundDomains = configuration.limitsNavigationsToAppBoundDomains
        preferredContentMode = configuration.preferredContentMode
        storedLegacyConfig = configuration.storedLegacyConfig
        appStartPath = configuration.appStartPath
        appLocation = location
        super.init()
    }

    @objc public func updatingAppLocation(_ location: URL) -> InstanceConfiguration {
        return InstanceConfiguration(with: self, location: location)
    }

    // swiftlint:disable:next force_unwrapping
    private static let defaultLocalURL = URL(string: "\(InstanceDescriptorDefaults.scheme)://\(InstanceDescriptorDefaults.hostname)")!
}

extension InstanceConfiguration {
    @objc public var appStartFileURL: URL {
        if let path = appStartPath {
            return appLocation.appendingPathComponent(path)
        }
        return appLocation
    }

    @objc public var appStartServerURL: URL {
        if let path = appStartPath {
            return serverURL.appendingPathComponent(path)
        }
        return serverURL
    }

    @objc public var errorPathURL: URL? {
        guard let errorPath = errorPath else {
            return nil
        }

        return localURL.appendingPathComponent(errorPath)
    }

    @available(*, deprecated, message: "Use getPluginConfig")
    @objc public func getPluginConfigValue(_ pluginId: String, _ configKey: String) -> Any? {
        return (pluginConfigurations as? JSObject)?[keyPath: KeyPath("\(pluginId).\(configKey)")]
    }

    @objc public func getPluginConfig(_ pluginId: String) -> PluginConfig {
        if let cfg = (pluginConfigurations as? JSObject)?[keyPath: KeyPath("\(pluginId)")] as? JSObject {
            return PluginConfig(config: cfg)
        }
        return PluginConfig(config: JSObject())
    }

    @objc public func shouldAllowNavigation(to host: String) -> Bool {
        for hostname in allowedNavigationHostnames {
            if doesHost(host, match: hostname) {
                return true
            }
        }
        return false
    }

    @available(*, deprecated, message: "Use direct property accessors")
    @objc public func getValue(_ key: String) -> Any? {
        return (storedLegacyConfig as? JSObject)?[keyPath: KeyPath(key)]
    }

    @available(*, deprecated, message: "Use direct property accessors")
    @objc public func getString(_ key: String) -> String? {
        return (storedLegacyConfig as? JSObject)?[keyPath: KeyPath(key)] as? String
    }

    // MARK: - Private

    private func doesHost(_ host: String, match pattern: String) -> Bool {
        // bail early in the simple case
        if pattern == "*" {
            return true
        }
        // break apart the pieces
        var hostComponents = host.lowercased().split(separator: ".")
        var patternComponents = pattern.lowercased().split(separator: ".")
        guard hostComponents.count == patternComponents.count else {
            return false
        }
        // remove any wildcard segments
        for wildcard in patternComponents.enumerated().reversed().filter({ $0.element == "*" }) {
            hostComponents.remove(at: wildcard.offset)
            patternComponents.remove(at: wildcard.offset)
        }
        // match with what's left
        return hostComponents == patternComponents
    }
}
