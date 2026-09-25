import Foundation
import Dispatch
import UIKit
import WebKit

internal typealias CapacitorPlugin = CAPPlugin & CAPBridgedPlugin

/**
 The bridge between the web view and the native plugins.

 An internal class adopting a public protocol means that we have a lot of `public` methods
 but that is by design not a mistake.

 This file declares the stored state, the initializer and the members subclasses can override; members declared in
 an extension cannot be overridden. The rest is split by concern:
 - `CapacitorBridge+Environment.swift`: environment and status bar properties
 - `CapacitorBridge+Plugins.swift`: registering, loading and looking up plugins
 - `CapacitorBridge+Calls.swift`: calling plugin methods and managing saved calls
 - `CapacitorBridge+JavaScript.swift`: exporting and evaluating JavaScript
 - `CapacitorBridge+Lifecycle.swift`: forwarding scene lifecycle transitions to the page
 - `CapacitorBridge+Paths.swift`: the server base path and file URL translation
 */
open class CapacitorBridge: CAPBridgeProtocol {

    // MARK: - CAPBridgeProtocol: Properties

    public var webView: WKWebView? {
        return bridgeDelegate?.bridgedWebView
    }

    public let autoRegisterPlugins: Bool

    public var notificationRouter: NotificationRouter

    public static let capacitorSite = "https://capacitorjs.com/"
    public static let fileStartIdentifier = "/_capacitor_file_"
    public static let httpInterceptorStartIdentifier = "/_capacitor_http_interceptor_"
    public static let httpInterceptorUrlParam = "u"
    public static let defaultScheme = "capacitor"

    public private(set) var webViewAssetHandler: WebViewAssetHandler
    public private(set) var webViewDelegationHandler: WebViewDelegationHandler
    public private(set) weak var bridgeDelegate: CAPBridgeDelegate?
    public var viewController: UIViewController? {
        return bridgeDelegate?.bridgedViewController
    }

    // `InstanceConfiguration` is a struct, so assigning it is not a single store the way the
    // Obj-C `CAPInstanceConfiguration` pointer was. `setServerBasePath` runs on the bridge's
    // dispatch queue while the view controller and the delegation handler read `config` on the
    // main thread, so both sides go through the lock and readers get a whole value.
    private let configLock = NSLock()
    private var lockedConfig: InstanceConfiguration
    public var config: InstanceConfiguration {
        get { configLock.withLock { lockedConfig } }
        set { configLock.withLock { lockedConfig = newValue } }
    }
    // All loaded and instantiated plugins by JavaScript name, in registration order
    let pluginRegistry = PluginRegistry()
    // Calls we are storing to resolve later
    var storedCalls = ConcurrentDictionary<CAPPluginCall>()
    // Calls of async plugin methods that have not returned yet
    let asyncCalls = AsyncPluginCalls()
    // Scripts injected before the page loads; see CapacitorBridge+JavaScript.swift
    var injectMiscFiles: [String] = []
    var canInjectJS: Bool = true

    // Background dispatch queue for plugin calls
    open private(set) var dispatchQueue = DispatchQueue(label: "bridge")
    // Array of block based observers
    var observers: [NSObjectProtocol] = []

    // MARK: - Initialization

    public init(with configuration: InstanceConfiguration, delegate bridgeDelegate: CAPBridgeDelegate, assetHandler: WebViewAssetHandler, delegationHandler: WebViewDelegationHandler, autoRegisterPlugins: Bool = true) {
        self.bridgeDelegate = bridgeDelegate
        self.webViewAssetHandler = assetHandler
        self.webViewDelegationHandler = delegationHandler
        self.lockedConfig = configuration
        self.notificationRouter = NotificationRouter()
        self.notificationRouter.handleApplicationNotifications = configuration.handleApplicationNotifications
        self.autoRegisterPlugins = autoRegisterPlugins

        // covers bridges that are created without a CAPBridgeViewController
        _ = CapacitorRuntimeHooks.install

        self.webViewDelegationHandler.bridge = self
        self.webViewAssetHandler.setConfiguration(configuration)

        exportCoreJS(localUrl: configuration.localURL.absoluteString)
        registerPlugins()
        setupLifecycleObservers()
        exportMiscJS()
        canInjectJS = false

        self.setupWebDebugging(configuration: configuration)
    }

    deinit {
        // nothing can receive the results of async methods any more
        asyncCalls.cancelAll()
        // the message handler needs to removed to avoid any retain cycles
        webViewDelegationHandler.cleanUp()
        for observer in observers {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    private func setupWebDebugging(configuration: InstanceConfiguration) {
        self.webView?.isInspectable = configuration.isWebDebuggable
    }

    // MARK: - Plugins

    /**
     Register all plugins that have been declared
     */
    func registerPlugins() {
        var pluginList: [AnyClass] = [CAPHttpPlugin.self, CAPConsolePlugin.self, CAPWebViewPlugin.self, CAPCookiesPlugin.self, CAPSystemBarsPlugin.self]

        if autoRegisterPlugins {
            do {
                if let pluginJSON = Bundle.main.url(forResource: "capacitor.config", withExtension: "json") {
                    let pluginData = try Data(contentsOf: pluginJSON)
                    let registrationList = try JSONDecoder().decode(RegistrationList.self, from: pluginData)

                    // keep the listed order so plugins are registered, and consulted, in the same order on every launch
                    var listed = Set<String>()
                    for plugin in registrationList.packageClassList where listed.insert(plugin).inserted {
                        if let pluginClass = NSClassFromString(plugin) {
                            pluginList.append(pluginClass)
                        }
                    }
                }
            } catch {
                CAPLog.print("Error registering plugins: \(error)")
            }
        }

        for plugin in pluginList {
            if plugin is CAPInstancePlugin.Type { continue }
            if let capPlugin = plugin as? CapacitorPlugin.Type {
                registerPlugin(capPlugin)
            }
        }
    }

    // MARK: - Sending Results

    /**
     Send a successful result to the JavaScript layer.

     `save` tells the page whether to keep the callback for further results, as it does for a call that is kept alive.
     */
    func toJs(result: JSResultProtocol, save: Bool) {
        let resultJson = result.jsonPayload()
        CAPLog.print("⚡️  TO JS", resultJson.prefix(256))
        evaluateFromNative(BridgeScript.fromNative(result, success: true, save: save, payload: resultJson))
    }

    /**
     Send an error result to the JavaScript layer.

     `save` has the same meaning as for ``toJs(result:save:)``: an error ends a call unless it is kept alive, and the
     page releases a callback-style entry only when it is told not to save it.
     */
    func toJsError(error: JSResultProtocol, save: Bool) {
        evaluateFromNative(BridgeScript.fromNative(error, success: false, save: save, payload: error.jsonPayload()))
    }

    private func evaluateFromNative(_ script: String) {
        DispatchQueue.main.async {
            self.webView?.evaluateJavaScript(script) { (_, error) in
                if let error = error {
                    CAPLog.print(error)
                }
            }
        }
    }

    // MARK: - CAPBridgeProtocol: View Presentation

    /// Presents an alert from the topmost view controller. Safe to call from any thread; plugins usually call it from
    /// the bridge queue, and UIKit is only used on the main thread.
    open func showAlertWith(title: String, message: String, buttonTitle: String) {
        let show = { [weak self] in
            guard let viewController = self?.viewController else {
                return
            }
            let alert = UIAlertController(title: title, message: message, preferredStyle: UIAlertController.Style.alert)
            alert.addAction(UIAlertAction(title: buttonTitle, style: UIAlertAction.Style.default, handler: nil))
            WebViewDelegationHandler.topmostViewController(from: viewController).present(alert, animated: true, completion: nil)
        }
        if Thread.isMainThread {
            show()
        } else {
            DispatchQueue.main.async(execute: show)
        }
    }
}
