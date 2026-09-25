import WebKit
import XCTest

@testable import Capacitor

private class NamedPlugin: CAPPlugin, CAPBridgedPlugin {
    let identifier: String
    let jsName: String
    let pluginMethods: [CAPPluginMethod] = []
    /// What the plugin answers to shouldOverrideLoad.
    let overrideLoad: Bool?

    init(_ name: String, overrideLoad: Bool? = nil) {
        identifier = name
        jsName = name
        self.overrideLoad = overrideLoad
        super.init()
    }

    required init() {
        identifier = "Named"
        jsName = "Named"
        overrideLoad = nil
        super.init()
    }

    override func shouldOverrideLoad(_ navigationAction: WKNavigationAction) -> Bool? {
        overrideLoad
    }
}

private class ExternalNavigationAction: WKNavigationAction {
    override var request: URLRequest { URLRequest(url: URL(string: "https://example.com/")!) }
    override var targetFrame: WKFrameInfo? { nil }
}

class PluginRegistryTests: XCTestCase {
    func testKeepsRegistrationOrder() {
        let registry = PluginRegistry()
        let names = (0..<50).map { "Plugin\($0)" }.shuffled()
        for name in names {
            XCTAssertNil(registry.register(NamedPlugin(name)))
        }
        XCTAssertEqual(registry.all.map(\.jsName), names)
        XCTAssertEqual(registry[names[7]]?.jsName, names[7])
        XCTAssertNil(registry["missing"])
    }

    func testAReplacementTakesThePlaceOfThePluginItReplaces() {
        let registry = PluginRegistry()
        let first = NamedPlugin("A")
        registry.register(first)
        registry.register(NamedPlugin("B"))
        let replacement = NamedPlugin("A")
        XCTAssertTrue(registry.register(replacement) === first)
        XCTAssertEqual(registry.all.map(\.jsName), ["A", "B"])
        XCTAssertTrue(registry["A"] === replacement)
    }

    func testConcurrentAccess() {
        let registry = PluginRegistry()
        DispatchQueue.concurrentPerform(iterations: 2000) { index in
            let name = "Plugin\(index % 25)"
            switch index % 3 {
            case 0:
                registry.register(NamedPlugin(name))
            case 1:
                _ = registry[name]
            default:
                _ = registry.all.count
            }
        }
        XCTAssertEqual(Set(registry.all.map(\.jsName)).count, 25)
        XCTAssertEqual(registry.all.count, 25)
    }

    private func policy(_ plugins: [NamedPlugin]) -> WKNavigationActionPolicy? {
        let handler = WebViewDelegationHandler()
        let delegate = TestBridgeDelegate()
        let bridge = RecordingBridge(delegate: delegate, delegationHandler: handler)
        plugins.forEach(bridge.registerPluginInstance)
        var decision: WKNavigationActionPolicy?
        handler.webView(WKWebView(), decidePolicyFor: ExternalNavigationAction()) { decision = $0 }
        return decision
    }

    func testTheFirstRegisteredPluginDecidesNavigation() {
        XCTAssertEqual(policy([NamedPlugin("Silent"), NamedPlugin("Blocks", overrideLoad: true), NamedPlugin("Allows", overrideLoad: false)]), .cancel)
        XCTAssertEqual(policy([NamedPlugin("Allows", overrideLoad: false), NamedPlugin("Blocks", overrideLoad: true)]), .allow)
    }

    func testBridgeLooksPluginsUpByJavaScriptName() {
        let bridge = RecordingBridge(delegate: TestBridgeDelegate())
        let plugin = NamedPlugin("Lookup")
        bridge.registerPluginInstance(plugin)
        XCTAssertTrue(bridge.plugin(withName: "Lookup") === plugin)
        XCTAssertTrue(bridge.pluginRegistry.all.contains { $0 === plugin })
    }
}
