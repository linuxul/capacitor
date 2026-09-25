//
//  DecoderBoundaryTests.swift
//  CodableTests
//

import XCTest
import Capacitor

/// Reads a fixed number of elements from an unkeyed container, the way `CGPoint` and hand-written tuple types do.
private struct Pair: Decodable, Equatable {
    let first: Int
    let second: Int

    init(first: Int, second: Int) {
        self.first = first
        self.second = second
    }

    init(from decoder: Decoder) throws {
        var container = try decoder.unkeyedContainer()
        first = try container.decode(Int.self)
        second = try container.decode(Int.self)
    }
}

/// Reads optional elements with `decodeIfPresent`, which asks `decodeNil()` before decoding each element.
private struct OptionalTriple: Decodable, Equatable {
    let values: [Int?]

    init(values: [Int?]) {
        self.values = values
    }

    init(from decoder: Decoder) throws {
        var container = try decoder.unkeyedContainer()
        values = [
            try container.decodeIfPresent(Int.self),
            try container.decodeIfPresent(Int.self),
            try container.decodeIfPresent(Int.self)
        ]
    }
}

/// Asks for one nested container more than the array holds.
private struct NestedBeyondEnd: Decodable {
    enum Kind: String, CaseIterable {
        case unkeyed, keyed, superDecoder
    }

    static var kind = Kind.unkeyed

    init(from decoder: Decoder) throws {
        var container = try decoder.unkeyedContainer()
        switch Self.kind {
        case .unkeyed:
            _ = try container.nestedUnkeyedContainer()
        case .keyed:
            _ = try container.nestedContainer(keyedBy: Item.CodingKeys.self)
        case .superDecoder:
            _ = try container.superDecoder()
        }
    }
}

/// Retries an element as a different type after the first attempt failed.
private struct RetryingProbe: Decodable {
    let intFailed: Bool
    let string: String
    let endedAtEnd: Bool

    init(from decoder: Decoder) throws {
        var container = try decoder.unkeyedContainer()
        intFailed = (try? container.decode(Int.self)) == nil
        // the element that could not be read as an Int is still the current one
        string = try container.decode(String.self)
        endedAtEnd = container.isAtEnd
    }
}

private struct Item: Decodable {
    enum CodingKeys: String, CodingKey {
        case name
    }

    let name: String
}

private struct Order: Decodable {
    let items: [Item]
}

class DecoderBoundaryTests: XCTestCase {
    private let decoder = JSValueDecoder()

    func testShortArrayIntoFixedSizeTypeThrowsValueNotFound() throws {
        XCTAssertThrowsError(try decoder.decode(Pair.self, from: [1 as NSNumber] as JSArray)) { error in
            guard case let DecodingError.valueNotFound(_, context) = error else {
                return XCTFail("expected valueNotFound, got \(error)")
            }
            XCTAssertEqual(context.codingPath.map(\.intValue), [1])
        }
        XCTAssertThrowsError(try decoder.decode(Pair.self, from: [] as JSArray))
    }

    func testExactArrayIntoFixedSizeTypeDecodes() throws {
        let pair = try decoder.decode(Pair.self, from: [1 as NSNumber, 2 as NSNumber] as JSArray)
        XCTAssertEqual(pair, Pair(first: 1, second: 2))
    }

    func testNestedContainersPastTheEndThrow() {
        for kind in NestedBeyondEnd.Kind.allCases {
            NestedBeyondEnd.kind = kind
            XCTAssertThrowsError(try decoder.decode(NestedBeyondEnd.self, from: [] as JSArray), "\(kind)") { error in
                guard case DecodingError.valueNotFound = error else {
                    return XCTFail("\(kind): expected valueNotFound, got \(error)")
                }
            }
        }
    }

    func testDecodeNilOnlyConsumesNull() throws {
        let value = try decoder.decode(OptionalTriple.self, from: [1 as NSNumber, NSNull(), 3 as NSNumber] as JSArray)
        XCTAssertEqual(value, OptionalTriple(values: [1, nil, 3]))
    }

    func testDecodeIfPresentPastTheEndIsNil() throws {
        let value = try decoder.decode(OptionalTriple.self, from: [1 as NSNumber] as JSArray)
        XCTAssertEqual(value, OptionalTriple(values: [1, nil, nil]))
    }

    func testFailedElementDecodeDoesNotAdvance() throws {
        let probe = try decoder.decode(RetryingProbe.self, from: ["a"] as JSArray)
        XCTAssertTrue(probe.intFailed)
        XCTAssertEqual(probe.string, "a")
        XCTAssertTrue(probe.endedAtEnd)
    }

    func testNestedErrorCarriesTheCodingPath() {
        let order: JSObject = ["items": [["name": "first"], ["name": 3 as NSNumber]] as JSArray]
        XCTAssertThrowsError(try decoder.decode(Order.self, from: order)) { error in
            guard case let DecodingError.typeMismatch(_, context) = error else {
                return XCTFail("expected typeMismatch, got \(error)")
            }
            XCTAssertEqual(context.codingPath.map(\.stringValue), ["items", "Index 1", "name"])
            XCTAssertEqual(context.codingPath.map(\.intValue), [nil, 1, nil])
        }
    }

    func testMissingKeyInNestedObjectCarriesTheCodingPath() {
        let order: JSObject = ["items": [["name": "first"], [:] as JSObject] as JSArray]
        XCTAssertThrowsError(try decoder.decode(Order.self, from: order)) { error in
            guard case let DecodingError.keyNotFound(key, context) = error else {
                return XCTFail("expected keyNotFound, got \(error)")
            }
            XCTAssertEqual(key.stringValue, "name")
            XCTAssertEqual(context.codingPath.map(\.stringValue), ["items", "Index 1"])
        }
    }
}
