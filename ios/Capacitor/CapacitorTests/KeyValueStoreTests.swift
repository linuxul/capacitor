import XCTest

@testable import Capacitor

class KeyValueStoreTests: XCTestCase {
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "capacitor-tests-\(UUID().uuidString)"
    }

    override func tearDown() {
        if let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first {
            try? FileManager.default.removeItem(at: library.appendingPathComponent("kvstore").appendingPathComponent(suiteName))
        }
        super.tearDown()
    }

    func testStoresForOneSuiteShareABackend() {
        let first = KeyValueStore(suiteName: suiteName)
        let second = KeyValueStore(suiteName: suiteName)
        XCTAssertTrue(first.backend as AnyObject === second.backend as AnyObject)
        XCTAssertFalse(first.backend as AnyObject === KeyValueStore(suiteName: suiteName + "-other").backend as AnyObject)
    }

    func testConcurrentlyCreatedStoresShareABackend() {
        let lock = NSLock()
        var backends: [AnyObject] = []
        DispatchQueue.concurrentPerform(iterations: 64) { _ in
            let backend = KeyValueStore(suiteName: suiteName).backend as AnyObject
            lock.withLock { backends.append(backend) }
        }
        XCTAssertEqual(backends.count, 64)
        XCTAssertEqual(Set(backends.map(ObjectIdentifier.init)).count, 1, "two file stores for one suite would cache different values")
    }

    func testValuesAreVisibleAcrossStoresOfOneSuite() throws {
        let writer = KeyValueStore(suiteName: suiteName)
        let reader = KeyValueStore(suiteName: suiteName)
        writer["key"] = "one"
        XCTAssertEqual(reader["key", as: String.self], "one")
        writer["key"] = "two"
        XCTAssertEqual(reader["key", as: String.self], "two")
        reader["key"] = nil as String?
        XCTAssertNil(writer["key", as: String.self])
    }

    func testEphemeralStore() throws {
        let store = KeyValueStore(type: .ephemeral)
        try store.set("number", value: 42)
        XCTAssertEqual(try store.get("number", as: Int.self), 42)
        try store.delete("number")
        XCTAssertNil(try store.get("number", as: Int.self))
    }
}
