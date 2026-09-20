import Foundation
import UIKit
import WebKit

/// The base class of every Capacitor plugin.
///
/// Plugin classes are looked up by their Obj-C runtime name and instantiated by the bridge through ``init()``, so
/// subclasses must be annotated with `@objc(Name)` and any subclass that declares its own designated initializer
/// must also implement the required ``init()``.
@objc(CAPPlugin)
open class CAPPlugin: NSObject {
    @objc public weak var webView: WKWebView?
    @objc public weak var bridge: CAPBridgeProtocol?
    @objc public var pluginId: String = ""
    @objc public var pluginName: String = ""
    @objc public var shouldStringifyDatesInCalls: Bool = true

    // Listeners are added/removed on the bridge's dispatch queue while notifications usually originate from the main thread
    // (or any other), so the storage for both dictionaries is guarded by this lock. The lock is never held while a
    // listener is being invoked.
    private let listenerLock = NSLock()
    private var lockedEventListeners: [String: [CAPPluginCall]] = [:]
    private var lockedRetainedEventArguments: [String: [PluginCallResultData?]] = [:]

    public var eventListeners: [String: [CAPPluginCall]]? {
        get { return withListenerLock { lockedEventListeners } }
        set { withListenerLock { lockedEventListeners = newValue ?? [:] } }
    }

    public var retainedEventArguments: [String: [PluginCallResultData?]]? {
        get { return withListenerLock { lockedRetainedEventArguments } }
        set { withListenerLock { lockedRetainedEventArguments = newValue ?? [:] } }
    }

    /// The bridge instantiates plugins through this initializer.
    override public required init() {
        super.init()
    }

    @available(*, deprecated, message: "This initializer is deprecated and is not suggested for use. Any data set through this init method will be overridden when it is loaded on the bridge.")
    public convenience init(bridge: CAPBridgeProtocol, pluginId: String, pluginName: String) {
        self.init()
        self.bridge = bridge
        self.webView = bridge.webView
        self.pluginId = pluginId
        self.pluginName = pluginName
    }

    /// Called after init if the plugin wants to do some loading so the plugin author doesn't need to override `init()`.
    @objc open func load() {}

    @objc open func getId() -> String {
        return pluginName
    }

    @available(*, deprecated, message: "use getConfig() and access config values using the methods available depending on the type.")
    @objc open func getConfigValue(_ key: String) -> Any? {
        return bridge?.config.getPluginConfigValue(pluginName, key)
    }

    @objc open func getConfig() -> PluginConfig {
        return bridge?.config.getPluginConfig(pluginName) ?? PluginConfig(config: JSObject())
    }

    // MARK: - Event Listeners

    @objc open func addEventListener(_ eventName: String, listener: CAPPluginCall) {
        let retained: [PluginCallResultData?] = withListenerLock {
            if let listeners = lockedEventListeners[eventName], !listeners.isEmpty {
                lockedEventListeners[eventName] = listeners + [listener]
                return []
            }
            lockedEventListeners[eventName] = [listener]
            // take ownership of the retained arguments so that they can only be delivered once
            return lockedRetainedEventArguments.removeValue(forKey: eventName) ?? []
        }
        for data in retained {
            notifyListeners(eventName, data: data)
        }
    }

    @objc open func removeEventListener(_ eventName: String, listener: CAPPluginCall) {
        withListenerLock {
            guard var listeners = lockedEventListeners[eventName], let index = listeners.firstIndex(of: listener) else {
                return
            }
            listeners.remove(at: index)
            lockedEventListeners[eventName] = listeners
        }
    }

    @objc open func notifyListeners(_ eventName: String, data: PluginCallResultData?) {
        notifyListeners(eventName, data: data, retainUntilConsumed: false)
    }

    @objc open func notifyListeners(_ eventName: String, data: PluginCallResultData?, retainUntilConsumed retain: Bool) {
        let listeners: [CAPPluginCall] = withListenerLock {
            if let listeners = lockedEventListeners[eventName], !listeners.isEmpty {
                return listeners
            }
            if retain {
                lockedRetainedEventArguments[eventName, default: []].append(data)
            }
            return []
        }
        for call in listeners {
            call.successHandler(CAPPluginCallResult(data), call)
        }
    }

    @objc open func getListeners(_ eventName: String) -> [CAPPluginCall]? {
        return withListenerLock { lockedEventListeners[eventName] }
    }

    @objc open func hasListeners(_ eventName: String) -> Bool {
        return withListenerLock { !(lockedEventListeners[eventName]?.isEmpty ?? true) }
    }

    // The following three methods are invoked from JavaScript through their selectors.

    @objc open func addListener(_ call: CAPPluginCall) {
        guard let eventName = call.getString("eventName") else {
            call.reject("eventName is required")
            return
        }
        call.keepAlive = true
        addEventListener(eventName, listener: call)
    }

    @objc open func removeListener(_ call: CAPPluginCall) {
        // Messaging nil was harmless in the Obj-C implementation, so missing values must remain a no-op.
        guard let callbackId = call.getString("callbackId") else {
            return
        }
        if let eventName = call.getString("eventName"), let storedCall = bridge?.savedCall(withID: callbackId) {
            removeEventListener(eventName, listener: storedCall)
        }
        bridge?.releaseCall(withID: callbackId)
    }

    @objc open func removeAllListeners(_ call: CAPPluginCall) {
        removeAllListeners()
        call.resolve()
    }

    /// Removes every listener without needing a call to resolve. Used by the bridge when the web view is reset.
    internal func removeAllListeners() {
        withListenerLock { lockedEventListeners.removeAll() }
    }

    // MARK: - Permissions

    /// Default implementation of the capacitor 3.0 permission pattern
    @objc open func checkPermissions(_ call: CAPPluginCall) {
        call.resolve()
    }

    @objc open func requestPermissions(_ call: CAPPluginCall) {
        call.resolve()
    }

    // MARK: - Popovers

    /// Configure popover sourceRect, sourceView and permittedArrowDirections to show it centered
    @objc open func setCenteredPopover(_ viewController: UIViewController) {
        guard let view = bridge?.viewController?.view else {
            return
        }
        viewController.popoverPresentationController?.sourceRect = CGRect(x: view.center.x, y: view.center.y, width: 0, height: 0)
        viewController.popoverPresentationController?.sourceView = view
        viewController.popoverPresentationController?.permittedArrowDirections = []
    }

    @objc open func setCenteredPopover(_ viewController: UIViewController, size: CGSize) {
        guard let view = bridge?.viewController?.view else {
            return
        }
        viewController.popoverPresentationController?.sourceRect = CGRect(x: view.center.x, y: view.center.y, width: 0, height: 0)
        viewController.preferredContentSize = size
        viewController.popoverPresentationController?.sourceView = view
        viewController.popoverPresentationController?.permittedArrowDirections = []
    }

    @available(*, deprecated, message: "All iOS 13+ devices support popover")
    @objc open func supportsPopover() -> Bool {
        return true
    }

    // MARK: - WebView Hooks

    /// Give the plugins a chance to take control when a URL is about to be loaded in the WebView.
    ///
    /// Returning true causes the WebView to abort loading the URL.
    /// Returning false causes the WebView to continue loading the URL.
    /// Returning nil will defer to the default Capacitor policy
    @objc open func shouldOverrideLoad(_ navigationAction: WKNavigationAction) -> NSNumber? {
        return nil
    }

    /// Allows plugins to hook into and respond to the WebView's URL authentication challenge.
    ///
    /// Returning false will defer to the default response of
    /// [.rejectProtectionSpace](https://developer.apple.com/documentation/Foundation/URLSession/AuthChallengeDisposition/rejectProtectionSpace).
    @objc open func handleWKWebViewURLAuthenticationChallenge(_ challenge: URLAuthenticationChallenge,
                                                              completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) -> Bool {
        return false
    }

    // MARK: - Private

    private func withListenerLock<T>(_ body: () -> T) -> T {
        listenerLock.lock()
        defer { listenerLock.unlock() }
        return body()
    }
}
