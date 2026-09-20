import XCTest

@testable import Capacitor

class PluginCallAccessorTests: XCTestCase {
    static let referenceInterval: TimeInterval = 632854800

    var call: CAPPluginCall!

    override func setUp() {
        super.setUp()
        let date = Date(timeIntervalSinceReferenceDate: Self.referenceInterval)
        let formatter = ISO8601DateFormatter()
        let source: [AnyHashable: Any] = ["testString": "foo",
                                          "testDict": ["testSubkey": "sub value"],
                                          "testArray": ["one", "two"],
                                          "testFloat": 3.14159,
                                          "testInt": 42,
                                          "testDateObject": date,
                                          "testDateString": formatter.string(from: date),
                                          "testBoolTrue": true,
                                          "testBoolFalse": false,
                                          "testNull": NSNull()]
        // mirror the bridge, which always coerces the options before constructing a call
        let options = JSTypes.coerceDictionaryToJSObject(source) ?? [:]
        call = CAPPluginCall(callbackId: "test", methodName: "test", options: options, success: { _, _ in }, error: { _ in })
    }

    func testCallProperties() {
        XCTAssertEqual(call.callbackId, "test")
        XCTAssertEqual(call.methodName, "test")
        XCTAssertFalse(call.keepAlive)
        XCTAssertEqual(call.jsObjectRepresentation.count, 10)
    }

    func testStringAccessor() {
        XCTAssertEqual(call.getString("testString"), "foo")
        XCTAssertNil(call.getString("badString"))
        XCTAssertEqual(call.getString("badString", "default"), "default")
        // wrong type and null fall back to the default
        XCTAssertNil(call.getString("testFloat"))
        XCTAssertNil(call.getString("testNull"))
        XCTAssertEqual(call.getString("testNull", "default"), "default")
    }

    func testDateObjectAccessor() {
        let value = call.getDate("testDateObject")
        XCTAssertEqual(value?.timeIntervalSinceReferenceDate, Self.referenceInterval)

        XCTAssertNil(call.getDate("badString"))

        let defaultDate = Date()
        XCTAssertEqual(call.getDate("badString", defaultDate), defaultDate)
    }

    func testDateStringAccessor() {
        let objectValue = call.getDate("testDateObject")
        let stringValue = call.getDate("testDateString")
        XCTAssertNotNil(objectValue)
        XCTAssertNotNil(stringValue)
        XCTAssertEqual(objectValue, stringValue)
        // a string that is not an ISO 8601 date does not parse
        XCTAssertNil(call.getDate("testString"))
    }

    func testObjectAccessor() {
        let value = call.getObject("testDict")
        XCTAssertEqual(value?["testSubkey"] as? String, "sub value")

        XCTAssertNil(call.getObject("badString"))

        let fallback = call.getObject("badString", ["defaultKey": "default"])
        XCTAssertEqual(fallback["defaultKey"] as? String, "default")
    }

    func testArrayAccessor() {
        XCTAssertEqual(call.getArray("testArray", String.self), ["one", "two"])
        XCTAssertEqual(call.getArray("testArray")?.count, 2)
        XCTAssertNil(call.getArray("badString"))
        XCTAssertEqual(call.getArray("badString", ["default"]).count, 1)
    }

    func testNumberAccessor() throws {
        let value = try XCTUnwrap(call.getValue("testFloat") as? NSNumber)
        XCTAssertTrue(value.isEqual(to: NSNumber(value: 3.14159)))
        XCTAssertEqual(try XCTUnwrap(call.getDouble("testFloat")), 3.14159, accuracy: 0.000001)
        XCTAssertEqual(try XCTUnwrap(call.getFloat("testFloat")), 3.14159, accuracy: 0.0001)
        XCTAssertEqual(call.getInt("testInt"), 42)

        XCTAssertNil(call.getValue("badString"))
        XCTAssertNil(call.getDouble("badString"))
        XCTAssertNil(call.getInt("badString"))

        XCTAssertEqual(call.getInt("badString", 100), 100)
        XCTAssertEqual(call.getDouble("badString", 100), 100)

        // booleans are still representable as numbers
        let boolNumber = try XCTUnwrap(call.getValue("testBoolTrue") as? NSNumber)
        XCTAssertTrue(boolNumber.boolValue)
    }

    func testBoolAccessor() {
        XCTAssertTrue(call.getBool("testBoolTrue", false))
        XCTAssertFalse(call.getBool("testBoolFalse", true))
        XCTAssertTrue(call.getBool("badString", true))
        XCTAssertFalse(call.getBool("badString", false))
        XCTAssertEqual(call.getBool("testBoolTrue"), true)
        XCTAssertEqual(call.getBool("testBoolFalse"), false)
        XCTAssertNil(call.getBool("badString"))
    }
}
