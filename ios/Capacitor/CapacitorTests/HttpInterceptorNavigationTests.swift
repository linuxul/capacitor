import WebKit
import XCTest

@testable import Capacitor

private class StubFrameInfo: WKFrameInfo {
    override var isMainFrame: Bool { false }
}

private class StubNavigationAction: WKNavigationAction {
    private let stubbedRequest: URLRequest
    private let stubbedTargetFrame: WKFrameInfo?
    init(url: String, subframe: Bool = false) {
        self.stubbedRequest = URLRequest(url: URL(string: url)!)
        if subframe {
            // A WKFrameInfo created outside of WebKit has no backing frame, and on newer WebKit
            // versions its dealloc traps (CFRetain of NULL). Leak the stub so dealloc never runs.
            let frame = StubFrameInfo()
            _ = Unmanaged.passRetained(frame)
            self.stubbedTargetFrame = frame
        } else {
            self.stubbedTargetFrame = nil
        }
        super.init()
    }
    override var request: URLRequest { stubbedRequest }
    override var targetFrame: WKFrameInfo? { stubbedTargetFrame }
}

// The proxy path shares the app's origin, so the origin guard alone would allow it.
class HttpInterceptorNavigationTests: XCTestCase {
    // WebViewDelegationHandler holds the bridge weakly and decidePolicyFor allows everything when
    // it is nil, so this strong reference is what makes the tests below reach the interceptor guard.
    private var bridge: MockBridge!
    private var handler: WebViewDelegationHandler!
    private let webView = WKWebView()
    private let interceptorURL = "capacitor://localhost\(CapacitorBridge.httpInterceptorStartIdentifier)?u=https://example.com/payload.html"

    override func setUp() {
        super.setUp()
        let descriptor = InstanceDescriptor()
        handler = WebViewDelegationHandler()
        bridge = MockBridge(
            with: InstanceConfiguration(with: descriptor, isDebug: true),
            delegate: MockBridgeViewController(),
            assetHandler: MockAssetHandler(router: CapacitorRouter()),
            delegationHandler: handler
        )
    }

    private func policy(for url: String, subframe: Bool = false) -> WKNavigationActionPolicy {
        var decision: WKNavigationActionPolicy?
        handler.webView(webView, decidePolicyFor: StubNavigationAction(url: url, subframe: subframe)) { decision = $0 }
        return decision!
    }

    func testBlocksNavigationToInterceptorPath() {
        XCTAssertEqual(policy(for: interceptorURL), .cancel)
    }

    // decidePolicyFor also fires for subframes, and a same-origin iframe gets the bridge too.
    func testBlocksSubframeNavigationToInterceptorPath() {
        XCTAssertEqual(policy(for: interceptorURL, subframe: true), .cancel)
    }

    func testAllowsInAppNavigation() {
        XCTAssertEqual(policy(for: "capacitor://localhost/index.html"), .allow)
    }
}
