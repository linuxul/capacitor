import Foundation
import UIKit

public struct InstanceConfiguration {
    public let appendedUserAgentString: String?
    public let overridenUserAgentString: String?
    public let backgroundColor: UIColor?
    public let allowedNavigationHostnames: [String]
    public let localURL: URL
    public let serverURL: URL
    public let errorPath: String?
    public let pluginConfigurations: [AnyHashable: Any]
    public let loggingEnabled: Bool
    public let scrollingEnabled: Bool
    public let zoomingEnabled: Bool
    public let allowLinkPreviews: Bool
    public let handleApplicationNotifications: Bool
    public let isWebDebuggable: Bool
    public let hasInitialFocus: Bool
    public let contentInsetAdjustmentBehavior: UIScrollView.ContentInsetAdjustmentBehavior
    public private(set) var appLocation: URL
    public let appStartPath: String?
    public let limitsNavigationsToAppBoundDomains: Bool
    public let preferredContentMode: String?

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
    }

    public func updatingAppLocation(_ location: URL) -> InstanceConfiguration {
        var copy = self
        copy.appLocation = location
        return copy
    }

    // swiftlint:disable:next force_unwrapping
    private static let defaultLocalURL = URL(string: "\(InstanceDescriptorDefaults.scheme)://\(InstanceDescriptorDefaults.hostname)")!
}

extension InstanceConfiguration {
    public var appStartFileURL: URL {
        if let path = appStartPath {
            return appLocation.appendingPathComponent(path)
        }
        return appLocation
    }

    public var appStartServerURL: URL {
        if let path = appStartPath {
            return serverURL.appendingPathComponent(path)
        }
        return serverURL
    }

    public var errorPathURL: URL? {
        guard let errorPath = errorPath else {
            return nil
        }

        return localURL.appendingPathComponent(errorPath)
    }

    public func getPluginConfig(_ pluginId: String) -> PluginConfig {
        if let cfg = (pluginConfigurations as? JSObject)?[keyPath: KeyPath("\(pluginId)")] as? JSObject {
            return PluginConfig(config: cfg)
        }
        return PluginConfig(config: JSObject())
    }

    public func shouldAllowNavigation(to host: String) -> Bool {
        for hostname in allowedNavigationHostnames {
            if doesHost(host, match: hostname) {
                return true
            }
        }
        return false
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
