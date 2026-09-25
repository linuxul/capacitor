import WebKit
import XCTest

@testable import Capacitor

/// The selector-based registration of fork 8.5.3, which 9.0 keeps but deprecates. Tests reach it through this protocol,
/// whose requirements are not deprecated, so that testing it does not add deprecation warnings.
protocol SelectorRegisteredMethod {
    init(name: String, returnType: CAPPluginMethod.ReturnType)
    init(_ selector: Selector, returnType: CAPPluginMethod.ReturnType)
    var selector: Selector? { get }
}

extension CAPPluginMethod: SelectorRegisteredMethod {}

/// A method registered by selector, the way plugins of fork 8.5.3 register theirs.
func selectorMethod(_ name: String, _ returnType: CAPPluginMethod.ReturnType = .promise) -> CAPPluginMethod {
    func make<Method: SelectorRegisteredMethod>() -> Method {
        Method(name: name, returnType: returnType)
    }
    return make()
}

func selectorMethod(_ selector: Selector, _ returnType: CAPPluginMethod.ReturnType = .promise) -> CAPPluginMethod {
    func make<Method: SelectorRegisteredMethod>() -> Method {
        Method(selector, returnType: returnType)
    }
    return make()
}

/// The selector of a method registered by selector, nil for one registered by reference.
func registeredSelector(_ method: CAPPluginMethod) -> Selector? {
    func read<Method: SelectorRegisteredMethod>(_ method: Method) -> Selector? {
        method.selector
    }
    return read(method)
}

private struct PluginMethodFailure: LocalizedError {
    var errorDescription: String? { "the method failed" }
}

/// Registers its methods by reference; none of them is visible to the Obj-C runtime.
@objc(CAPReferencePlugin)
private final class ReferencePlugin: CAPPlugin, CAPBridgedPlugin {
    let identifier = "CAPReferencePlugin"
    let jsName = "Reference"
    let pluginMethods: [CAPPluginMethod] = [
        .promise("echo", ReferencePlugin.echo),
        .promise("fail", ReferencePlugin.fail),
        .promise("resolveThenFail", ReferencePlugin.resolveThenFail),
        .callback("watch", ReferencePlugin.watch),
        .callback("watchThenFail", ReferencePlugin.watchThenFail),
        .none("fire", ReferencePlugin.fire),
        .promise("foreign", OtherPlugin.foreign),
        selectorMethod("legacy"),
        selectorMethod(#selector(ReferencePlugin.legacyBySelector(_:)), .callback)
    ]

    private let lock = NSLock()
    private var queueLabels: [String] = []
    private var fired = 0

    var labels: [String] { lock.withLock { queueLabels } }
    var fireCount: Int { lock.withLock { fired } }

    private func record() {
        let label = String(cString: __dispatch_queue_get_label(nil))
        lock.withLock { queueLabels.append(label) }
    }

    private func echo(_ call: CAPPluginCall) {
        record()
        call.resolve(["value": call.getString("value") ?? ""])
    }

    private func fail(_ call: CAPPluginCall) throws {
        record()
        throw PluginMethodFailure()
    }

    private func resolveThenFail(_ call: CAPPluginCall) throws {
        call.resolve(["value": "resolved"])
        throw PluginMethodFailure()
    }

    private func watch(_ call: CAPPluginCall) {
        call.keepAlive = true
    }

    private func watchThenFail(_ call: CAPPluginCall) throws {
        call.keepAlive = true
        throw PluginMethodFailure()
    }

    private func fire(_ call: CAPPluginCall) {
        lock.withLock { fired += 1 }
    }

    @objc func legacy(_ call: CAPPluginCall) {
        call.resolve(["value": "legacy"])
    }

    @objc func legacyBySelector(_ call: CAPPluginCall) {
        call.keepAlive = true
        call.resolve(["value": "bySelector"])
    }
}

private final class OtherPlugin: CAPPlugin {
    func foreign(_ call: CAPPluginCall) {
        call.resolve()
    }
}

class PluginMethodTests: XCTestCase {
    private var bridge: RecordingBridge!
    private var plugin: ReferencePlugin!

    override func setUp() {
        super.setUp()
        bridge = RecordingBridge(delegate: TestBridgeDelegate())
        plugin = ReferencePlugin()
        bridge.registerPluginInstance(plugin)
    }

    override func tearDown() {
        bridge = nil
        plugin = nil
        super.tearDown()
    }

    /// Sends `method` and waits for the bridge queue to run it. Returns what was sent back for the call, if anything.
    private func send(_ method: String, _ options: [String: Any] = [:], expectingResult: Bool = true) -> (callbackId: String, message: RecordingBridge.Message?) {
        let callbackId = UUID().uuidString
        let sent = expectation(description: "result sent")
        sent.isInverted = !expectingResult
        bridge.onMessage = { if $0.callbackId == callbackId { sent.fulfill() } }
        bridge.handleJSCall(call: JSCall(options: options, pluginId: "Reference", method: method, callbackId: callbackId))
        wait(for: [sent], timeout: expectingResult ? 20 : 0.5)
        bridge.dispatchQueue.sync {}
        return (callbackId, bridge.messages.last { $0.callbackId == callbackId })
    }

    func testTheFactoriesDescribeTheMethod() {
        let methods = plugin.pluginMethods
        XCTAssertEqual(methods.map(\.name), ["echo", "fail", "resolveThenFail", "watch", "watchThenFail", "fire", "foreign", "legacy", "legacyBySelector"])
        XCTAssertEqual(methods.map(\.returnType), [.promise, .promise, .promise, .callback, .callback, .none, .promise, .promise, .callback])
        XCTAssertEqual(methods.map { registeredSelector($0).map(NSStringFromSelector) }, [nil, nil, nil, nil, nil, nil, nil, "legacy:", "legacyBySelector:"])
    }

    func testMethodsRegisteredByReferenceNeedNoObjC() {
        XCTAssertFalse(plugin.responds(to: NSSelectorFromString("echo:")), "the fixture must not expose echo to the Obj-C runtime")
        let message = send("echo", ["value": "hi"]).message
        XCTAssertEqual(message?.success, true)
        XCTAssertEqual(message?.payload, #"{"value":"hi"}"#)
    }

    func testMethodsRunOnTheBridgeQueue() {
        _ = send("echo")
        _ = send("fail")
        XCTAssertEqual(plugin.labels, ["bridge", "bridge"])
    }

    func testAThrownErrorRejectsTheCall() throws {
        let message = try XCTUnwrap(send("fail").message)
        XCTAssertFalse(message.success)
        XCTAssertFalse(message.save)
        XCTAssertTrue(message.payload.contains(#""message":"the method failed""#), message.payload)
    }

    func testAnErrorThrownAfterTheCallSettledIsDropped() {
        let (callbackId, message) = send("resolveThenFail")
        XCTAssertEqual(message?.success, true)
        XCTAssertEqual(bridge.messages.filter { $0.callbackId == callbackId }.count, 1)
    }

    func testACallbackMethodThatKeepsItsCallIsSaved() {
        let callbackId = send("watch", expectingResult: false).callbackId
        XCTAssertNotNil(bridge.savedCall(withID: callbackId))
    }

    func testAMethodThatThrowsIsNotSaved() {
        let (callbackId, message) = send("watchThenFail")
        XCTAssertEqual(message?.success, false)
        XCTAssertEqual(message?.save, true, "the call was kept alive when it was rejected")
        XCTAssertNil(bridge.savedCall(withID: callbackId))
    }

    func testANoneMethodIsCalled() {
        _ = send("fire", expectingResult: false)
        XCTAssertEqual(plugin.fireCount, 1)
    }

    func testAMethodOfAnotherPluginTypeIsRejected() throws {
        let message = try XCTUnwrap(send("foreign").message)
        XCTAssertFalse(message.success)
        XCTAssertTrue(message.payload.contains(#""code":"UNIMPLEMENTED""#), message.payload)
    }

    func testMethodsRegisteredBySelectorAreStillPerformed() {
        XCTAssertEqual(send("legacy").message?.payload, #"{"value":"legacy"}"#)
        let bySelector = send("legacyBySelector")
        XCTAssertEqual(bySelector.message?.payload, #"{"value":"bySelector"}"#)
        XCTAssertNotNil(bridge.savedCall(withID: bySelector.callbackId))
    }

    func testTheExportedJavaScriptUsesTheReturnTypes() throws {
        let contentController = WKUserContentController()
        JSExport.exportJS(for: plugin, in: contentController)
        let source = contentController.userScripts.map(\.source).joined(separator: "\n")
        XCTAssertTrue(source.contains(#"t["echo"] = function(_options) {"# + "\n" + #"return w.Capacitor.nativePromise("Reference", "echo", _options);"#), source)
        XCTAssertTrue(source.contains(#"t["watch"] = function(_options, _callback) {"#), source)
        XCTAssertTrue(source.contains(#"return w.Capacitor.nativeCallback("Reference", "fire", _options);"#), source)

        let header = try XCTUnwrap(source.components(separatedBy: "h.push(").last?.components(separatedBy: ");").first)
        let methods = try JSONDecoder().decode(PluginHeader.self, from: Data(header.utf8)).methods
        XCTAssertEqual(methods.first { $0.name == "watch" }?.rtype, "callback")
        XCTAssertEqual(methods.first { $0.name == "echo" }?.rtype, "promise")
        XCTAssertNil(methods.first { $0.name == "fire" }?.rtype)
    }
}
