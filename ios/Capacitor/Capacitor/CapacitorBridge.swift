import Foundation
import Dispatch
import UIKit
import WebKit

internal typealias CapacitorPlugin = CAPPlugin & CAPBridgedPlugin

struct RegistrationList: Codable {
    /// The plugin classes the CLI found, in the order it listed them.
    let packageClassList: [String]
}

/**
 An internal class adopting a public protocol means that we have a lot of `public` methods
 but that is by design not a mistake. And since the bridge is the center of the whole project
 its size/complexity is unavoidable.

 Quiet these warnings for the whole file.
 */
// swiftlint:disable lower_acl_than_parent
// swiftlint:disable file_length
// swiftlint:disable type_body_length
open class CapacitorBridge: NSObject, CAPBridgeProtocol {

    // this decision is needed before the bridge is instantiated,
    // so we need a class property to avoid duplication
    public static var isDevEnvironment: Bool {
        #if DEBUG
        return true
        #else
        // this is needed for SPM xcframework Capacitor.  Can eventually be removed when the SPM package moves to being source-based.
        if let debugValue = Bundle.main.object(forInfoDictionaryKey: "CAPACITOR_DEBUG") as? String, debugValue == "true" {
            return true
        }
        return false
        #endif
    }

    // MARK: - CAPBridgeProtocol: Properties

    public var webView: WKWebView? {
        return bridgeDelegate?.bridgedWebView
    }

    public let autoRegisterPlugins: Bool

    public var notificationRouter: NotificationRouter

    public var isSimEnvironment: Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }

    public var isDevEnvironment: Bool {
        return CapacitorBridge.isDevEnvironment
    }

    public var userInterfaceStyle: UIUserInterfaceStyle {
        return viewController?.traitCollection.userInterfaceStyle ?? .unspecified
    }

    public var statusBarVisible: Bool {
        get {
            return !(viewController?.prefersStatusBarHidden ?? true)
        }
        set {
            DispatchQueue.main.async { [weak self] in
                (self?.viewController as? CAPBridgeViewController)?.setStatusBarVisible(newValue)
            }
        }
    }

    public var statusBarStyle: UIStatusBarStyle {
        get {
            return viewController?.preferredStatusBarStyle ?? .default
        }
        set {
            DispatchQueue.main.async { [weak self] in
                (self?.viewController as? CAPBridgeViewController)?.setStatusBarStyle(newValue)
            }
        }
    }

    public var statusBarAnimation: UIStatusBarAnimation {
        get {
            return (viewController as? CAPBridgeViewController)?.statusBarAnimation ?? .slide
        }
        set {
            DispatchQueue.main.async { [weak self] in
                (self?.viewController as? CAPBridgeViewController)?.setStatusBarAnimation(newValue)
            }
        }
    }
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
    private var injectMiscFiles: [String] = []
    private var canInjectJS: Bool = true

    // Background dispatch queue for plugin calls
    open private(set) var dispatchQueue = DispatchQueue(label: "bridge")
    // Array of block based observers
    var observers: [NSObjectProtocol] = []

    public func setServerBasePath(_ path: String) {
        let url = URL(fileURLWithPath: path, isDirectory: true)
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        config = config.updatingAppLocation(url)
        webViewAssetHandler.setAssetPath(url.path)
    }

    // MARK: - Static Methods

    /**
     Print a hopefully informative error message to the log when something
     particularly dreadful happens.
     */
    static func fatalError(_ error: Error) {
        CAPLog.print("⚡️ ❌  Capacitor: FATAL ERROR")
        CAPLog.print("⚡️ ❌  Error was: ", error.localizedDescription)
        switch error {
        case CapacitorBridgeError.errorExportingCoreJS:
            CAPLog.print("⚡️ ❌  Unable to export required Bridge JavaScript. Bridge will not function.")
            CAPLog.print("⚡️ ❌  You should run \"npx capacitor copy\" to ensure the Bridge JS is added to your project.")
        default:
            CAPLog.print("⚡️ ❌  Unknown error")
        }

        CAPLog.print("⚡️ ❌  Please verify your installation or file an issue")
    }

    // MARK: - Initialization

    public init(with configuration: InstanceConfiguration, delegate bridgeDelegate: CAPBridgeDelegate, assetHandler: WebViewAssetHandler, delegationHandler: WebViewDelegationHandler, autoRegisterPlugins: Bool = true) {
        self.bridgeDelegate = bridgeDelegate
        self.webViewAssetHandler = assetHandler
        self.webViewDelegationHandler = delegationHandler
        self.lockedConfig = configuration
        self.notificationRouter = NotificationRouter()
        self.notificationRouter.handleApplicationNotifications = configuration.handleApplicationNotifications
        self.autoRegisterPlugins = autoRegisterPlugins
        super.init()

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
        // the message handler needs to removed to avoid any retain cycles
        webViewDelegationHandler.cleanUp()
        for observer in observers {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    // MARK: - Plugins
    /**
     Export core JavaScript to the webview
     */
    func exportCoreJS(localUrl: String) {
        do {
            try JSExport.exportCapacitorGlobalJS(userContentController: webViewDelegationHandler.contentController,
                                                 isDebug: isDevEnvironment,
                                                 loggingEnabled: config.loggingEnabled,
                                                 localUrl: localUrl)
            try JSExport.exportBridgeJS(userContentController: webViewDelegationHandler.contentController)
        } catch {
            type(of: self).fatalError(error)
        }
    }

    /**
     Export misc JavaScript to the webview
     */
    func exportMiscJS() {
        JSExport.exportMiscFileJS(paths: injectMiscFiles, userContentController: webViewDelegationHandler.contentController)
        injectMiscFiles.removeAll()
    }

    /**
     Observe scene lifecycle transitions and forward them to the page as `resume` and `pause` document events.
     */
    func setupLifecycleObservers() {
        observers.append(NotificationCenter.default.addObserver(forName: UIScene.willEnterForegroundNotification, object: nil, queue: OperationQueue.main) { [weak self] notification in
            self?.triggerSceneLifecycleJSEvent("resume", for: notification)
        })
        observers.append(NotificationCenter.default.addObserver(forName: UIScene.didEnterBackgroundNotification, object: nil, queue: OperationQueue.main) { [weak self] notification in
            self?.triggerSceneLifecycleJSEvent("pause", for: notification)
        })
    }

    /**
     Forward a scene lifecycle transition to the page as a document event, but only once
     the page exists to receive it.

     On a cold start `UIScene.willEnterForegroundNotification` is posted while the initial
     load is still in flight, before `window.Capacitor` has been defined. Evaluating
     `triggerEvent` at that point throws inside the web view and surfaces as a
     "JS Eval error" in the log. The same happens when the web content process was
     terminated and the page is being reloaded. In both cases the page is about to load
     from scratch, so there is nothing for it to resume or pause; the event is dropped
     rather than deferred, because a "resume" delivered right after a fresh load would
     make the page react to a transition it never went through.
     */
    private func triggerSceneLifecycleJSEvent(_ eventName: String, for notification: Notification) {
        guard let scene = notification.object as? UIWindowScene,
              scene === viewController?.view.window?.windowScene else {
            return
        }
        guard case .subsequentLoad = webViewDelegationHandler.webViewLoadingState, webView?.isLoading == false else {
            return
        }
        triggerDocumentJSEvent(eventName: eventName)
    }

    /**
     Reset the state of the bridge between navigations to avoid
     sending data back to the page from a previous page.
     */
    func reset() {
        storedCalls.withLock { $0.removeAll() }
        removeAllPluginListeners()
    }

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

    public func registerPluginType(_ pluginType: CAPPlugin.Type) {
        if autoRegisterPlugins { return }
        if pluginType is CAPInstancePlugin.Type {
            Swift.fatalError("""

            ⚡️ ❌  Cannot register class \(pluginType): CAPInstancePlugin through registerPluginType(_:).
            ⚡️ ❌  Use `registerPluginInstance(_:)` to register subclasses of CAPInstancePlugin.
            """)
        }
        guard let bridgedType = pluginType as? CapacitorPlugin.Type else { return }
        registerPlugin(bridgedType)
    }

    public func registerPluginInstance(_ pluginInstance: CAPPlugin) {
        guard let pluginInstance = pluginInstance as? CapacitorPlugin else {
            CAPLog.print("""

            ⚡️  Plugin \(pluginInstance.classForCoder) must conform to CAPBridgedPlugin.
            ⚡️  Not loading plugin \(pluginInstance.classForCoder)
            """)
            return
        }

        if pluginRegistry.register(pluginInstance) != nil {
            CAPLog.print("⚡️  Overriding existing registered plugin \(pluginInstance.classForCoder)")
        }
        pluginInstance.load(on: self)

        JSExport.exportJS(for: pluginInstance, in: webViewDelegationHandler.contentController)
    }

    /**
     Register a single plugin.
     */
    func registerPlugin(_ pluginType: CapacitorPlugin.Type) {
        if let plugin = loadPlugin(type: pluginType) {
            JSExport.exportJS(for: plugin, in: webViewDelegationHandler.contentController)
        }
    }

    func loadPlugin(type: CAPPlugin.Type) -> CapacitorPlugin? {
        guard let plugin = type.init() as? CapacitorPlugin else {
            CAPLog.print("⚡️  Unable to load plugin \(type.classForCoder()). No such module found.")
            return nil
        }
        plugin.load(on: self)
        pluginRegistry.register(plugin)
        return plugin
    }

    /**
     Load a plugin that the page calls by a name no registered plugin has.

     This keeps plugins working that are not listed for registration but whose Obj-C class name is their JavaScript
     name. Nothing else is instantiated: the class must be a bridged plugin the bridge creates itself (not a
     `CAPInstancePlugin`), a class that is registered already is never created a second time, and the new instance is
     kept only when its JavaScript name is the requested name. A page that called a plugin by its class identifier
     used to get a second instance registered under the plugin's JavaScript name, replacing the one in use.
     */
    func lazyLoadPlugin(named name: String) -> CapacitorPlugin? {
        guard let type = NSClassFromString(name) as? CapacitorPlugin.Type, !(type is CAPInstancePlugin.Type) else {
            return nil
        }
        guard !pluginRegistry.all.contains(where: { Swift.type(of: $0) == type }) else {
            CAPLog.print("⚡️  \(name) is a registered plugin class; call it by its JavaScript name")
            return nil
        }
        let plugin = type.init()
        guard plugin.jsName == name else {
            CAPLog.print("⚡️  Not loading \(name): its JavaScript name is \(plugin.jsName)")
            return nil
        }
        plugin.load(on: self)
        return pluginRegistry.registerIfAbsent(plugin)
    }

    // MARK: - CAPBridgeProtocol: Plugin Access

    public func plugin(withName: String) -> CAPPlugin? {
        return pluginRegistry[withName]
    }

    // MARK: - CAPBridgeProtocol: Call Management

    public func saveCall(_ call: CAPPluginCall) {
        storedCalls[call.callbackId] = call
    }

    public func savedCall(withID: String) -> CAPPluginCall? {
        return storedCalls[withID]
    }

    public func releaseCall(_ call: CAPPluginCall) {
        releaseCall(withID: call.callbackId)
    }

    public func releaseCall(withID: String) {
        _ = storedCalls.withLock { $0.removeValue(forKey: withID) }
    }

    // MARK: - Internal

    func docLink(_ url: String) -> String {
        return "\(type(of: self).capacitorSite)docs/\(url)"
    }

    private func setupWebDebugging(configuration: InstanceConfiguration) {
        self.webView?.isInspectable = configuration.isWebDebuggable
    }

    /**
     Handle a call from JavaScript. First, find the corresponding plugin, construct a selector,
     and perform that selector on the plugin instance.

     Quiet the length warning because we don't want to refactor the function at this time.
     */
    // swiftlint:disable:next function_body_length
    func handleJSCall(call: JSCall) {
        guard let plugin = pluginRegistry[call.pluginId] ?? lazyLoadPlugin(named: call.pluginId) else {
            rejectJSCall(call, message: "Error loading plugin \(call.pluginId) for call. Check that the pluginId is correct")
            return
        }

        let selector: Selector
        if call.method == "addListener" || call.method == "removeListener" || call.method == "removeAllListeners" {
            selector = NSSelectorFromString(call.method + ":")
        } else {
            guard let method = plugin.getMethod(named: call.method) else {
                CAPLog.print("⚡️  Ensure plugin method exists and uses @objc in its declaration, and has been defined")
                rejectJSCall(call, message: "Error calling method \(call.method) on plugin \(call.pluginId): No method found.")
                return
            }

            selector = method.selector
        }

        if !plugin.responds(to: selector) {
            CAPLog.print("⚡️  Ensure plugin method exists, uses @objc in its declaration, and is listed in the pluginMethods of the plugin.")
            CAPLog.print("⚡️  Learn more: \(docLink(DocLinks.CAPPluginMethodSelector.rawValue))")
            rejectJSCall(call, message: "Plugin \(plugin.getId()) does not respond to method \(call.method) using selector \(selector).")
            return
        }

        // Create a plugin call object and handle the success/error callbacks
        dispatchQueue.async { [weak self] in
            // The error handler has no call parameter, but needs keepAlive to tell the page whether to keep its callback.
            // The call is alive whenever its handler runs, so a weak reference is enough.
            weak var weakPluginCall: CAPPluginCall?
            let pluginCall = CAPPluginCall(callbackId: call.callbackId, methodName: call.method,
                                           options: JSTypes.coerceDictionaryToJSObject(call.options,
                                                                                       formattingDatesAsStrings: plugin.shouldStringifyDatesInCalls) ?? [:],
                                           success: { (result: CAPPluginCallResult, pluginCall: CAPPluginCall) in
                                            self?.toJs(result: JSResult(call: call, callResult: result), save: pluginCall.keepAlive)
                                           }, error: { (error: CAPPluginCallError) in
                                            let save = weakPluginCall?.keepAlive ?? false
                                            self?.toJsError(error: JSResultError(call: call, callError: error), save: save)
                                           })
            weakPluginCall = pluginCall
            pluginCall.pluginName = call.pluginId

            plugin.perform(selector, with: pluginCall)
            if pluginCall.keepAlive {
                self?.saveCall(pluginCall)
            }
        }
    }

    private func rejectJSCall(_ call: JSCall, message: String) {
        CAPLog.print("⚡️  \(message)")
        let error = CAPPluginCallError(message: message, code: "UNIMPLEMENTED", error: nil, data: nil)
        toJsError(error: JSResultError(call: call, callError: error), save: false)
    }

    func removeAllPluginListeners() {
        for plugin in pluginRegistry.all {
            plugin.removeAllListeners()
        }
    }

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

    // MARK: - CAPBridgeProtocol: JavaScript Handling

    /**
     Inject JavaScript from an external file before the WebView loads.

     `path` is relative to the public folder
     */
    public func injectScriptBeforeLoad(path: String) {
        if canInjectJS {
            injectMiscFiles.append(path)
        }
    }

    /**
     Eval JS for a specific plugin.

     `js` is a short name but needs to be preserved for backwards compatibility.
     */
    // swiftlint:disable:next identifier_name
    public func evalWithPlugin(_ plugin: CAPPlugin, js: String) {
        eval(js: BridgeScript.withPlugin(plugin.getId(), js: js))
    }

    /**
     Eval JS in the web view

     `js` is a short name but needs to be preserved for backwards compatibility.
     */
    // swiftlint:disable:next identifier_name
    public func eval(js: String) {
        DispatchQueue.main.async {
            self.webView?.evaluateJavaScript(js, completionHandler: { (_, error) in
                if let error = error {
                    CAPLog.print("⚡️  JS Eval error", error.localizedDescription)
                }
            })
        }
    }

    public func triggerJSEvent(eventName: String, target: String) {
        self.eval(js: BridgeScript.triggerEvent(eventName, target: target))
    }

    public func triggerJSEvent(eventName: String, target: String, data: String) {
        self.eval(js: BridgeScript.triggerEvent(eventName, target: target, data: data))
    }

    public func triggerWindowJSEvent(eventName: String) {
        self.triggerJSEvent(eventName: eventName, target: "window")
    }

    public func triggerWindowJSEvent(eventName: String, data: String) {
        self.triggerJSEvent(eventName: eventName, target: "window", data: data)
    }

    public func triggerDocumentJSEvent(eventName: String) {
        self.triggerJSEvent(eventName: eventName, target: "document")
    }

    public func triggerDocumentJSEvent(eventName: String, data: String) {
        self.triggerJSEvent(eventName: eventName, target: "document", data: data)
    }

    public func logToJs(_ message: String, _ level: String = "log") {
        DispatchQueue.main.async {
            self.webView?.evaluateJavaScript(BridgeScript.logJs(message, level: level)) { (_, error) in
                if let error = error {
                    CAPLog.print(error)
                }
            }
        }
    }

    // MARK: - CAPBridgeProtocol: Paths, Files, Assets

    /**
     Translate a URL from the web view into a file URL for native iOS.

     The web view may be handling several different types of URLs:
     - res:// (shortcut scheme to web assets)
     - file:// (fully qualified URL to file on the local device)
     - base64:// (to be implemented)
     - [web view scheme]:// (already converted once to load in the web view, to be implemented)
     */
    public func localURL(fromWebURL webURL: URL?) -> URL? {
        guard let inputURL = webURL else {
            return nil
        }

        let url: URL

        switch inputURL.scheme {
        case "res":
            url = config.appLocation.appendingPathComponent(inputURL.path)
        case "file":
            url = inputURL
        default:
            return nil
        }

        return url
    }

    /**
     Translate a file URL for native iOS into a URL to load in the web view.
     */
    public func portablePath(fromLocalURL localURL: URL?) -> URL? {
        guard let inputURL = localURL else {
            return nil
        }

        return self.config.localURL.appendingPathComponent(CapacitorBridge.fileStartIdentifier).appendingPathComponent(inputURL.path)
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
