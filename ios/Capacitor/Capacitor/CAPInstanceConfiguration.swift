import Foundation
import UIKit

/// The configuration of a bridge. It is an immutable value (``updatingAppLocation(_:)`` returns a copy), so it may be
/// read from any thread.
public struct InstanceConfiguration: @unchecked Sendable {
    public let appendedUserAgentString: String?
    public let overridenUserAgentString: String?
    public let backgroundColor: UIColor?
    public let allowedNavigationHostnames: [String]
    public let localURL: URL
    public let serverURL: URL
    public let errorPath: String?
    public let pluginConfigurations: JSObject
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
        // normalize() has already coerced this into a JSObject; the fallback is the same empty
        // config the old per-call cast produced
        pluginConfigurations = descriptor.pluginConfigurations as? JSObject ?? [:]
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
        // the [keyPath:] subscript is kept rather than a plain lookup so a dotted plugin id keeps traversing
        guard let cfg = pluginConfigurations[keyPath: KeyPath(pluginId)] as? JSObject else {
            return PluginConfig(config: JSObject())
        }
        return PluginConfig(config: cfg)
    }

    public func shouldAllowNavigation(to host: String) -> Bool {
        return allowedNavigationHostnames.contains { doesHost(host, match: $0) }
    }

    // MARK: - Private

    private func doesHost(_ host: String, match pattern: String) -> Bool {
        // bail early in the simple case
        if pattern == "*" {
            return true
        }
        // break apart the pieces
        let hostComponents = host.lowercased().split(separator: ".")
        let patternComponents = pattern.lowercased().split(separator: ".")
        guard hostComponents.count == patternComponents.count else {
            return false
        }
        // every segment has to match, except where the pattern wildcards it
        return zip(hostComponents, patternComponents).allSatisfy { hostSegment, patternSegment in
            patternSegment == "*" || hostSegment == patternSegment
        }
    }
}
