import XCTest

@testable import Capacitor

class TestContainer: NSObject, JSValueContainer {
    var coercedDictionary: [AnyHashable: Any] = [:]
    
    public static var jsDateFormatter: ISO8601DateFormatter = {
        return ISO8601DateFormatter()
    }()
    
    public var jsObjectRepresentation: JSObject {
        return coercedDictionary as? JSObject ?? [:]
    }
}

class BridgedTypesTests: XCTestCase {
    static var unserializedDictionary: [AnyHashable: Any] = [:]
    static var deserializedDictionary: [AnyHashable: Any] = [:]
    
    var unserializedDictionary: [AnyHashable: Any] = [:]
    var deserializedDictionary: [AnyHashable: Any] = [:]
    var testContainer = TestContainer()
    
    override class func setUp() {
        let formatter = ISO8601DateFormatter()
        // an ISO 8601 string does not necessarily include subsecond precision, so we can't just capture the current date
        // or else we won't be able to compare the objects since they could differ by milliseconds or nanoseonds. so instead
        // we use a fixed timestamp at a whole hour.
        let date = NSDate(timeIntervalSinceReferenceDate: 632854800)
        let subDictionary: [AnyHashable: Any] = ["testIntArray": [0, 1, 2], "testStringArray": ["1", "2", "3"], "testDictionary":["foo":"bar"]]
        var dictionary: [AnyHashable: Any] = ["testInt": 1 as Int, "testFloat": Float.pi, "testBool": true as Bool, "testString": "Some string value", "testChild": subDictionary, "testDateString": formatter.string(from: date as Date)]
        // roundtrip through the JSON serializer and receive the result as an NSDictionary so that the values keep their
        // Foundation class cluster types (e.g. __NSCFNumber), which is what the bridge receives from the web view.
        // swiftlint:disable force_try force_cast
        let serializedData = try! JSONSerialization.data(withJSONObject: dictionary, options: .prettyPrinted)
        let deserializedObject = try! JSONSerialization.jsonObject(with: serializedData, options: []) as! NSDictionary
        var unwrappedResult = deserializedObject as! [AnyHashable: Any]
        // swiftlint:enable force_try force_cast
        // date objects are not handled by the JSON serializer, so we have to insert these after the roundtrip
        unwrappedResult["testDateObject"] = date
        dictionary["testDateObject"] = date
        unserializedDictionary = dictionary
        deserializedDictionary = unwrappedResult
    }
    
    override func setUpWithError() throws {
        // Put setup code here. This method is called before the invocation of each test method in the class.
        unserializedDictionary = BridgedTypesTests.unserializedDictionary
        deserializedDictionary = BridgedTypesTests.deserializedDictionary
        testContainer.coercedDictionary = JSTypes.coerceDictionaryToJSObject(deserializedDictionary)!
    }
    
    override func tearDownWithError() throws {
        // Put teardown code here. This method is called after the invocation of each test method in the class.
    }
    
    func testTranslation() throws {
        XCTAssertTrue(unserializedDictionary.count > 0)
        XCTAssertTrue(deserializedDictionary.count > 0)
        XCTAssertTrue(testContainer.coercedDictionary.count > 0)
    }
    
    func testCastingFailure() throws {
        var castResult = deserializedDictionary as? JSObject
        XCTAssertNil(castResult)
        
        castResult = unserializedDictionary as? JSObject
        XCTAssertNil(castResult)
    }
    
    func testCoercionSuccess() throws {
        let coercedResult = JSTypes.coerceDictionaryToJSObject(deserializedDictionary)
        XCTAssertNotNil(coercedResult)
    }
    
    func testRoundtripEquality() throws {
        let coercedResult = JSTypes.coerceDictionaryToJSObject(deserializedDictionary)!
        let foo: NSDictionary = coercedResult as NSDictionary
        let bar: NSDictionary = unserializedDictionary as NSDictionary
        
        XCTAssertEqual(foo, bar)
    }
    
    func testTypeEquavalency() throws {
        let coercedResult = JSTypes.coerceDictionaryToJSObject(deserializedDictionary)!
        let coercedFloat = coercedResult["testFloat"] as? Float
        let sourceFloat = unserializedDictionary["testFloat"] as? Float
        let resultFloat = deserializedDictionary["testFloat"] as? Float
        
        XCTAssertNotNil(coercedFloat)
        XCTAssertNotNil(sourceFloat)
        XCTAssertNotNil(resultFloat)
        
        XCTAssertEqual(coercedFloat, sourceFloat)
        XCTAssertEqual(sourceFloat, resultFloat)
        XCTAssertEqual(coercedFloat, Float.pi)
    }
    
    func testNumberWrapping() throws {
        // the original number is a swift primitive float
        let sourceFloat = unserializedDictionary["testFloat"]!
        XCTAssertTrue(type(of: sourceFloat) == Float.self)
        
        // but after serialization/deserilization, it will be wrapped as an NSNumber
        let wrappedFloat = deserializedDictionary["testFloat"]!
        let underlyingType: AnyObject.Type = NSClassFromString("__NSCFNumber")!
        XCTAssertTrue(type(of: wrappedFloat) == underlyingType.self)
        
        // coercion will keep the NSNumber type since there's no way to recover it
        let coercedResult = JSTypes.coerceDictionaryToJSObject(deserializedDictionary)!
        let coercedFloat = coercedResult["testFloat"]!
        XCTAssertTrue(type(of: coercedFloat) == underlyingType.self)
        
        // but the cast accessor should restore it
        let castFloat = testContainer.getFloat("testFloat")!
        XCTAssertTrue(type(of: castFloat) == Float.self)
        XCTAssertEqual(sourceFloat as! Float, castFloat)
    }
    
    func testDateObject() throws {
        let coercedResult = JSTypes.coerceDictionaryToJSObject(deserializedDictionary)!
        let date = coercedResult["testDateObject"] as! Date
        XCTAssertNotNil(date)
        XCTAssertTrue(type(of: date) == Date.self)
    }
    
    func testDateParsing() throws {
        let coercedResult = JSTypes.coerceDictionaryToJSObject(deserializedDictionary)!
        let formatter = ISO8601DateFormatter()
        let parsedDate = formatter.date(from: coercedResult["testDateString"] as! String)!
        let dateObject = coercedResult["testDateObject"] as! Date
        XCTAssertNotNil(parsedDate)
        XCTAssertNotNil(dateObject)
        XCTAssertTrue(dateObject.compare(parsedDate) == .orderedSame)
    }
    
    func testDateExtensions() throws {
        let parsedDate = testContainer.getDate("testDateString")!
        let dateObject = testContainer.getDate("testDateObject")!
        XCTAssertNotNil(parsedDate)
        XCTAssertNotNil(dateObject)
        XCTAssertTrue(dateObject.compare(parsedDate) == .orderedSame)
    }
    
    func testDateCoercion() throws {
        let stringifiedDictionary = JSTypes.coerceDictionaryToJSObject(deserializedDictionary, formattingDatesAsStrings: true)!
        let unstringifiedDictionary = JSTypes.coerceDictionaryToJSObject(deserializedDictionary, formattingDatesAsStrings: false)!
        let stringifiedValue = stringifiedDictionary["testDateObject"]!
        let unstringifiedValue = unstringifiedDictionary["testDateObject"]!
        XCTAssertTrue(type(of: stringifiedValue) == String.self)
        XCTAssertTrue(type(of: unstringifiedValue) == Date.self)
        XCTAssertEqual(stringifiedValue as! String, stringifiedDictionary["testDateString"] as! String)
    }
    
    func testDateResultWrapping() throws {
        let result = try PluginCallResult.dictionary(["date": unserializedDictionary["testDateObject"]!]).jsonRepresentation()
        XCTAssertEqual(result, "{\"date\":\"\(unserializedDictionary["testDateString"] as! String)\"}")
    }
    
    func testResultMerging() throws {
        let result = try PluginCallResult.dictionary(["number": 1]).jsonRepresentation(includingFields: ["string":"foo"])
        // ordering of the pairs should be non-deterministic
        if result != "{\"string\":\"foo\",\"number\":1}" && result != "{\"number\":1,\"string\":\"foo\"}" {
            XCTAssert(false)
        }
    }
    
    func testNullWrapping() throws {
        let dictionary: [AnyHashable: Any] = ["testInt": 1 as Int, "testNull": NSNull()]
        let coercedDictionary = JSTypes.coerceDictionaryToJSObject(dictionary)!
        XCTAssertNotNil(coercedDictionary)
        XCTAssertEqual(coercedDictionary.count, 2)
        XCTAssertTrue(coercedDictionary["testNull"]! is NSNull)
    }
    
    func testNullTransformation() throws {
        let array: [Any] = [1, NSNull(), "test string"]
        let coercedArray = JSTypes.coerceArrayToJSArray(array)!
        XCTAssertNotNil(coercedArray)
        XCTAssertEqual(coercedArray.count, 3)
        XCTAssertTrue(type(of: coercedArray[1]) == NSNull.self)
        let filteredArray = coercedArray.capacitor.replacingNullValues()
        XCTAssertEqual(filteredArray.count, 3)
        XCTAssertNil(filteredArray[1])
        let restoredArray = filteredArray.capacitor.replacingOptionalValues()
        XCTAssertEqual(restoredArray.count, 3)
        XCTAssertNotNil(restoredArray[1])
        XCTAssertTrue(restoredArray[0] is NSNumber)
        XCTAssertTrue(restoredArray[1] is NSNull)
        XCTAssertTrue(restoredArray[2] is String)
    }
    
    func testSparseArrayCastSuccess() throws {
        let array: [Any] = ["test string 1", "test string 2", NSNull()]
        let sparseArray = JSTypes.coerceArrayToJSArray(array)?.capacitor.replacingNullValues() as? [String?]
        XCTAssertNotNil(sparseArray)
        XCTAssertEqual(sparseArray!.count, 3)
        XCTAssertNil(sparseArray![2])
    }
    
    func testSparseArrayCastFailure() throws {
        let array: [Any] = ["test string 1", 1, NSNull()]
        let sparseArray = JSTypes.coerceArrayToJSArray(array)?.capacitor.replacingNullValues() as? [String?]
        XCTAssertNil(sparseArray)
    }

    func testNullHandling() throws {
        let source: [Any] = ["test", NSNull(), 3]
        let result = try XCTUnwrap(JSTypes.coerceArrayToJSArray(source)).capacitor.replacingNullValues().capacitor.replacingOptionalValues() as [Any]
        // test that the replaced null value exists, both natively and when bridged to Foundation
        XCTAssertTrue(result[1] is NSNull)
        XCTAssertTrue((result as NSArray).object(at: 1) is NSNull)
        // test that the null value casts to non-optional
        let castArray = try XCTUnwrap(result as? [JSValue])
        XCTAssertTrue(castArray[1] is NSNull)
    }

    func testOptionalHandling() throws {
        let source: [Any] = ["test", NSNull(), 3]
        let result = try XCTUnwrap(JSTypes.coerceArrayToJSArray(source)).capacitor.replacingNullValues() as [Any]
        // test that the removed null value, now optional, is automatically transformed back into a NSNull when bridged
        XCTAssertTrue((result as NSArray).object(at: 1) is NSNull)
        // test that the optional value fails to cast to non-optional
        XCTAssertNil(result as? [JSValue])
    }
}
