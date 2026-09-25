import UIKit
import WebKit
import XCTest

@testable import Capacitor

/// Hosts a bridge without loading a web view.
final class TestBridgeDelegate: CAPBridgeDelegate {
    var bridgedWebView: WKWebView?
    var bridgedViewController: UIViewController?

    init(viewController: UIViewController? = nil) {
        bridgedViewController = viewController
    }
}

/// A call and how it settled.
final class RecordedCall {
    private(set) var resolutions: [PluginCallResultData?] = []
    private(set) var rejections: [CAPPluginCallError] = []
    private(set) var threads: [Bool] = []
    private var expectation: XCTestExpectation?
    private(set) lazy var call = CAPPluginCall(callbackId: UUID().uuidString, methodName: "test", options: options, success: { [weak self] result, _ in
        self?.threads.append(Thread.isMainThread)
        self?.resolutions.append(result.data)
        self?.expectation?.fulfill()
    }, error: { [weak self] error in
        self?.threads.append(Thread.isMainThread)
        self?.rejections.append(error)
        self?.expectation?.fulfill()
    })
    private let options: JSObject

    init(_ options: JSObject = [:], expectation: XCTestExpectation? = nil) {
        self.options = options
        self.expectation = expectation
    }

    var settleCount: Int {
        resolutions.count + rejections.count
    }
}

class BuiltInPluginTests: XCTestCase {
    private func settle(_ options: JSObject = [:], _ invoke: (CAPPluginCall) -> Void) -> RecordedCall {
        let settled = expectation(description: "call settled")
        let recorded = RecordedCall(options, expectation: settled)
        invoke(recorded.call)
        wait(for: [settled], timeout: 2)
        return recorded
    }

    private func makeBridge(delegate: CAPBridgeDelegate) -> MockBridge {
        MockBridge(with: InstanceConfiguration(with: InstanceDescriptor(), isDebug: true), delegate: delegate,
                   assetHandler: MockAssetHandler(router: CapacitorRouter()), delegationHandler: WebViewDelegationHandler())
    }

    // MARK: - WebView

    func testWebViewMethodsRejectWithoutABridgeViewController() {
        let plugin = CAPWebViewPlugin()
        let calls: [(JSObject, (CAPPluginCall) -> Void)] = [
            (["path": "www"], plugin.setServerAssetPath),
            (["path": "/tmp"], plugin.setServerBasePath),
            ([:], plugin.getServerBasePath),
            ([:], plugin.persistServerBasePath)
        ]
        for (options, method) in calls {
            let recorded = settle(options, method)
            XCTAssertEqual(recorded.rejections.count, 1)
            XCTAssertEqual(recorded.threads, [true], "UIKit is only touched on the main thread")
        }
    }

    func testWebViewPathSettersRejectAMissingPath() {
        let plugin = CAPWebViewPlugin()
        XCTAssertEqual(settle([:], plugin.setServerBasePath).rejections.first?.message, "Must provide a path")
        XCTAssertEqual(settle([:], plugin.setServerAssetPath).rejections.first?.message, "Must provide a path")
    }

    func testWebViewMethodsResolveOnTheMainThreadWithABridgeViewController() {
        let delegate = TestBridgeDelegate(viewController: MockBridgeViewController())
        let bridge = makeBridge(delegate: delegate)
        let plugin = CAPWebViewPlugin()
        plugin.bridge = bridge

        let get = settle { call in DispatchQueue.global().async { plugin.getServerBasePath(call) } }
        XCTAssertEqual(get.resolutions.count, 1)
        XCTAssertEqual(get.threads, [true])
        XCTAssertNotNil(get.resolutions.first??["path"] as? String)

        let set = settle(["path": NSTemporaryDirectory()]) { call in DispatchQueue.global().async { plugin.setServerBasePath(call) } }
        XCTAssertEqual(set.resolutions.count, 1)
        XCTAssertEqual(set.threads, [true])
    }

    // MARK: - CapacitorCookies

    func testCookieMethodsRejectBeforeThePluginIsLoaded() {
        let plugin = CAPCookiesPlugin()
        let methods: [(CAPPluginCall) -> Void] = [plugin.getCookies, plugin.setCookie, plugin.deleteCookie, plugin.clearCookies, plugin.clearAllCookies]
        for method in methods {
            let recorded = settle(["key": "k", "value": "v", "url": "https://example.com"], method)
            XCTAssertEqual(recorded.rejections.first?.message, "The CapacitorCookies plugin is not loaded")
        }
    }

    func testClearCookiesRejectsWithoutAServerUrl() {
        let plugin = CAPCookiesPlugin()
        // no bridge, so there is no server URL to fall back to
        plugin.load()
        XCTAssertEqual(settle([:], plugin.clearCookies).rejections.first?.message, "Invalid URL / Server URL")
    }

    func testClearCookiesResolves() {
        let plugin = CAPCookiesPlugin()
        plugin.load()
        let recorded = settle(["url": "https://capacitor-cookie-test.invalid"], plugin.clearCookies)
        XCTAssertEqual(recorded.resolutions.count, 1)
    }
}
