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
