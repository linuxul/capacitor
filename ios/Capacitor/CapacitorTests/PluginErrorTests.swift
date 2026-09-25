import XCTest

@testable import Capacitor

private struct Underlying: Error {}

@objc(CAPErrorTestPlugin)
private final class ErrorTestPlugin: CAPPlugin, CAPBridgedPlugin {
    let identifier = "CAPErrorTestPlugin"
    let jsName = "ErrorTest"
    let pluginMethods: [CAPPluginMethod] = [
        .promise("pluginError", ErrorTestPlugin.pluginError),
        .promise("unimplemented", ErrorTestPlugin.unimplemented),
        .callback("unavailable", ErrorTestPlugin.unavailable),
        .promise("nsError", ErrorTestPlugin.nsError),
        .promise("cancellation", ErrorTestPlugin.cancellation),
        .async("asyncPluginError", ErrorTestPlugin.asyncPluginError)
    ]

    private func pluginError(_ call: CAPPluginCall) throws {
        throw CAPPluginError("Denied", code: "DENIED", data: ["reason": "user"], underlyingError: Underlying())
    }

    private func unimplemented(_ call: CAPPluginCall) throws {
        throw CAPPluginError.unimplemented()
    }

    private func unavailable(_ call: CAPPluginCall) throws {
        throw CAPPluginError.unavailable("No camera")
    }

    private func nsError(_ call: CAPPluginCall) throws {
        throw NSError(domain: "Test", code: 7, userInfo: [NSLocalizedDescriptionKey: "Described"])
    }

    private func cancellation(_ call: CAPPluginCall) throws {
        throw CancellationError()
    }

    private func asyncPluginError(_ call: CAPPluginCall) async throws -> JSObject {
        throw CAPPluginError("Async denied", code: "DENIED")
    }
}

class PluginErrorTests: XCTestCase {
    /// Sends `method` to a new bridge and returns the error JavaScript receives.
    private func rejection(_ method: String, file: StaticString = #filePath, line: UInt = #line) throws -> [String: Any] {
        let bridge = RecordingBridge(delegate: TestBridgeDelegate())
        bridge.registerPluginInstance(ErrorTestPlugin())
        let callbackId = UUID().uuidString
        let sent = expectation(description: "\(method) rejected")
        bridge.onMessage = { if $0.callbackId == callbackId { sent.fulfill() } }
        bridge.handleJSCall(call: JSCall(options: [:], pluginId: "ErrorTest", method: method, callbackId: callbackId))
        wait(for: [sent], timeout: 20)
        let message = try XCTUnwrap(bridge.messages.first { $0.callbackId == callbackId }, file: file, line: line)
        XCTAssertFalse(message.success, file: file, line: line)
        let payload = try JSONSerialization.jsonObject(with: Data(message.payload.utf8))
        return try XCTUnwrap(payload as? [String: Any], file: file, line: line)
    }

    func testAThrownPluginErrorRejectsWithItsMessageCodeAndData() throws {
        let error = try rejection("pluginError")
        XCTAssertEqual(error["message"] as? String, "Denied")
        XCTAssertEqual(error["code"] as? String, "DENIED")
        XCTAssertEqual((error["data"] as? [String: Any])?["reason"] as? String, "user")
    }

    func testUnimplementedAndUnavailableErrorsCarryTheirCodes() throws {
        let unimplemented = try rejection("unimplemented")
        XCTAssertEqual(unimplemented["message"] as? String, "not implemented")
        XCTAssertEqual(unimplemented["code"] as? String, "UNIMPLEMENTED")

        let unavailable = try rejection("unavailable")
        XCTAssertEqual(unavailable["message"] as? String, "No camera")
        XCTAssertEqual(unavailable["code"] as? String, "UNAVAILABLE")
    }

    func testOtherErrorsRejectWithTheirDescription() throws {
        let error = try rejection("nsError")
        XCTAssertEqual(error["message"] as? String, "Described")
        XCTAssertNil(error["code"])
    }

    func testACancellationErrorRejectsAsACancelledCall() throws {
        XCTAssertEqual(try rejection("cancellation")["message"] as? String, "The plugin call was cancelled")
    }

    func testAnAsyncMethodThatThrowsAPluginErrorRejectsWithIt() throws {
        let error = try rejection("asyncPluginError")
        XCTAssertEqual(error["message"] as? String, "Async denied")
        XCTAssertEqual(error["code"] as? String, "DENIED")
    }

    func testRejectWithAnErrorSettlesTheCallOnce() {
        let recorded = RecordedCall()
        recorded.call.reject(CAPPluginError("First", code: "ONE", underlyingError: Underlying()))
        recorded.call.reject(CAPPluginError("Second"))
        recorded.call.resolve()
        XCTAssertEqual(recorded.settleCount, 1)
        let error = recorded.rejections.first
        XCTAssertEqual(error?.message, "First")
        XCTAssertEqual(error?.code, "ONE")
        XCTAssertTrue(error?.error is Underlying, "the underlying error is kept for diagnostics")
    }

    func testAPluginErrorDescribesItselfWithItsMessage() {
        XCTAssertEqual(CAPPluginError("Readable").localizedDescription, "Readable")
        XCTAssertEqual(CAPPluginError.unavailable().message, "not available")
    }
}
