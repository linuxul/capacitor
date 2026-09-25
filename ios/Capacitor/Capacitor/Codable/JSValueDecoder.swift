//
//  JSValueDecoder.swift
//  Capacitor
//
//  Created by Steven Sherry on 12/8/23.
//  Copyright © 2023 Drifty Co. All rights reserved.
//

import Foundation
import Combine

/// A decoder that can decode ``JSValue`` objects into `Decodable` types.
public final class JSValueDecoder: TopLevelDecoder {
    /// The strategies available for formatting dates when decoding from a ``JSValue``
    public typealias DateDecodingStrategy = JSONDecoder.DateDecodingStrategy
    /// The strategies available for decoding raw data.
    public typealias DataDecodingStrategy = JSONDecoder.DataDecodingStrategy

    /// The strategies availble for decoding NaN, Infinity, and -Infinity
    public enum NonConformingFloatDecodingStrategy {
        /// Decodes directly into the floating point type as .infinity, -.infinity, or .nan
        case deferred
        /// Throw an error when a non-conforming float is encountered
        case `throw`
        /// Converts from the provided strings into .infinity, -.infinity, or .nan
        case convertFromString(positiveInfinity: String, negativeInfinity: String, nan: String)
    }

    struct Options {
        var dataStrategy: DataDecodingStrategy
        var dateStrategy: DateDecodingStrategy
        var nonConformingStrategy: NonConformingFloatDecodingStrategy
    }

    private var options: Options

    /// Creates a new JSValueDecoder with the provided decoding and formatting strategies
    /// - Parameters:
    ///   - dateDecodingStrategy: Defaults to `DateDecodingStrategy.deferredToDate`
    ///   - dataDecodingStrategy: Defaults to `DataDecodingStrategy.deferredToData`
    ///   - nonConformingFloatDecodingStrategy: Defaults to ``NonConformingFloatDecodingStrategy/deferred``
    public init(
        dateDecodingStrategy: DateDecodingStrategy = .deferredToDate,
        dataDecodingStrategy: DataDecodingStrategy = .deferredToData,
        nonConformingFloatDecodingStrategy: NonConformingFloatDecodingStrategy = .deferred
    ) {
        self.options = .init(
            dataStrategy: dataDecodingStrategy,
            dateStrategy: dateDecodingStrategy,
            nonConformingStrategy: nonConformingFloatDecodingStrategy
        )
    }

    fileprivate init(options: Options) {
        self.options = options
    }

    /// The strategy to use when decoding dates from a ``JSValue``
    public var dateDecodingStrategy: DateDecodingStrategy {
        get { options.dateStrategy }
        set { options.dateStrategy = newValue }
    }

    /// The strategy to use when decoding raw data from a ``JSValue``
    public var dataDecodingStrategy: DataDecodingStrategy {
        get { options.dataStrategy }
        set { options.dataStrategy = newValue }
    }

    /// The strategy used by a decoder when it encounters exceptional floating-point values
    public var nonConformingFloatDecodingStrategy: NonConformingFloatDecodingStrategy {
        get { options.nonConformingStrategy }
        set { options.nonConformingStrategy = newValue }
    }

    /// Decodes a ``JSValue`` into the provided `Decodable` type
    /// - Parameters:
    ///   - type: The type of the value to decode from the provided ``JSValue`` object
    ///   - data: The ``JSValue`` to decode
    /// - Returns: A value of the specified type.
    ///
    /// An error will be thrown from this method for two possible reasons:
    /// 1. A type mismatch was found.
    /// 2. A key was not found in the `data` field that is required in the `type` provided.
    public func decode<T>(_ type: T.Type, from data: JSValue) throws -> T where T: Decodable {
        let decoder = JSValueDecoderImpl(data: data, options: options)
        return try decoder.decodeData(as: T.self)
    }
}

typealias CodingUserInfo = [CodingUserInfoKey: Any]
final class JSValueDecoderImpl {
    typealias Options = JSValueDecoder.Options

    var codingPath: [CodingKey] = []
    var userInfo: CodingUserInfo = [:]
    var options: Options
    fileprivate var data: JSValue

    init(data: JSValue, options: Options, codingPath: [CodingKey] = []) {
        self.data = data
        self.options = options
        self.codingPath = codingPath
    }
}

extension JSValueDecoderImpl: Decoder {
    func container<Key>(keyedBy type: Key.Type) throws -> KeyedDecodingContainer<Key> where Key: CodingKey {
        guard let data = data as? JSObject else {
            throw DecodingError.typeMismatch(JSObject.self, on: data, codingPath: codingPath)
        }

        return KeyedDecodingContainer(
            KeyedContainer(
                data: data,
                codingPath: codingPath,
                userInfo: userInfo,
                options: options
            )
        )
    }

    func unkeyedContainer() throws -> UnkeyedDecodingContainer {
        guard let data = data as? JSArray else {
            throw DecodingError.typeMismatch(JSArray.self, on: data, codingPath: codingPath)
        }

        return UnkeyedContainer(data: data, codingPath: codingPath, userInfo: userInfo, options: options)
    }

    func singleValueContainer() throws -> SingleValueDecodingContainer {
        SingleValueContainer(data: data, codingPath: codingPath, userInfo: userInfo, options: options)
    }

    // force casting is fine becasue we've already determined that T is the type in the case
    // the swift standard library also force casts in their similar functions
    // https://github.com/swiftlang/swift-foundation/blob/da80d51fa3e77f3e7ed57c4300a870689e755713/Sources/FoundationEssentials/JSON/JSONEncoder.swift#L1140
    // swiftlint:disable force_cast
    func decodeData<T>(as type: T.Type) throws -> T where T: Decodable {
        switch type {
        case is Date.Type:
            return try decodeDate() as! T
        case is URL.Type:
            return try decodeUrl() as! T
        case is Data.Type:
            return try decodeData() as! T
        default:
            return try T(from: self)
        }
    }
    // swiftlint:enable force_cast

    private func decodeDate() throws -> Date {
        switch options.dateStrategy {
        case .deferredToDate:
            return try Date(from: self)
        case .secondsSince1970:
            return Date(timeIntervalSince1970: try numberValue().doubleValue)
        case .millisecondsSince1970:
            return Date(timeIntervalSince1970: try numberValue().doubleValue / Double(MSEC_PER_SEC))
        case .iso8601:
            let value = try stringValue()
            guard let date = JSDateFormat.date(from: value) else {
                throw DecodingError.dataCorrupted(value, target: Date.self, codingPath: codingPath)
            }
            return date
        case .formatted(let formatter):
            let value = try stringValue()
            guard let date = formatter.date(from: value) else { throw DecodingError.dataCorrupted(value, target: Date.self, codingPath: codingPath) }
            return date
        case .custom(let decode):
            return try decode(self)
        @unknown default:
            return try Date(from: self)
        }
    }

    /// The value of a date encoded as a time interval.
    private func numberValue() throws -> NSNumber {
        guard let value = data as? NSNumber else { throw DecodingError.dataCorrupted(data, target: Double.self, codingPath: codingPath) }
        return value
    }

    /// The value of a date encoded as a string.
    private func stringValue() throws -> String {
        guard let value = data as? String else { throw DecodingError.dataCorrupted(data, target: String.self, codingPath: codingPath) }
        return value
    }

    private func decodeUrl() throws -> URL {
        guard let str = data as? String,
              let url = URL(string: str)
        else { throw DecodingError.dataCorrupted(data, target: URL.self, codingPath: codingPath) }

        return url
    }

    private func decodeData() throws -> Data {
        switch options.dataStrategy {
        case .deferredToData:
            return try Data(from: self)
        case .base64:
            guard let value = data as? String else { throw DecodingError.dataCorrupted(data, target: String.self, codingPath: codingPath) }
            guard let data = Data(base64Encoded: value) else { throw DecodingError.dataCorrupted(value, target: Data.self, codingPath: codingPath) }
            return data
        case .custom(let decode):
            return try decode(self)
        @unknown default:
            return try Data(from: self)
        }
    }
}

extension DecodingError {
    static func typeMismatch(_ type: Any.Type, on data: any JSValue, codingPath: [CodingKey]) -> DecodingError {
        return .typeMismatch(
            type,
            .init(
                codingPath: codingPath,
                debugDescription: "\(data) was unable to be cast to \(type)."
            )
        )
    }

    static func keyNotFound(_ key: any CodingKey, on data: any JSValue, codingPath: [CodingKey]) -> DecodingError {
        return .keyNotFound(
            key,
            .init(
                codingPath: codingPath,
                debugDescription: "Key \(key.stringValue) not found in \(data)")
        )
    }

    static func dataCorrupted<T>(_ value: any JSValue, target type: T.Type, codingPath: [CodingKey]) -> DecodingError where T: Decodable {
        return .dataCorrupted(.init(codingPath: codingPath, debugDescription: "\(value) was not in the format expected for \(T.self)"))
    }
}
