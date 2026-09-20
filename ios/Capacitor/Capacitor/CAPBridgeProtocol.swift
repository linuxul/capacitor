import Foundation
import UIKit
import WebKit

public protocol CAPBridgeProtocol: AnyObject {
    // MARK: - Environment Properties
    var viewController: UIViewController? { get }
    var config: InstanceConfiguration { get }
    var webView: WKWebView? { get }
    var notificationRouter: NotificationRouter { get }
    var isSimEnvironment: Bool { get }
    var isDevEnvironment: Bool { get }
    var userInterfaceStyle: UIUserInterfaceStyle { get }
    var autoRegisterPlugins: Bool { get }
    var statusBarVisible: Bool { get set }
    var statusBarStyle: UIStatusBarStyle { get set }
    var statusBarAnimation: UIStatusBarAnimation { get set }

    // MARK: - Plugin Access
    func plugin(withName: String) -> CAPPlugin?

    // MARK: - Call Management
    func saveCall(_ call: CAPPluginCall)
    func savedCall(withID: String) -> CAPPluginCall?
    func releaseCall(_ call: CAPPluginCall)
    func releaseCall(withID: String)

    // MARK: - JavaScript Handling
    // `js` is a short name but needs to be preserved for backwards compatibility.
    // swiftlint:disable identifier_name
    func evalWithPlugin(_ plugin: CAPPlugin, js: String)
    func eval(js: String)
    // swiftlint:enable identifier_name

    func injectScriptBeforeLoad(path: String)

    func triggerJSEvent(eventName: String, target: String)
    func triggerJSEvent(eventName: String, target: String, data: String)

    func triggerWindowJSEvent(eventName: String)
    func triggerWindowJSEvent(eventName: String, data: String)

    func triggerDocumentJSEvent(eventName: String)
    func triggerDocumentJSEvent(eventName: String, data: String)

    // MARK: - Paths, Files, Assets
    func localURL(fromWebURL webURL: URL?) -> URL?
    func portablePath(fromLocalURL localURL: URL?) -> URL?
    func setServerBasePath(_ path: String)

    // MARK: - Plugins
    func registerPluginType(_ pluginType: CAPPlugin.Type)
    func registerPluginInstance(_ pluginInstance: CAPPlugin)

    // MARK: - View Presentation
    func showAlertWith(title: String, message: String, buttonTitle: String)
}

extension CAPBridgeProtocol {
    // default arguments are not permitted in protocol declarations
    public func alert(_ title: String, _ message: String, _ buttonTitle: String = "OK") {
        showAlertWith(title: title, message: message, buttonTitle: buttonTitle)
    }

    // optional requirement: bridges that do not support script injection can rely on this no-op
    public func injectScriptBeforeLoad(path: String) {}
}

/*
 Error(s) potentially exported by the bridge.
 */
public enum CapacitorBridgeError: Error {
    case errorExportingCoreJS
}

extension CapacitorBridgeError: CustomNSError {
    public static var errorDomain: String { "CapacitorBridge" }
    public var errorCode: Int {
        switch self {
        case .errorExportingCoreJS:
            return 0
        }
    }
    public var errorUserInfo: [String: Any] {
        return ["info": String(describing: self)]
    }
}

extension CapacitorBridgeError: LocalizedError {
    public var errorDescription: String? {
        return NSLocalizedString("Unable to export JavaScript bridge code to webview", comment: "Capacitor bridge initialization error")
    }
}
