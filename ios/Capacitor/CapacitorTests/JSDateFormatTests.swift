import XCTest

@testable import Capacitor

/// Every place the runtime writes or reads an ISO 8601 date uses the same form: UTC, whole seconds.
class JSDateFormatTests: XCTestCase {
    // 2023-11-14T22:13:20.750Z; the fraction is dropped when written
    private let date = Date(timeIntervalSince1970: 1_700_000_000.75)
    private let wholeSeconds = Date(timeIntervalSince1970: 1_700_000_000)
    private let string = "2023-11-14T22:13:20Z"

    func testUsesTheDefaultOptionsOfISO8601DateFormatter() {
        XCTAssertEqual(JSDateFormat.formatter.formatOptions, ISO8601DateFormatter().formatOptions)
        XCTAssertEqual(JSDateFormat.formatter.formatOptions, .withInternetDateTime)
        XCTAssertEqual(JSDateFormat.formatter.timeZone, TimeZone(identifier: "GMT"))
    }

    func testWritesUTCWithWholeSeconds() {
        XCTAssertEqual(JSDateFormat.string(from: date), string)
    }

    func testReadsOffsetsButNotFractionalSeconds() {
        XCTAssertEqual(JSDateFormat.date(from: string), wholeSeconds)
        XCTAssertEqual(JSDateFormat.date(from: "2023-11-15T07:13:20+09:00"), wholeSeconds)
        XCTAssertNil(JSDateFormat.date(from: "2023-11-14T22:13:20.750Z"))
    }

    func testPluginCallResultsWriteDatesInTheSameForm() throws {
        // key order is not fixed, so the JSON is read back instead of compared as text
        let json = try XCTUnwrap(try PluginCallResultJSON.serialize(["date": date, "nested": [["date": date]]]))
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        XCTAssertEqual(object["date"] as? String, string)
        XCTAssertEqual((object["nested"] as? [[String: Any]])?.first?["date"] as? String, string)
    }

    func testCoercedCallOptionsWriteDatesInTheSameForm() {
        let object = JSTypes.coerceDictionaryToJSObject(["date": date, "list": [date]], formattingDatesAsStrings: true)
        XCTAssertEqual(object?["date"] as? String, string)
        XCTAssertEqual((object?["list"] as? JSArray)?.first as? String, string)
        XCTAssertEqual(JSTypes.coerceDictionaryToJSObject(["date": date])?["date"] as? Date, date)
    }

    func testTheValueEncoderAndDecoderUseTheSameForm() throws {
        XCTAssertEqual(try JSValueEncoder(dateEncodingStrategy: .iso8601).encode(date) as? String, string)
        XCTAssertEqual(try JSValueDecoder(dateDecodingStrategy: .iso8601).decode(Date.self, from: string), wholeSeconds)
        XCTAssertThrowsError(try JSValueDecoder(dateDecodingStrategy: .iso8601).decode(Date.self, from: "2023-11-14T22:13:20.750Z"))
    }

    func testPluginCallsReadDatesWithTheirOwnFormatter() {
        let call = CAPPluginCall(callbackId: "1", methodName: "m", options: ["date": string], success: { _, _ in }, error: { _ in })
        XCTAssertEqual(call.getDate("date"), wholeSeconds)
        XCTAssertEqual(CAPPluginCall.jsDateFormatter.formatOptions, JSDateFormat.formatter.formatOptions)
        // plugins can change the options of the public formatter; that must not change what the runtime writes
        XCTAssertFalse(CAPPluginCall.jsDateFormatter === JSDateFormat.formatter)
    }
}
