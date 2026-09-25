import JavaScriptCore
import WebKit
import XCTest

@testable import Capacitor

@objc(CAPBridgeMessageTestPlugin)
private class MessageTestPlugin: CAPPlugin, CAPBridgedPlugin {
    let identifier = "CAPBridgeMessageTestPlugin"
    let jsName = "MessageTest"
    let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "fail", returnType: .promise),
        CAPPluginMethod(name: "failKeptAlive", returnType: .callback)
    ]

    @objc func fail(_ call: CAPPluginCall) {
        call.reject("failed")
    }

    @objc func failKeptAlive(_ call: CAPPluginCall) {
        call.keepAlive = true
        call.reject("failed")
    }
}

class BridgeMessageTests: XCTestCase {
    /// Strings that end or break a JavaScript string literal when interpolated between quotes.
    private let hostile = "it's \"quoted\"\nnext line\r\u{2028}\u{2029}</script>\\ '); throw new Error('injected'); ('"

    private let call = { (callbackId: String, pluginId: String, method: String) in
        JSCall(options: [:], pluginId: pluginId, method: method, callbackId: callbackId)
    }

    /// Evaluates `script` against a stub `window.Capacitor` and returns what the stub received.
    private func evaluate(_ script: String) throws -> [[String: Any]] {
        let context = try XCTUnwrap(JSContext())
        var exception: String?
        context.exceptionHandler = { _, value in exception = value?.toString() }
        context.evaluateScript("""
        var received = [];
        var console = { error: function (message) { received.push({ consoleError: message }); } };
        var window = { Capacitor: {
            fromNative: function (result) { received.push(result); },
            triggerEvent: function (eventName, target, data) { received.push({ eventName: eventName, target: target, data: data }); },
            logJs: function (message, level) { received.push({ message: message, level: level }); },
            withPlugin: function (pluginId, fn) { received.push({ pluginId: pluginId }); fn(null); }
        } };
        """)
        context.evaluateScript(script)
        XCTAssertNil(exception, script)
        let received = context.objectForKeyedSubscript("received")?.toArray() as? [[String: Any]]
        return try XCTUnwrap(received)
    }

    func testFromNativeKeepsHostileIdentifiersIntact() throws {
        let result = JSResult(call: call(hostile, hostile, hostile), result: .dictionary(["value": hostile]))
        let script = BridgeScript.fromNative(result, success: true, save: true, payload: result.jsonPayload())
        XCTAssertTrue(script.hasPrefix("window.Capacitor.fromNative({"))

        let message = try XCTUnwrap(evaluate(script).first)
        XCTAssertEqual(message["callbackId"] as? String, hostile)
        XCTAssertEqual(message["pluginId"] as? String, hostile)
        XCTAssertEqual(message["methodName"] as? String, hostile)
        XCTAssertEqual(message["save"] as? Bool, true)
        XCTAssertEqual(message["success"] as? Bool, true)
        XCTAssertEqual((message["data"] as? [String: Any])?["value"] as? String, hostile)
    }

    func testFromNativeWithoutDataSendsUndefined() throws {
        let result = JSResult(call: call("id", "Plugin", "method"), result: nil)
        let received = try evaluate(BridgeScript.fromNative(result, success: true, save: false, payload: result.jsonPayload()))
        let message = try XCTUnwrap(received.first)
        XCTAssertEqual(message["save"] as? Bool, false)
        // toArray() drops undefined members, so data must be absent rather than null
        XCTAssertFalse(message.keys.contains("data"))
    }

    func testFromNativeErrorCarriesTheErrorAndSave() throws {
        let error = JSResultError(call: call("id'1", "Plugin", "method"), callError: CAPPluginCallError(message: hostile, code: "E'1", error: nil, data: nil))
        let received = try evaluate(BridgeScript.fromNative(error, success: false, save: false, payload: error.jsonPayload()))
        let message = try XCTUnwrap(received.first)
        XCTAssertEqual(message["callbackId"] as? String, "id'1")
        XCTAssertEqual(message["success"] as? Bool, false)
        XCTAssertEqual(message["save"] as? Bool, false)
        let payload = try XCTUnwrap(message["error"] as? [String: Any])
        XCTAssertEqual(payload["message"] as? String, hostile)
        XCTAssertEqual(payload["code"] as? String, "E'1")
    }

    func testEventLogAndPluginScriptsKeepHostileStringsIntact() throws {
        let received = try evaluate([
            BridgeScript.triggerEvent(hostile, target: hostile),
            BridgeScript.triggerEvent("resume", target: "document", data: #"{"a":1}"#),
            BridgeScript.logJs(hostile, level: hostile),
            BridgeScript.withPlugin(hostile, js: "received.push({ ran: true });")
        ].joined(separator: "\n"))
        XCTAssertEqual(received.count, 6)
        XCTAssertEqual(received[0]["eventName"] as? String, hostile)
        XCTAssertEqual(received[0]["target"] as? String, hostile)
        XCTAssertEqual(received[1]["eventName"] as? String, "resume")
        XCTAssertEqual((received[1]["data"] as? [String: Any])?["a"] as? Int, 1)
        XCTAssertEqual(received[2]["message"] as? String, hostile)
        XCTAssertEqual(received[2]["level"] as? String, hostile)
        XCTAssertEqual(received[3]["pluginId"] as? String, hostile)
        XCTAssertEqual(received[4]["consoleError"] as? String, "Unable to execute JS in plugin, no such plugin found for id \(hostile)")
        XCTAssertEqual(received[5]["ran"] as? Bool, true)
    }

    // MARK: - save on errors

    private func dispatch(_ method: String, on bridge: RecordingBridge) -> RecordingBridge.Message? {
        let sent = expectation(description: "message sent")
        bridge.onMessage = { _ in sent.fulfill() }
        let callbackId = UUID().uuidString
        bridge.handleJSCall(call: JSCall(options: [:], pluginId: "MessageTest", method: method, callbackId: callbackId))
        wait(for: [sent], timeout: 2)
        return bridge.messages.last { $0.callbackId == callbackId }
    }

    func testErrorsTellThePageWhetherToKeepTheCallback() throws {
        let delegate = TestBridgeDelegate()
        let bridge = RecordingBridge(delegate: delegate)
        bridge.registerPluginInstance(MessageTestPlugin())

        let failed = try XCTUnwrap(dispatch("fail", on: bridge))
        XCTAssertFalse(failed.success)
        XCTAssertFalse(failed.save, "a failed call ends, so the page must drop its callback")

        let keptAlive = try XCTUnwrap(dispatch("failKeptAlive", on: bridge))
        XCTAssertFalse(keptAlive.success)
        XCTAssertTrue(keptAlive.save, "a call kept alive continues after an error")
    }

    func testDispatchFailuresDoNotSave() throws {
        let bridge = RecordingBridge(delegate: TestBridgeDelegate())
        let message = try XCTUnwrap(dispatch("missing", on: bridge))
        XCTAssertFalse(message.success)
        XCTAssertFalse(message.save)
    }
}
