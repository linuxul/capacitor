import XCTest

@testable import Capacitor

@objc(CAPSettleTestPlugin)
private class SettleTestPlugin: CAPPlugin, CAPBridgedPlugin {
    let identifier = "CAPSettleTestPlugin"
    let jsName = "SettleTest"
    let pluginMethods: [CAPPluginMethod] = [
        .promise("resolveTwice", SettleTestPlugin.resolveTwice),
        .callback("watch", SettleTestPlugin.watch)
    ]

    func resolveTwice(_ call: CAPPluginCall) {
        call.resolve(["attempt": 1])
        call.reject("too late")
        call.resolve(["attempt": 2])
    }

    func watch(_ call: CAPPluginCall) {
        call.keepAlive = true
        call.resolve(["tick": 1])
        call.resolve(["tick": 2])
    }
}

class PluginCallSettleTests: XCTestCase {
    private final class Counter {
        private let lock = NSLock()
        private var successes = 0
        private var errors = 0

        var counts: (successes: Int, errors: Int) {
            lock.withLock { (successes, errors) }
        }

        func call(keepAlive: Bool = false) -> CAPPluginCall {
            let call = CAPPluginCall(callbackId: UUID().uuidString, methodName: "test", options: [:], success: { [self] _, _ in
                lock.withLock { successes += 1 }
            }, error: { [self] _ in
                lock.withLock { errors += 1 }
            })
            call.keepAlive = keepAlive
            return call
        }
    }

    private struct Payload: Encodable {
        let value = 1
    }

    func testACallSettlesOnce() {
        let counter = Counter()
        let call = counter.call()
        call.resolve()
        call.resolve(["value": 1])
        call.resolve(with: Payload())
        call.reject("late")
        call.unimplemented()
        call.unavailable()
        XCTAssertEqual(counter.counts.successes, 1)
        XCTAssertEqual(counter.counts.errors, 0)
    }

    func testTheFirstRejectWins() {
        let counter = Counter()
        let call = counter.call()
        call.unavailable()
        call.reject("late")
        call.resolve()
        XCTAssertEqual(counter.counts.successes, 0)
        XCTAssertEqual(counter.counts.errors, 1)
    }

    func testACallKeptAliveResolvesRepeatedlyUntilReleased() {
        let counter = Counter()
        let call = counter.call(keepAlive: true)
        call.resolve()
        call.resolve()
        call.reject("recoverable")
        call.resolve()
        XCTAssertEqual(counter.counts.successes, 3)
        XCTAssertEqual(counter.counts.errors, 1)

        // the final result once the call is no longer kept alive
        call.keepAlive = false
        call.resolve()
        call.resolve()
        XCTAssertEqual(counter.counts.successes, 4)
    }

    func testConcurrentSettlementsSendOneResult() {
        let counter = Counter()
        let call = counter.call()
        DispatchQueue.concurrentPerform(iterations: 200) { index in
            if index.isMultiple(of: 2) {
                call.resolve()
            } else {
                call.reject("race")
            }
        }
        XCTAssertEqual(counter.counts.successes + counter.counts.errors, 1)
    }

    func testKeepAliveIsSafeToUseFromManyThreads() {
        let counter = Counter()
        let call = counter.call()
        DispatchQueue.concurrentPerform(iterations: 1000) { index in
            if index.isMultiple(of: 2) {
                call.keepAlive = true
            } else {
                _ = call.keepAlive
            }
        }
        XCTAssertTrue(call.keepAlive)
        // resolving while other threads flip keepAlive settles at least once and never crashes
        DispatchQueue.concurrentPerform(iterations: 200) { index in
            if index.isMultiple(of: 3) {
                call.keepAlive.toggle()
            } else {
                call.resolve()
            }
        }
        XCTAssertGreaterThanOrEqual(counter.counts.successes, 1)
    }

    func testListenersStillReceiveEveryEvent() {
        let plugin = SettleTestPlugin()
        let counter = Counter()
        // registered directly, without addListener, so the call is not kept alive
        plugin.addEventListener("tick", listener: counter.call())
        plugin.notifyListeners("tick", data: [:])
        plugin.notifyListeners("tick", data: [:])
        XCTAssertEqual(counter.counts.successes, 2)
    }

    private func send(_ method: String, expecting count: Int, on bridge: RecordingBridge) -> [RecordingBridge.Message] {
        let callbackId = UUID().uuidString
        let sent = expectation(description: "messages sent")
        sent.expectedFulfillmentCount = count
        sent.assertForOverFulfill = true
        bridge.onMessage = { message in
            if message.callbackId == callbackId {
                sent.fulfill()
            }
        }
        bridge.handleJSCall(call: JSCall(options: [:], pluginId: "SettleTest", method: method, callbackId: callbackId))
        wait(for: [sent], timeout: 20)
        // let anything sent late show up
        bridge.dispatchQueue.sync {}
        return bridge.messages.filter { $0.callbackId == callbackId }
    }

    func testTheBridgeSendsOneResultForAPromise() {
        let bridge = RecordingBridge(delegate: TestBridgeDelegate())
        bridge.registerPluginInstance(SettleTestPlugin())
        let messages = send("resolveTwice", expecting: 1, on: bridge)
        XCTAssertEqual(messages.count, 1)
        XCTAssertEqual(messages.first?.success, true)
        XCTAssertEqual(messages.first?.payload, #"{"attempt":1}"#)
    }

    func testTheBridgeSendsEveryResultForACallKeptAlive() {
        let bridge = RecordingBridge(delegate: TestBridgeDelegate())
        bridge.registerPluginInstance(SettleTestPlugin())
        let messages = send("watch", expecting: 2, on: bridge)
        XCTAssertEqual(messages.map(\.save), [true, true])
    }
}
