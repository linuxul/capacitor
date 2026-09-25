import Foundation
import WebKit

// MARK: - Exporting JavaScript

extension CapacitorBridge {
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
    public func evalWithPlugin(_ plugin: CAPPlugin, js: String) { // swiftlint:disable:this identifier_name
        eval(js: BridgeScript.withPlugin(plugin.getId(), js: js))
    }

    /**
     Eval JS in the web view

     `js` is a short name but needs to be preserved for backwards compatibility.
     */
    public func eval(js: String) { // swiftlint:disable:this identifier_name
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
}
