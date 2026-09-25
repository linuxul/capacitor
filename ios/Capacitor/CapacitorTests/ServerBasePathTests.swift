import XCTest

@testable import Capacitor

private final class RecordingAssetHandler: WebViewAssetHandler {
    private let lock = NSLock()
    private var recorded: [(path: String, onMain: Bool)] = []

    var assetPaths: [(path: String, onMain: Bool)] {
        lock.withLock { recorded }
    }

    override func setAssetPath(_ assetPath: String) {
        lock.withLock { recorded.append((assetPath, Thread.isMainThread)) }
        super.setAssetPath(assetPath)
    }
}

class ServerBasePathTests: XCTestCase {
    private var directory: URL!
    private var assetHandler: RecordingAssetHandler!
    private var delegate: TestBridgeDelegate!
    private var bridge: MockBridge!

    override func setUpWithError() throws {
        try super.setUpWithError()
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        assetHandler = RecordingAssetHandler(router: CapacitorRouter())
        delegate = TestBridgeDelegate()
        bridge = MockBridge(with: InstanceConfiguration(with: InstanceDescriptor(), isDebug: true), delegate: delegate,
                            assetHandler: assetHandler, delegationHandler: WebViewDelegationHandler())
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
        try super.tearDownWithError()
    }

    func testAPersistedServerBasePathIsServedFromTheSnapshotsInLibrary() throws {
        let store = KeyValueStore.standard
        let previous = store["serverBasePath", as: String.self]
        defer { store["serverBasePath"] = previous }
        store["serverBasePath"] = "/elsewhere/ionic_built_snapshots/snapshot-1"

        let viewController = CAPBridgeViewController()
        viewController.isNewBinary = false
        let library = try XCTUnwrap(NSSearchPathForDirectoriesInDomains(.libraryDirectory, .userDomainMask, true).first)
        let expected = URL(fileURLWithPath: library, isDirectory: true)
            .appendingPathComponent("NoCloud")
            .appendingPathComponent("ionic_built_snapshots")
            .appendingPathComponent("snapshot-1")
        XCTAssertEqual(viewController.instanceDescriptor().appLocation, expected)
    }

    func testTheRouterChangesOnTheMainThreadWhenCalledFromTheBridgeQueue() {
        let changed = expectation(description: "base path changed")
        let bridge = self.bridge!
        let path = directory.path
        var appLocation: String?
        bridge.dispatchQueue.async {
            bridge.setServerBasePath(path)
            // the configuration changes before the call returns
            appLocation = bridge.config.appLocation.path
            changed.fulfill()
        }
        wait(for: [changed], timeout: 20)
        XCTAssertEqual(appLocation, path)

        // the router follows on the main queue; wait for it instead of a fixed delay, which a busy machine outlasts
        let deadline = Date(timeIntervalSinceNow: 20)
        while assetHandler.assetPaths.isEmpty, Date() < deadline {
            RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.01))
        }
        XCTAssertEqual(assetHandler.assetPaths.map(\.path), [path])
        XCTAssertEqual(assetHandler.assetPaths.map(\.onMain), [true])
    }

    func testTheRouterChangesRightAwayOnTheMainThread() {
        bridge.setServerBasePath(directory.path)
        XCTAssertEqual(assetHandler.assetPaths.map(\.path), [directory.path])
        XCTAssertEqual(assetHandler.assetPaths.map(\.onMain), [true])
        XCTAssertEqual(bridge.config.appLocation.path, directory.path)
    }

    func testAMissingDirectoryIsIgnored() {
        let before = bridge.config.appLocation
        bridge.setServerBasePath(directory.appendingPathComponent("missing").path)
        XCTAssertEqual(bridge.config.appLocation, before)
        XCTAssertTrue(assetHandler.assetPaths.isEmpty)
    }
}
