import XCTest

@testable import Capacitor

/// `Thread.isMainThread` is unavailable in async code; a synchronous function may still ask.
private func onMainThread() -> Bool {
    Thread.isMainThread
}

private struct Photo: Encodable {
    let path: String
}

private struct AsyncFailure: LocalizedError {
    var errorDescription: String? { "the async method failed" }
}

/// Holds an async method until the test opens it. The method does not react to cancellation while it waits.
private final class Gate {
    private let lock = NSLock()
    private var opened = false
    private var waiting: CheckedContinuation<Void, Never>?

    func wait() async {
        await withCheckedContinuation { continuation in
            let resumeNow: Bool = lock.withLock {
                if opened {
                    return true
                }
                waiting = continuation
                return false
            }
            if resumeNow {
                continuation.resume()
            }
        }
    }

    func open() {
        let continuation: CheckedContinuation<Void, Never>? = lock.withLock {
            opened = true
            defer { waiting = nil }
            return waiting
        }
        continuation?.resume()
    }
}

@objc(CAPAsyncTestPlugin)
private final class AsyncTestPlugin: CAPPlugin, CAPBridgedPlugin {
    let identifier = "CAPAsyncTestPlugin"
    let jsName = "AsyncTest"
    let pluginMethods: [CAPPluginMethod] = [
        .async("nothing", AsyncTestPlugin.nothing),
        .async("resolvesItself", AsyncTestPlugin.resolvesItself),
        .async("object", AsyncTestPlugin.object),
        .async("encodable", AsyncTestPlugin.encodable),
        .async("unencodable", AsyncTestPlugin.unencodable),
        .async("fails", AsyncTestPlugin.fails),
        .async("onMain", AsyncTestPlugin.onMain),
        .async("synchronousOnMain", AsyncTestPlugin.synchronousOnMain),
        .promise("synchronousOnMainByPromise", AsyncTestPlugin.synchronousOnMainByPromise),
        .async("keepsAlive", AsyncTestPlugin.keepsAlive),
        .async("waits", AsyncTestPlugin.waits),
        .async("ignoresCancellation", AsyncTestPlugin.ignoresCancellation)
    ]

    let gate = Gate()
    /// Called when `waits` or `ignoresCancellation` has started.
    var started: () -> Void = {}
    /// Called when `waits` saw that its task was cancelled.
    var cancelled: () -> Void = {}

    private func nothing(_ call: CAPPluginCall) async {}

    private func resolvesItself(_ call: CAPPluginCall) async {
        call.resolve(["value": "own"])
    }

    private func object(_ call: CAPPluginCall) async throws -> JSObject {
        ["value": call.getString("value") ?? ""]
    }

    private func encodable(_ call: CAPPluginCall) async throws -> Photo {
        Photo(path: "photo.jpg")
    }

    private func unencodable(_ call: CAPPluginCall) async throws -> String {
        "not an object"
    }

    private func fails(_ call: CAPPluginCall) async throws {
        throw AsyncFailure()
    }

    @MainActor
    private func onMain(_ call: CAPPluginCall) async -> JSObject {
        ["main": onMainThread()]
    }

    @MainActor
    private func synchronousOnMain(_ call: CAPPluginCall) -> JSObject {
        ["main": onMainThread()]
    }

    @MainActor
    private func synchronousOnMainByPromise(_ call: CAPPluginCall) {
        call.resolve(["main": onMainThread()])
    }

    private func keepsAlive(_ call: CAPPluginCall) async {
        call.keepAlive = true
    }

    private func waits(_ call: CAPPluginCall) async throws {
        started()
        do {
            try await Task.sleep(nanoseconds: 120 * NSEC_PER_SEC)
        } catch is CancellationError {
            cancelled()
            throw CancellationError()
        }
    }

    private func ignoresCancellation(_ call: CAPPluginCall) async -> JSObject {
        started()
        await gate.wait()
        return ["late": true]
    }
}

class AsyncPluginMethodTests: XCTestCase {
    private var bridge: RecordingBridge!
    private var plugin: AsyncTestPlugin!

    override func setUp() {
        super.setUp()
        bridge = RecordingBridge(delegate: TestBridgeDelegate())
        plugin = AsyncTestPlugin()
        bridge.registerPluginInstance(plugin)
    }

    override func tearDown() {
        plugin.gate.open()
        bridge = nil
        plugin = nil
        super.tearDown()
    }

    @discardableResult
    private func dispatch(_ method: String, _ options: [String: Any] = [:], callbackId: String = UUID().uuidString) -> String {
        bridge.handleJSCall(call: JSCall(options: options, pluginId: "AsyncTest", method: method, callbackId: callbackId))
        return callbackId
    }

    /// Sends `method` and returns the first result sent for it.
    private func send(_ method: String, _ options: [String: Any] = [:]) -> RecordingBridge.Message? {
        let sent = expectation(description: "\(method) answered")
        let callbackId = UUID().uuidString
        bridge.onMessage = { if $0.callbackId == callbackId { sent.fulfill() } }
        dispatch(method, options, callbackId: callbackId)
        wait(for: [sent], timeout: 20)
        return bridge.messages.first { $0.callbackId == callbackId }
    }

    private func messages(for callbackId: String) -> [RecordingBridge.Message] {
        bridge.messages.filter { $0.callbackId == callbackId }
    }

    /// Waits until the bridge tracks no running call, so that anything a finished method sends has been sent.
    private func waitUntilNoCallIsRunning(file: StaticString = #filePath, line: UInt = #line) {
        let deadline = Date().addingTimeInterval(20)
        while bridge.asyncCalls.count > 0 && Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.01))
        }
        XCTAssertEqual(bridge.asyncCalls.count, 0, file: file, line: line)
    }

    func testTheAsyncFactoriesReturnPromises() {
        XCTAssertTrue(plugin.pluginMethods.filter { $0.name != "synchronousOnMainByPromise" }.allSatisfy { $0.returnType == .promise })
    }

    func testReturningNothingResolvesWithoutData() {
        let message = send("nothing")
        XCTAssertEqual(message?.success, true)
        XCTAssertEqual(message?.payload, "undefined")
        XCTAssertEqual(message?.save, false)
    }

    func testAMethodThatResolvesItsCallIsNotResolvedAgain() {
        let message = send("resolvesItself")
        XCTAssertEqual(message?.payload, #"{"value":"own"}"#)
        waitUntilNoCallIsRunning()
        XCTAssertEqual(bridge.messages.count, 1)
    }

    func testReturningAnObjectResolvesWithIt() {
        XCTAssertEqual(send("object", ["value": "hi"])?.payload, #"{"value":"hi"}"#)
    }

    func testReturningAnEncodableValueResolvesWithItsEncoding() {
        XCTAssertEqual(send("encodable")?.payload, #"{"path":"photo.jpg"}"#)
    }

    func testAValueThatIsNotAnObjectRejects() throws {
        let message = try XCTUnwrap(send("unencodable"))
        XCTAssertFalse(message.success)
        XCTAssertTrue(message.payload.contains(#""message":"Failed encoding response""#), message.payload)
    }

    func testThrowingRejects() throws {
        let message = try XCTUnwrap(send("fails"))
        XCTAssertFalse(message.success)
        XCTAssertTrue(message.payload.contains(#""message":"the async method failed""#), message.payload)
    }

    func testAMainActorMethodRunsOnTheMainThread() {
        XCTAssertEqual(send("onMain")?.payload, #"{"main":true}"#)
    }

    func testASynchronousMainActorMethodRegisteredAsAsyncRunsOnTheMainThread() {
        XCTAssertEqual(send("synchronousOnMain")?.payload, #"{"main":true}"#)
    }

    /// The limitation of the Swift 5 language mode: a synchronous `@MainActor` method converts silently to the
    /// nonisolated function type that `promise`, `callback` and `none` take, and then runs on the bridge queue.
    /// Registering it with `async` is what runs it on the main thread (see the test above). The Swift 6 language mode
    /// rejects this registration at compile time; this test documents the Swift 5 behaviour until then.
    func testASynchronousMainActorMethodRegisteredAsPromiseRunsOnTheBridgeQueue() {
        XCTAssertEqual(send("synchronousOnMainByPromise")?.payload, #"{"main":false}"#)
    }

    func testAMethodThatKeepsItsCallAliveIsSavedAndNotResolved() {
        let callbackId = dispatch("keepsAlive")
        bridge.dispatchQueue.sync {}
        waitUntilNoCallIsRunning()
        XCTAssertNotNil(bridge.savedCall(withID: callbackId))
        XCTAssertTrue(messages(for: callbackId).isEmpty)
    }

    func testResettingTheBridgeRejectsAndCancelsRunningCalls() {
        let started = expectation(description: "method started")
        let cancelled = expectation(description: "task cancelled")
        plugin.started = { started.fulfill() }
        plugin.cancelled = { cancelled.fulfill() }

        let callbackId = dispatch("waits")
        wait(for: [started], timeout: 20)
        XCTAssertEqual(bridge.asyncCalls.count, 1)

        bridge.reset()
        wait(for: [cancelled], timeout: 20)
        waitUntilNoCallIsRunning()

        let sent = messages(for: callbackId)
        XCTAssertEqual(sent.count, 1, "the CancellationError the method threw is dropped")
        XCTAssertEqual(sent.first?.success, false)
        XCTAssertTrue(sent.first?.payload.contains(#""message":"The plugin call was cancelled""#) ?? false, sent.first?.payload ?? "")
    }

    func testWhatACancelledMethodReturnsLaterIsDropped() {
        let started = expectation(description: "method started")
        plugin.started = { started.fulfill() }

        let callbackId = dispatch("ignoresCancellation")
        wait(for: [started], timeout: 20)
        bridge.reset()
        XCTAssertEqual(bridge.asyncCalls.count, 0)

        let finished = expectation(description: "method returned")
        finished.isInverted = true
        bridge.onMessage = { if $0.callbackId == callbackId { finished.fulfill() } }
        plugin.gate.open()
        wait(for: [finished], timeout: 1)

        let sent = messages(for: callbackId)
        XCTAssertEqual(sent.count, 1)
        XCTAssertEqual(sent.first?.success, false)
    }

    func testReleasingTheBridgeCancelsRunningCalls() {
        let started = expectation(description: "method started")
        let cancelled = expectation(description: "task cancelled")
        plugin.started = { started.fulfill() }
        plugin.cancelled = { cancelled.fulfill() }

        dispatch("waits")
        wait(for: [started], timeout: 20)
        bridge.dispatchQueue.sync {}

        weak var released: RecordingBridge?
        released = bridge
        bridge = nil
        XCTAssertNil(released, "nothing may keep the bridge alive")
        wait(for: [cancelled], timeout: 20)
        bridge = RecordingBridge(delegate: TestBridgeDelegate())
    }
}
