import Foundation
import UIKit
import WebKit

public enum InstanceDescriptorDefaults {
    public static let scheme = "capacitor"
    public static let hostname = "localhost"
}

/// The type of a Capacitor instance.
public enum InstanceType {
    /// The default environment: built by the Capacitor CLI with the web app located in the application bundle.
    case fixed
    /// An environment whose app location and configuration are supplied by the caller.
    case variable
}

/// Warnings generated while initializing an ``InstanceDescriptor``.
public struct InstanceWarning: OptionSet {
    public let rawValue: UInt

    public init(rawValue: UInt) {
        self.rawValue = rawValue
    }

    public static let missingAppDir = InstanceWarning(rawValue: 1 << 0)
    public static let missingFile = InstanceWarning(rawValue: 1 << 1)
    public static let invalidFile = InstanceWarning(rawValue: 1 << 2)
}

/// The build configurations under which logging should be enabled. The values are mutually exclusive.
public enum InstanceLoggingBehavior {
    case none
    case debug
    case production
}

public final class InstanceDescriptor {
    /// A value to append to the `User-Agent` string. Ignored if `overridenUserAgentString` is set.
    ///
    /// Set by `appendUserAgent` in the configuration file.
    public var appendedUserAgentString: String?
    /// A value that will completely replace the `User-Agent` string. Overrides `appendedUserAgentString`.
    ///
    /// Set by `overrideUserAgent` in the configuration file.
    public var overridenUserAgentString: String?
    /// The background color to set on the web view where content is not visible.
    ///
    /// Set by `backgroundColor` in the configuration file.
    public var backgroundColor: UIColor?
    /// Hostnames to which the web view is allowed to navigate without opening an external browser.
    ///
    /// Set by `allowNavigation` in the configuration file.
    public var allowedNavigationHostnames: [String] = []
    /// The scheme that will be used for the server URL.
    ///
    /// Defaults to `capacitor`. Set by `server.iosScheme` in the configuration file.
    public var urlScheme: String? = InstanceDescriptorDefaults.scheme
    /// The path to a local html page to display in case of errors.
    ///
    /// Defaults to nil.
    public var errorPath: String?
    /// The hostname that will be used for the server URL.
    ///
    /// Defaults to `localhost`. Set by `server.hostname` in the configuration file.
    public var urlHostname: String? = InstanceDescriptorDefaults.hostname
    /// The fully formed URL that will be used as the server URL.
    ///
    /// Defaults to nil, in which case the server URL will be constructed from `urlScheme` and `urlHostname`.
    /// If set, it will override the other properties. Set by `server.url` in the configuration file.
    public var serverURL: String?
    /// The JSON dictionary that contains the plugin-specific configuration information.
    ///
    /// Set by `plugins` in the configuration file.
    public var pluginConfigurations: [AnyHashable: Any] = [:]
    /// The build configurations under which logging should be enabled.
    ///
    /// Defaults to `debug`. Set by `loggingBehavior` in the configuration file.
    public var loggingBehavior: InstanceLoggingBehavior = .debug
    /// Whether or not the web view can scroll.
    ///
    /// Set by `ios.scrollEnabled` in the configuration file. Corresponds to `isScrollEnabled` on WKWebView.
    public var scrollingEnabled: Bool = true
    /// Whether or not the web view can zoom.
    ///
    /// Set by `zoomEnabled` in the configuration file.
    public var zoomingEnabled: Bool = false
    /// Whether or not the web view will preview links.
    ///
    /// Set by `ios.allowsLinkPreview` in the configuration file. Corresponds to `allowsLinkPreview` on WKWebView.
    public var allowLinkPreviews: Bool = true
    /// Whether or not the Capacitor runtime will set itself as the `UNUserNotificationCenter` delegate.
    ///
    /// Defaults to `true`. Required to be `true` for notification plugins to work correctly.
    /// Set to `false` if your application will handle notifications independently.
    public var handleApplicationNotifications: Bool = true
    /// Enables web debugging by setting `isInspectable` of `WKWebView` to `true`.
    ///
    /// Defaults to true in debug mode and false in production.
    public var isWebDebuggable: Bool = false
    /// Whether or not the webview will have focus.
    ///
    /// Defaults to `true`. Set by `ios.initialFocus` in the configuration file.
    public var hasInitialFocus: Bool = true
    /// How the web view will inset its content.
    ///
    /// Set by `ios.contentInset` in the configuration file. Corresponds to `contentInsetAdjustmentBehavior` on WKWebView.
    public var contentInsetAdjustmentBehavior: UIScrollView.ContentInsetAdjustmentBehavior = .never
    /// The base file URL from which Capacitor will load resources.
    ///
    /// Defaults to `public/` located at the root of the application bundle.
    public var appLocation: URL
    /// The path (relative to `appLocation`) which Capacitor will use for the inital URL at launch.
    ///
    /// Defaults to nil, in which case Capacitor will attempt to load `index.html`.
    public var appStartPath: String?
    /// Whether or not the Capacitor WebView will limit the navigation to `WKAppBoundDomains` listed in the Info.plist.
    ///
    /// Defaults to `false`. Set by `ios.limitsNavigationsToAppBoundDomains` in the configuration file.
    /// Required to be `true` for plugins to work if the app includes `WKAppBoundDomains` in the Info.plist.
    public var limitsNavigationsToAppBoundDomains: Bool = false
    /// The content mode for the web view to use when it loads and renders web content.
    ///
    /// Defaults to `recommended`. Set by `ios.preferredContentMode` in the configuration file.
    public var preferredContentMode: String?
    /// Warnings generated during initialization.
    public var warnings: InstanceWarning = []
    /// The type of instance.
    public let instanceType: InstanceType
    /// The JSON dictionary representing the contents of the configuration file.
    /// - Warning: Deprecated. Do not use.
    public var legacyConfig: [AnyHashable: Any] = [:]

    /// Initialize the descriptor with the default environment. This assumes that the application was built with the help of
    /// the Capacitor CLI and that that the web app is located inside the application bundle at `public/`.
    public init() {
        instanceType = .fixed
        appLocation = Self.defaultAppLocation
        setAppLocation(Bundle.main.url(forResource: "public", withExtension: nil))
        _parseConfiguration(at: Bundle.main.url(forResource: "capacitor.config", withExtension: "json"))
    }

    /// Initialize the descriptor for use in other contexts. The app location is the one required parameter.
    /// - Parameters:
    ///   - appURL: The location of the folder containing the web app.
    ///   - configURL: The location of the Capacitor configuration file.
    public init(at appURL: URL, configuration configURL: URL?) {
        instanceType = .variable
        appLocation = appURL
        _parseConfiguration(at: configURL)
    }

    private static var defaultAppLocation: URL {
        return (Bundle.main.resourceURL ?? Bundle.main.bundleURL).appendingPathComponent("public")
    }

    private func setAppLocation(_ location: URL?) {
        if let location = location {
            appLocation = location
        } else {
            warnings.update(with: .missingAppDir)
            // location is nil so assume it was supposed to be the default
            appLocation = Self.defaultAppLocation
        }
    }
}

private extension InstanceLoggingBehavior {
    static func behavior(from: String) -> InstanceLoggingBehavior? {
        switch from.lowercased() {
        case "none":
            return InstanceLoggingBehavior.none
        case "debug":
            return InstanceLoggingBehavior.debug
        case "production":
            return InstanceLoggingBehavior.production
        default:
            return nil
        }
    }
}

/**
 The purpose of this function is to hide the messy details of parsing the configuration(s) so
 the complexity is worth it.
 */
internal extension InstanceDescriptor {
    // swiftlint:disable cyclomatic_complexity
    // swiftlint:disable function_body_length
    // swiftlint:disable:next identifier_name
    func _parseConfiguration(at capacitorURL: URL?) {
        // sanity check that the app directory is valid
        var isDirectory: ObjCBool = ObjCBool(false)
        if warnings.contains(.missingAppDir) == false,
           FileManager.default.fileExists(atPath: appLocation.path, isDirectory: &isDirectory) == false || isDirectory.boolValue == false {
            warnings.update(with: .missingAppDir)
        }

        // parse the capacitor configuration
        var config: JSObject?
        if let capacitorURL = capacitorURL,
           FileManager.default.fileExists(atPath: capacitorURL.path, isDirectory: &isDirectory),
           isDirectory.boolValue == false {
            do {
                let contents = try Data(contentsOf: capacitorURL)
                config = JSTypes.coerceDictionaryToJSObject(try JSONSerialization.jsonObject(with: contents) as? [String: Any])
            } catch {
                warnings.update(with: .invalidFile)
            }
        } else {
            warnings.update(with: .missingFile)
        }

        // extract our configuration values
        if let config = config {
            // to be removed
            legacyConfig = config

            if let agentString = (config[keyPath: "ios.appendUserAgent"] as? String) ?? (config[keyPath: "appendUserAgent"] as? String) {
                appendedUserAgentString = agentString
            }
            if let agentString = (config[keyPath: "ios.overrideUserAgent"] as? String) ?? (config[keyPath: "overrideUserAgent"] as? String) {
                overridenUserAgentString = agentString
            }
            if let colorString = (config[keyPath: "ios.backgroundColor"] as? String) ?? (config[keyPath: "backgroundColor"] as? String),
               let color = UIColor.capacitor.color(fromHex: colorString) {
                backgroundColor = color
            }
            if let allowNav = config[keyPath: "server.allowNavigation"] as? [String] {
                allowedNavigationHostnames = allowNav
            }
            if let scheme = (config[keyPath: "server.iosScheme"] as? String)?.lowercased() {
                urlScheme = scheme
            }
            if let host = config[keyPath: "server.hostname"] as? String {
                urlHostname = host
            }
            if let urlString = config[keyPath: "server.url"] as? String {
                serverURL = urlString
            }
            if let errorPathString = (config[keyPath: "server.errorPath"] as? String) {
                errorPath = errorPathString
            }
            if let insetBehavior = config[keyPath: "ios.contentInset"] as? String {
                let availableInsets: [String: UIScrollView.ContentInsetAdjustmentBehavior] = ["automatic": .automatic,
                                                                                              "scrollableAxes": .scrollableAxes,
                                                                                              "never": .never,
                                                                                              "always": .always]
                if let option = availableInsets[insetBehavior] {
                    contentInsetAdjustmentBehavior = option
                }
            }
            if let allowPreviews = config[keyPath: "ios.allowsLinkPreview"] as? Bool {
                allowLinkPreviews = allowPreviews
            }
            if let scrollEnabled = config[keyPath: "ios.scrollEnabled"] as? Bool {
                scrollingEnabled = scrollEnabled
            }
            if let zoomEnabled = (config[keyPath: "ios.zoomEnabled"] as? Bool) ?? (config[keyPath: "zoomEnabled"] as? Bool) {
                zoomingEnabled = zoomEnabled
            }
            if let pluginConfig = config[keyPath: "plugins"] as? JSObject {
                pluginConfigurations = pluginConfig
            }
            if let value = (config[keyPath: "ios.loggingBehavior"] as? String) ?? (config[keyPath: "loggingBehavior"] as? String) {
                if let behavior = InstanceLoggingBehavior.behavior(from: value) {
                    loggingBehavior = behavior
                }
            }
            if let limitsNavigations = config[keyPath: "ios.limitsNavigationsToAppBoundDomains"] as? Bool {
                limitsNavigationsToAppBoundDomains = limitsNavigations
            }
            if let preferredMode = (config[keyPath: "ios.preferredContentMode"] as? String) {
                preferredContentMode = preferredMode
            }
            if let handleNotifications = config[keyPath: "ios.handleApplicationNotifications"] as? Bool {
                handleApplicationNotifications = handleNotifications
            }
            if let webContentsDebuggingEnabled = config[keyPath: "ios.webContentsDebuggingEnabled"] as? Bool {
                isWebDebuggable = webContentsDebuggingEnabled
            } else {
                #if DEBUG
                isWebDebuggable = true
                #else
                // this is needed for SPM xcframework Capacitor.  Can eventually be removed when the SPM package moves to being source-based.
                if let debugValue = Bundle.main.object(forInfoDictionaryKey: "CAPACITOR_DEBUG") as? String, debugValue == "true" {
                    isWebDebuggable = true
                }
                #endif
            }
            if let initialFocus = (config[keyPath: "ios.initialFocus"] as? Bool) ?? (config[keyPath: "initialFocus"] as? Bool) {
                hasInitialFocus = initialFocus
            }
            if let startPath = (config[keyPath: "server.appStartPath"] as? String) {
                appStartPath = startPath
            }
        }
    }
    // swiftlint:enable cyclomatic_complexity
    // swiftlint:enable function_body_length
}

extension InstanceDescriptor {
    public func normalize() {
        // first, make sure the scheme is valid
        var schemeValid = false
        if let scheme = urlScheme, WKWebView.handlesURLScheme(scheme) == false,
           scheme.range(of: "^[a-z][a-z0-9.+-]*$", options: [.regularExpression, .caseInsensitive], range: nil, locale: nil) != nil {
            schemeValid = true
        }
        if !schemeValid {
            // reset to the default
            urlScheme = InstanceDescriptorDefaults.scheme
        }
        // make sure we have a hostname
        if urlHostname == nil {
            urlHostname = InstanceDescriptorDefaults.hostname
        }
        // now validate the server.url
        var urlValid = false
        if let server = serverURL, URL(string: server) != nil {
            urlValid = true
        }
        if !urlValid {
            serverURL = nil
        }
        // reset the path if it's not valid
        if let path = appStartPath?.trimmingCharacters(in: .whitespacesAndNewlines), path.isEmpty {
            appStartPath = nil
        }
        // if the plugin configuration was programmatically modified, the necessary type information may have been lost.
        // so perform a coercion here to make sure that casting will work as expected
        pluginConfigurations = JSTypes.coerceDictionaryToJSObject(pluginConfigurations) ?? [:]
        legacyConfig = JSTypes.coerceDictionaryToJSObject(legacyConfig) ?? [:]
    }
}
