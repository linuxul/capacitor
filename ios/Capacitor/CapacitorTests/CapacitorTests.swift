import XCTest
@testable import Capacitor

// Shared test doubles for the bridge; see HttpInterceptorNavigationTests.
class MockBridgeViewController: CAPBridgeViewController {
}

class MockAssetHandler: WebViewAssetHandler {
}

class MockBridge: CapacitorBridge {
    override public func registerPlugins() {
        Swift.print("REGISTER PLUGINS")
    }
}

/// A bridge that records what it would send to the page instead of evaluating it.
class RecordingBridge: MockBridge {
    struct Message {
        let callbackId: String
        let success: Bool
        let save: Bool
        let payload: String
    }

    private let lock = NSLock()
    private var recorded: [Message] = []
    /// Called after every recorded message, on the thread that sent it.
    var onMessage: (Message) -> Void = { _ in }

    var messages: [Message] {
        lock.withLock { recorded }
    }

    convenience init(delegate: CAPBridgeDelegate, delegationHandler: WebViewDelegationHandler = WebViewDelegationHandler()) {
        self.init(with: InstanceConfiguration(with: InstanceDescriptor(), isDebug: true), delegate: delegate,
                  assetHandler: MockAssetHandler(router: CapacitorRouter()), delegationHandler: delegationHandler)
    }

    override func toJs(result: JSResultProtocol, save: Bool) {
        record(Message(callbackId: result.callbackID, success: true, save: save, payload: result.jsonPayload()))
    }

    override func toJsError(error: JSResultProtocol, save: Bool) {
        record(Message(callbackId: error.callbackID, success: false, save: save, payload: error.jsonPayload()))
    }

    private func record(_ message: Message) {
        lock.withLock { recorded.append(message) }
        onMessage(message)
    }
}
