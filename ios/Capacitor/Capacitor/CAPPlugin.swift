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
    public weak var webView: WKWebView?
    public weak var bridge: CAPBridgeProtocol?
    /// The Obj-C runtime name of the plugin class, copied from ``CAPBridgedPlugin/identifier``.
    public var pluginId: String = ""
    /// The name the plugin is exposed under in JavaScript, copied from ``CAPBridgedPlugin/jsName``.
    public var pluginName: String = ""
    /// When true (the default), `Date` values in a call's options are converted to ISO-8601 strings before the
    /// plugin sees them. Set it from ``load()``, not from an initializer: the bridge resets it to true while
    /// wiring the plugin up, which happens before ``load()`` is called.
    public var shouldStringifyDatesInCalls: Bool = true

    // Listeners are added/removed on the bridge's dispatch queue while notifications usually originate from the main thread
    // (or any other), so the storage for both dictionaries is guarded by this lock. The lock is never held while a
    // listener is being invoked.
    private let listenerLock = NSLock()
    private var lockedEventListeners: [String: [CAPPluginCall]] = [:]
    private var lockedRetainedEventArguments: [String: [PluginCallResultData?]] = [:]

    // Internal: plugins go through addEventListener, removeEventListener, notifyListeners, getListeners and hasListeners,
    // which keep the two dictionaries consistent. The bridge resets both when it loads the plugin, and tests read them.
    var eventListeners: [String: [CAPPluginCall]]? {
        get { return withListenerLock { lockedEventListeners } }
        set { withListenerLock { lockedEventListeners = newValue ?? [:] } }
    }

    var retainedEventArguments: [String: [PluginCallResultData?]]? {
        get { return withListenerLock { lockedRetainedEventArguments } }
        set { withListenerLock { lockedRetainedEventArguments = newValue ?? [:] } }
    }

    /// The bridge instantiates plugins through this initializer.
    override public required init() {
        super.init()
    }

    /// Called after init if the plugin wants to do some loading so the plugin author doesn't need to override `init()`.
    open func load() {}

    /// The plugin's JavaScript name. This is ``pluginName``, not ``pluginId``.
    open func getId() -> String {
        return pluginName
    }

    open func getConfig() -> PluginConfig {
        return bridge?.config.getPluginConfig(pluginName) ?? PluginConfig(config: JSObject())
    }

    // MARK: - Event Listeners

    open func addEventListener(_ eventName: String, listener: CAPPluginCall) {
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

    open func removeEventListener(_ eventName: String, listener: CAPPluginCall) {
        withListenerLock {
            guard var listeners = lockedEventListeners[eventName], let index = listeners.firstIndex(of: listener) else {
                return
            }
            listeners.remove(at: index)
            lockedEventListeners[eventName] = listeners
        }
    }

    open func notifyListeners(_ eventName: String, data: PluginCallResultData?) {
        notifyListeners(eventName, data: data, retainUntilConsumed: false)
    }

    open func notifyListeners(_ eventName: String, data: PluginCallResultData?, retainUntilConsumed retain: Bool) {
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

    open func getListeners(_ eventName: String) -> [CAPPluginCall]? {
        return withListenerLock { lockedEventListeners[eventName] }
    }

    open func hasListeners(_ eventName: String) -> Bool {
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
    ///
    /// The reset path calls this directly instead of dispatching `removeAllListeners:` with a nil call the way the
    /// Obj-C bridge did, so an override of ``removeAllListeners(_:)`` runs for the JavaScript call only.
    ///
    /// The bridge saved each listener call when ``addListener(_:)`` kept it alive, so the removed calls are released
    /// from the bridge as well; otherwise they stayed in its saved calls until the next navigation.
    ///
    /// Same as the Obj-C original: retained event arguments are *not* dropped here, so a payload retained with
    /// `retainUntilConsumed` before a navigation is still delivered to the first listener the next page adds.
    internal func removeAllListeners() {
        let removed: [CAPPluginCall] = withListenerLock {
            let listeners = lockedEventListeners.values.flatMap { $0 }
            lockedEventListeners.removeAll()
            return listeners
        }
        for listener in removed {
            bridge?.releaseCall(listener)
        }
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
    open func setCenteredPopover(_ viewController: UIViewController) {
        centerPopover(viewController, size: nil)
    }

    open func setCenteredPopover(_ viewController: UIViewController, size: CGSize) {
        centerPopover(viewController, size: size)
    }

    // MARK: - WebView Hooks

    /// Give the plugins a chance to take control when a URL is about to be loaded in the WebView.
    ///
    /// Returning true causes the WebView to abort loading the URL.
    /// Returning false causes the WebView to continue loading the URL.
    /// Returning nil will defer to the default Capacitor policy
    open func shouldOverrideLoad(_ navigationAction: WKNavigationAction) -> Bool? {
        return nil
    }

    /// Allows plugins to hook into and respond to the WebView's URL authentication challenge.
    ///
    /// Returning false will defer to the default response of
    /// [.rejectProtectionSpace](https://developer.apple.com/documentation/Foundation/URLSession/AuthChallengeDisposition/rejectProtectionSpace).
    open func handleWKWebViewURLAuthenticationChallenge(_ challenge: URLAuthenticationChallenge,
                                                        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) -> Bool {
        return false
    }

    // MARK: - Private

    private func centerPopover(_ viewController: UIViewController, size: CGSize?) {
        guard let view = bridge?.viewController?.view else {
            return
        }
        viewController.popoverPresentationController?.sourceRect = CGRect(x: view.center.x, y: view.center.y, width: 0, height: 0)
        if let size {
            viewController.preferredContentSize = size
        }
        viewController.popoverPresentationController?.sourceView = view
        viewController.popoverPresentationController?.permittedArrowDirections = []
    }

    private func withListenerLock<T>(_ body: () -> T) -> T {
        listenerLock.lock()
        defer { listenerLock.unlock() }
        return body()
    }
}
