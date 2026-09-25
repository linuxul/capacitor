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

// The classes below are looked up by name. The test bundle subclasses CAPPlugin across the framework's library
// evolution boundary, so the Swift compiler emits them as Obj-C class stubs, which NSClassFromString only finds once
// they have been realized; the tests realize them first (see `lookUp`). Plugins built with SwiftPM are ordinary classes.

/// Not listed for registration, but its class name is its JavaScript name, so the bridge loads it on first use.
@objc(LazyEcho)
class LazyEchoPlugin: CAPPlugin, CAPBridgedPlugin {
    static var instances = 0
    let identifier = "LazyEcho"
    let jsName = "LazyEcho"
    let pluginMethods: [CAPPluginMethod] = [.promise("echo", LazyEchoPlugin.echo)]

    override func load() {
        LazyEchoPlugin.instances += 1
    }

    func echo(_ call: CAPPluginCall) {
        call.resolve(["value": call.getString("value") ?? ""])
    }
}

/// Registered under a JavaScript name that differs from its class name.
@objc(CAPRegistryIdentifierPlugin)
class RegistryIdentifierPlugin: CAPPlugin, CAPBridgedPlugin {
    let identifier = "CAPRegistryIdentifierPlugin"
    let jsName = "RegistryIdentifier"
    let pluginMethods: [CAPPluginMethod] = [.promise("echo", RegistryIdentifierPlugin.echo)]

    func echo(_ call: CAPPluginCall) {
        call.resolve()
    }
}

/// Not registered, and its JavaScript name differs from its class name.
@objc(CAPRegistryMismatchPlugin)
class RegistryMismatchPlugin: CAPPlugin, CAPBridgedPlugin {
    let identifier = "CAPRegistryMismatchPlugin"
    let jsName = "RegistryMismatch"
    let pluginMethods: [CAPPluginMethod] = [.promise("echo", RegistryMismatchPlugin.echo)]

    func echo(_ call: CAPPluginCall) {
        call.resolve()
    }
}

/// Instance plugins are registered by the app, never created by the bridge.
@objc(CAPRegistryInstancePlugin)
class RegistryInstancePlugin: CAPInstancePlugin, CAPBridgedPlugin {
    let identifier = "CAPRegistryInstancePlugin"
    let jsName = "CAPRegistryInstancePlugin"
    let pluginMethods: [CAPPluginMethod] = [.promise("echo", RegistryInstancePlugin.echo)]

    func echo(_ call: CAPPluginCall) {
        call.resolve()
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

    // MARK: - Lazy loading

    /// Realizes `type` and checks that the Obj-C runtime finds it under `name`, so a rejection is not just a failed lookup.
    private func lookUp(_ type: AnyClass, as name: String) {
        _ = type.description()
        XCTAssertTrue(NSClassFromString(name) === type, "\(name) must be visible to the Obj-C runtime")
    }

    private func send(_ pluginId: String, _ method: String = "echo", to bridge: RecordingBridge) -> RecordingBridge.Message? {
        let callbackId = UUID().uuidString
        let sent = expectation(description: "result sent")
        bridge.onMessage = { if $0.callbackId == callbackId { sent.fulfill() } }
        bridge.handleJSCall(call: JSCall(options: ["value": "hi"], pluginId: pluginId, method: method, callbackId: callbackId))
        wait(for: [sent], timeout: 20)
        return bridge.messages.last { $0.callbackId == callbackId }
    }

    func testLoadsAnUnlistedPluginWhoseClassNameIsItsJavaScriptName() {
        lookUp(LazyEchoPlugin.self, as: "LazyEcho")
        let bridge = RecordingBridge(delegate: TestBridgeDelegate())
        let loadedBefore = LazyEchoPlugin.instances
        XCTAssertEqual(send("LazyEcho", to: bridge)?.payload, #"{"value":"hi"}"#)
        let plugin = bridge.plugin(withName: "LazyEcho")
        XCTAssertNotNil(plugin)
        XCTAssertEqual(send("LazyEcho", to: bridge)?.success, true)
        XCTAssertTrue(bridge.plugin(withName: "LazyEcho") === plugin, "the loaded instance is reused")
        XCTAssertEqual(LazyEchoPlugin.instances, loadedBefore + 1)
    }

    func testDoesNotReplaceARegisteredPluginCalledByItsClassName() {
        let bridge = RecordingBridge(delegate: TestBridgeDelegate())
        let registered = RegistryIdentifierPlugin()
        bridge.registerPluginInstance(registered)
        lookUp(RegistryIdentifierPlugin.self, as: "CAPRegistryIdentifierPlugin")

        let message = send("CAPRegistryIdentifierPlugin", to: bridge)
        XCTAssertEqual(message?.success, false)
        XCTAssertTrue(bridge.plugin(withName: "RegistryIdentifier") === registered)
        XCTAssertNil(bridge.plugin(withName: "CAPRegistryIdentifierPlugin"))
        XCTAssertEqual(bridge.pluginRegistry.all.count, 1)
    }

    func testDoesNotLoadPluginsWhoseJavaScriptNameDiffers() {
        lookUp(RegistryMismatchPlugin.self, as: "CAPRegistryMismatchPlugin")
        let bridge = RecordingBridge(delegate: TestBridgeDelegate())
        XCTAssertEqual(send("CAPRegistryMismatchPlugin", to: bridge)?.success, false)
        XCTAssertTrue(bridge.pluginRegistry.all.isEmpty)
    }

    func testDoesNotLoadClassesThatAreNotBridgedPlugins() {
        lookUp(RegistryInstancePlugin.self, as: "CAPRegistryInstancePlugin")
        let bridge = RecordingBridge(delegate: TestBridgeDelegate())
        for name in ["NSObject", "UIView", "CAPPlugin", "CAPRegistryInstancePlugin", "NoSuchClass"] {
            XCTAssertEqual(send(name, to: bridge)?.success, false, name)
        }
        XCTAssertTrue(bridge.pluginRegistry.all.isEmpty)
    }
}


