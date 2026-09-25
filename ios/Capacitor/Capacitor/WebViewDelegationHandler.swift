import Foundation
import UIKit
import WebKit

@objc(CAPWebViewDelegationHandler)
open class WebViewDelegationHandler: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler, UIScrollViewDelegate {
    public internal(set) weak var bridge: CapacitorBridge?
    open fileprivate(set) var contentController = WKUserContentController()
    enum WebViewLoadingState {
        case unloaded
        case initialLoad(isOpaque: Bool)
        case subsequentLoad
    }

    fileprivate(set) var webViewLoadingState = WebViewLoadingState.unloaded

    private let handlerName = "bridge"

    override public init() {
        super.init()
        contentController.add(self, name: handlerName)
    }

    open func cleanUp() {
        contentController.removeScriptMessageHandler(forName: handlerName)
    }

    open func willLoadWebview(_ webView: WKWebView?) {
        // Set the webview to be not opaque on the inital load. This prevents
        // the webview from showing a white background, which is its default
        // loading display, as that can appear as a screen flash. The opacity
        // might have been set by something else, like a plugin, so we want
        // to save the current value so it can be reset on success or failure.
        if let webView = webView, case .unloaded = webViewLoadingState {
            webViewLoadingState = .initialLoad(isOpaque: webView.isOpaque)
            webView.isOpaque = false
        }
    }

    // MARK: - WKNavigationDelegate

    // The force unwrap is part of the protocol declaration, so we should keep it.
    // swiftlint:disable:next implicitly_unwrapped_optional
    open func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        // Reset the bridge on each navigation
        bridge?.reset()
    }

    open func webView(
        _ webView: WKWebView,
        requestMediaCapturePermissionFor origin: WKSecurityOrigin,
        initiatedByFrame frame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: @escaping (WKPermissionDecision) -> Void
    ) {
        decisionHandler(.grant)
    }

    open func webView(_ webView: WKWebView,
                      requestDeviceOrientationAndMotionPermissionFor origin: WKSecurityOrigin,
                      initiatedByFrame frame: WKFrameInfo,
                      decisionHandler: @escaping (WKPermissionDecision) -> Void) {
        decisionHandler(.grant)
    }

    open func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        // post a notification for any listeners
        NotificationCenter.default.post(name: .capacitorDecidePolicyForNavigationAction, object: navigationAction)

        // sanity check, these shouldn't ever be nil in practice
        guard let bridge = bridge, let navURL = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }

        // The proxy returns a remote body at the app origin, so block it before plugins can allow it.
        if navURL.path.starts(with: CapacitorBridge.httpInterceptorStartIdentifier) {
            decisionHandler(.cancel)
            return
        }

        // first, give plugins the chance to handle the decision
        for plugin in bridge.plugins.values {
            if let shouldOverrideLoad = plugin.shouldOverrideLoad(navigationAction) {
                decisionHandler(shouldOverrideLoad ? .cancel : .allow)
                return
            }
        }

        // next, check if this is covered by the allowedNavigation configuration
        if let host = navURL.host, bridge.config.shouldAllowNavigation(to: host) {
            decisionHandler(.allow)
            return
        }

        // otherwise, is this a new window or a main frame navigation but to an outside source
        let toplevelNavigation = (navigationAction.targetFrame == nil || navigationAction.targetFrame?.isMainFrame == true)

        // Check if the url being navigated to is configured as an application url (whether local or remote)
        let isApplicationNavigation = navURL.absoluteString.starts(with: bridge.config.serverURL.absoluteString) ||
            navURL.absoluteString.starts(with: bridge.config.localURL.absoluteString)

        if !isApplicationNavigation, toplevelNavigation {
            // disallow and let the system handle it
            if webView.window?.windowScene?.activationState == .foregroundActive {
                UIApplication.shared.open(navURL, options: [:], completionHandler: nil)
            }
            decisionHandler(.cancel)
            return
        }

        // fallthrough to allowing it
        decisionHandler(.allow)
    }

    // The force unwrap is part of the protocol declaration, so we should keep it.
    // swiftlint:disable:next implicitly_unwrapped_optional
    open func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        if case .initialLoad(let isOpaque) = webViewLoadingState {
            webView.isOpaque = isOpaque
            webViewLoadingState = .subsequentLoad
        }
        CAPLog.print("⚡️  WebView loaded")
    }

    // The force unwrap is part of the protocol declaration, so we should keep it.
    // swiftlint:disable:next implicitly_unwrapped_optional
    open func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        if case .initialLoad(let isOpaque) = webViewLoadingState {
            webView.isOpaque = isOpaque
            webViewLoadingState = .subsequentLoad
        }

        if let errorURL = bridge?.config.errorPathURL {
            webView.load(URLRequest(url: errorURL))
        }

        CAPLog.print("⚡️  WebView failed to load")
        CAPLog.print("⚡️  Error: " + error.localizedDescription)
    }

    // The force unwrap is part of the protocol declaration, so we should keep it.
    // swiftlint:disable:next implicitly_unwrapped_optional
    open func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if let errorURL = bridge?.config.errorPathURL {
            webView.load(URLRequest(url: errorURL))
        }

        CAPLog.print("⚡️  WebView failed provisional navigation")
        CAPLog.print("⚡️  Error: " + error.localizedDescription)
    }

    open func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        CAPLog.print("⚡️  WebView process terminated")
        bridge?.reset()
        webView.reload()
    }

    open func webView(_ webView: WKWebView, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping @MainActor (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard let bridge = bridge else {
            completionHandler(.rejectProtectionSpace, nil)
            return
        }

        for pluginObject in bridge.plugins {
            let plugin = pluginObject.value
            if plugin.handleWKWebViewURLAuthenticationChallenge(challenge, completionHandler: completionHandler) {
                return
            }
        }

        completionHandler(.rejectProtectionSpace, nil)
        return
    }

    // MARK: - WKScriptMessageHandler

    open func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let bridge = bridge else {
            return
        }

        let body = message.body
        if let dict = body as? [String: Any] {
            let type = dict["type"] as? String ?? ""

            if type == "js.error" {
                if let error = dict["error"] as? [String: Any] {
                    logJSError(error)
                }
            } else if type == "message" {
                let pluginId = dict["pluginId"] as? String ?? ""
                let method = dict["methodName"] as? String ?? ""
                let callbackId = dict["callbackId"] as? String ?? ""

                let options = dict["options"] as? [String: Any] ?? [:]

                if pluginId != "Console" {
                    CAPLog.print("⚡️  To Native -> ", pluginId, method, callbackId)
                }

                bridge.handleJSCall(call: JSCall(options: options, pluginId: pluginId, method: method, callbackId: callbackId))
            }
        }
    }

    // MARK: - WKUIDelegate

    open func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        let complete = PanelCompletion<Void> { _ in completionHandler() }
        let alertController = UIAlertController(title: nil, message: message, preferredStyle: .alert)

        alertController.addAction(UIAlertAction(title: "Ok", style: .default, handler: { (_) in
            complete(())
        }))

        if !presentJavaScriptPanel(alertController) {
            complete(())
        }
    }

    open func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let complete = PanelCompletion(completionHandler)
        let alertController = UIAlertController(title: nil, message: message, preferredStyle: .alert)

        alertController.addAction(UIAlertAction(title: "Cancel", style: .default, handler: { (_) in
            complete(false)
        }))

        alertController.addAction(UIAlertAction(title: "Ok", style: .default, handler: { (_) in
            complete(true)
        }))

        if !presentJavaScriptPanel(alertController) {
            complete(false)
        }
    }

    open func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) {

        // Check if this is a synchronous cookie or http call; anything else falls through to a real prompt.
        if let config = bridge?.config,
           let dataFromString = prompt.data(using: .utf8, allowLossyConversion: false),
           let payload = try? JSONSerialization.jsonObject(with: dataFromString, options: .fragmentsAllowed) as? [String: Any],
           let type = payload["type"] as? String {
            switch type {
            case "CapacitorCookies.get":
                completionHandler(CapacitorCookieManager(config).getCookies())
                // Don't present prompt
                return
            case "CapacitorCookies.set":
                if let action = payload["action"] as? String, let domain = payload["domain"] as? String {
                    CapacitorCookieManager(config).setCookie(domain, action)
                }
                completionHandler("")
                // Don't present prompt
                return
            case "CapacitorCookies.isEnabled":
                completionHandler(String(config.getPluginConfig("CapacitorCookies").getBoolean("enabled", false)))
                // Don't present prompt
                return
            case "CapacitorHttp":
                completionHandler(String(config.getPluginConfig("CapacitorHttp").getBoolean("enabled", false)))
                // Don't present prompt
                return
            default:
                break
            }
        }

        let complete = PanelCompletion(completionHandler)
        let alertController = UIAlertController(title: nil, message: prompt, preferredStyle: .alert)

        alertController.addTextField { (textField) in
            textField.text = defaultText
        }

        alertController.addAction(UIAlertAction(title: "Cancel", style: .default, handler: { (_) in
            complete(nil)
        }))

        alertController.addAction(UIAlertAction(title: "Ok", style: .default, handler: { (_) in
            if let text = alertController.textFields?.first?.text {
                complete(text)
            } else {
                complete(defaultText)
            }
        }))

        if !presentJavaScriptPanel(alertController) {
            complete(nil)
        }
    }

    open func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = navigationAction.request.url {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
        return nil
    }

    // MARK: - UIScrollViewDelegate

    // disable zooming in WKWebView ScrollView
    open func scrollViewWillBeginZooming(_ scrollView: UIScrollView, with view: UIView?) {
        scrollView.pinchGestureRecognizer?.isEnabled = false
    }

    // MARK: - Presentation

    /// The view controller at the top of the presentation stack that starts at `viewController`. A presented view
    /// controller that is being dismissed is skipped, because presenting from it would fail.
    static func topmostViewController(from viewController: UIViewController) -> UIViewController {
        var topmost = viewController
        while let presented = topmost.presentedViewController, !presented.isBeingDismissed {
            topmost = presented
        }
        return topmost
    }

    /// Presents a JavaScript panel from the topmost view controller above the bridge's view controller.
    ///
    /// Returns false when the panel could not be presented: there is no view controller, its view is not in a window,
    /// or UIKit refused the presentation. UIKit only logs in those cases, so the caller has to answer WebKit itself,
    /// which keeps the page's script blocked until the completion handler is called.
    private func presentJavaScriptPanel(_ panel: UIAlertController) -> Bool {
        guard let viewController = bridge?.viewController else {
            return false
        }
        let presenter = Self.topmostViewController(from: viewController)
        guard presenter.viewIfLoaded?.window != nil else {
            return false
        }
        presenter.present(panel, animated: true, completion: nil)
        return panel.presentingViewController != nil
    }

    // MARK: - Private

    private func logJSError(_ error: [String: Any]) {
        let message = error["message"] ?? "No message"
        let url = error["url"] as? String ?? ""
        let line = error["line"] ?? ""
        let col = error["col"] ?? ""
        var filename = ""
        if let filenameIndex = url.range(of: "/", options: .backwards)?.lowerBound {
            let index = url.index(after: filenameIndex)
            filename = String(url[index...])
        }

        CAPLog.print("\n⚡️  ------ STARTUP JS ERROR ------\n")
        CAPLog.print("⚡️  \(message)")
        CAPLog.print("⚡️  URL: \(url)")
        CAPLog.print("⚡️  \(filename):\(line):\(col)")
        CAPLog.print("\n⚡️  See above for help with debugging blank-screen issues")
    }
}

/// Calls a WebKit panel completion handler at most once. WebKit raises an exception when one is called twice, which
/// could otherwise happen if a panel that was reported as not presented shows up after all.
private final class PanelCompletion<Answer> {
    private var handler: ((Answer) -> Void)?

    init(_ handler: @escaping (Answer) -> Void) {
        self.handler = handler
    }

    func callAsFunction(_ answer: Answer) {
        let handler = self.handler
        self.handler = nil
        handler?(answer)
    }
}
