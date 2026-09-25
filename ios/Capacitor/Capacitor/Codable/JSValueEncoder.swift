//
//  JSValueEncoder.swift
//  Capacitor
//
//  Created by Steven Sherry on 12/8/23.
//  Copyright © 2023 Drifty Co. All rights reserved.
//

import Foundation
import Combine

/// An encoder than can encode ``JSValue`` objects from `Encodable` types
public final class JSValueEncoder: TopLevelEncoder {
    /// The strategy to use when encoding `nil` values
    public enum OptionalEncodingStrategy {
        /// Encode `nil` values as `NSNull`
        case explicitNulls
        /// Excludes the value from the encoded object altogether
        case undefined
    }

    /// The strategies available for encoding .nan, .infinity, and -.infinity
    public enum NonConformingFloatEncodingStrategy: Equatable {
        /// Throws an error when encountering an exceptional floating-point value
        case `throw`
        /// Converts to the provided strings
        case convertToString(positiveInfinity: String, negativeInfinity: String, nan: String)
        /// Encodes directly into an NSNumber
        case deferred
    }

    /// The strategy to use when encoding `Date` values
    public typealias DateEncodingStrategy = JSONEncoder.DateEncodingStrategy

    /// The strategy to use when encoding `Data` values
    public typealias DataEncodingStrategy = JSONEncoder.DataEncodingStrategy

    struct Options {
        var optionalStrategy: OptionalEncodingStrategy
        var dateStrategy: DateEncodingStrategy
        var dataStrategy: DataEncodingStrategy
        var nonConformingFloatStrategy: NonConformingFloatEncodingStrategy
    }

    private var options: Options

    /// The strategy to use when encoding `nil` values
    public var optionalEncodingStrategy: OptionalEncodingStrategy {
        get { options.optionalStrategy }
        set { options.optionalStrategy = newValue }
    }

    /// The strategy to use when encoding dates
    public var dateEncodingStrategy: DateEncodingStrategy {
        get { options.dateStrategy }
        set { options.dateStrategy = newValue }
    }

    /// The encoding strategy to use when encoding raw data
    public var dataEncodingStrategy: DataEncodingStrategy {
        get { options.dataStrategy }
        set { options.dataStrategy = newValue }
    }

    /// The encoding strategy to use when the encoder encounters exceptional floating-point values
    public var nonConformingFloatEncodingStrategy: NonConformingFloatEncodingStrategy {
        get { options.nonConformingFloatStrategy }
        set { options.nonConformingFloatStrategy = newValue }
    }

    /// Creates a new `JSValueEncoder`
    /// - Parameter optionalEncodingStrategy: The strategy to use when encoding `nil` values. Defaults to ``OptionalEncodingStrategy-swift.enum/undefined``
    /// - Parameter dateEncodingStrategy: Defaults to `DateEncodingStrategy.deferredToDate`
    /// - Parameter dataEncodingStrategy: Defaults to `DataEncodingStrategy.deferredToData`
    /// - Parameter nonConformingFloatEncodingStategy: Defaults to ``NonConformingFloatEncodingStrategy-swift.enum/deferred``
    public init(
        optionalEncodingStrategy: OptionalEncodingStrategy = .undefined,
        dateEncodingStrategy: DateEncodingStrategy = .deferredToDate,
        dataEncodingStrategy: DataEncodingStrategy = .deferredToData,
        nonConformingFloatEncodingStategy: NonConformingFloatEncodingStrategy = .deferred
    ) {
        self.options = .init(
            optionalStrategy: optionalEncodingStrategy,
            dateStrategy: dateEncodingStrategy,
            dataStrategy: dataEncodingStrategy,
            nonConformingFloatStrategy: nonConformingFloatEncodingStategy
        )
    }

    /// Encodes an `Encodable` value to a ``JSValue``
    /// - Parameter value: The value to encode to ``JSValue``
    /// - Returns: The encoded ``JSValue``
    /// - Throws: An error if the value could not be encoded as a ``JSValue``
    public func encode<T>(_ value: T) throws -> JSValue where T: Encodable {
        let encoder = JSValueEncoderImpl(options: options)
        try encoder.encodeGeneric(value)
        guard let value = encoder.data else {
            throw EncodingError.invalidValue(
                value,
                .init(
                    codingPath: encoder.codingPath,
                    debugDescription: "\(value) was unable to be encoded as a JSValue"
                )
            )
        }

        return value
    }

    /// Encodes an `Encodable` value to a ``JSObject``
    /// - Parameter value: The value to encode to a ``JSObject``
    /// - Returns: The encoded ``JSObject``
    /// - Throws: An error if the value could not be encoded as a ``JSObject``
    ///
    /// This method is a convenience method for encoding an `Encodable` value to a ``JSObject``.
    /// It is equivalent to calling ``encode(_:)`` and casting the result to a ``JSObject`` and
    /// throwing an error if the cast fails.
    public func encodeJSObject<T>(_ value: T) throws -> JSObject where T: Encodable {
        guard let object = try encode(value) as? JSObject else {
            throw EncodingError.invalidValue(
                value,
                .init(
                    codingPath: [],
                    debugDescription: "\(value) was unable to be encoded as a JSObject"
                )
            )
        }

        return object
    }
}

protocol JSValueEncodingContainer {
    var data: JSValue? { get }
}

enum EncodingContainer: JSValueEncodingContainer {
    case singleValue(JSValueEncoderImpl.SingleValueContainer)
    case unkeyed(JSValueEncoderImpl.UnkeyedContainer)
    case keyed(JSValueEncoderImpl.AnyKeyedContainer)

    var data: JSValue? {
        switch self {
        case let .singleValue(container):
            return container.data
        case let .unkeyed(container):
            return container.data
        case let .keyed(container):
            return container.data
        }
    }

    var type: String {
        switch self {
        case .singleValue:
            "SingleValueContainer"
        case .unkeyed:
            "UnkeyedContainer"
        case .keyed:
            "KeyedContainer"
        }
    }
}

final class JSValueEncoderImpl: JSValueEncodingContainer {
    typealias Options = JSValueEncoder.Options

    var codingPath: [CodingKey] = []
    var data: JSValue? {
        containers.data
    }

    var options: Options

    var userInfo: CodingUserInfo = [:]
    fileprivate var containers: [EncodingContainer] = []

    init(options: Options) {
        self.options = options
    }
}

extension Array: JSValueEncodingContainer where Element == EncodingContainer {
    var data: JSValue? {
        guard count != 0 else { return nil }
        guard count != 1 else { return self[0].data }
        var data: (any JSValue)?

        for container in self {
            if data == nil {
                data = container.data
            } else {
                // The top-level container is
                switch container {
                case let .keyed(container):
                    guard let obj = data as? JSObject else { break }
                    data = obj.merging(container.object() ?? [:]) { $1 }
                case let .unkeyed(container):
                    guard var copy = data as? JSArray else { break }
                    copy.append(contentsOf: container.array ?? [])
                    data = copy
                default:
                    break
                }
            }
        }

        return data
    }
}

enum EncodedValue {
    case value(any JSValue)
    case nestedContainer(any JSValueEncodingContainer)
}

extension JSValueEncoderImpl: Encoder {
    func addContainer(_ container: EncodingContainer) {
        guard !containers.isEmpty else {
            containers.append(container)
            return
        }

        for existingContainer in containers {
            switch (existingContainer, container) {
            case (.unkeyed, .unkeyed), (.keyed, .keyed):
                containers.append(container)
            default:
                preconditionFailure("Sibling top-level containers must be of the same type. Attempted to add a \(container)")
            }
        }
    }

    func container<Key>(keyedBy type: Key.Type) -> KeyedEncodingContainer<Key> where Key: CodingKey {
        let container = KeyedContainer<Key>(
            codingPath: codingPath,
            userInfo: userInfo,
            options: options
        )
        addContainer(.keyed(.init(container)))
        return KeyedEncodingContainer(container)
    }

    func unkeyedContainer() -> UnkeyedEncodingContainer {
        let container = UnkeyedContainer(
            codingPath: codingPath,
            userInfo: userInfo,
            options: options
        )
        addContainer(.unkeyed(container))
        return container
    }

    func singleValueContainer() -> SingleValueEncodingContainer {
        let container = SingleValueContainer(
            codingPath: codingPath,
            userInfo: userInfo,
            options: options
        )
        addContainer(.singleValue(container))
        return container
    }

    func encodeGeneric<T>(_ value: T) throws where T: Encodable {
        switch value {
        case let value as Date:
            try encodeDate(value)
        case let value as URL:
            try value.absoluteString.encode(to: self)
        case let value as Data:
            try encodeData(value)
        default:
            try value.encode(to: self)
        }
    }

    private func encodeDate(_ value: Date) throws {
        switch options.dateStrategy {
        case .deferredToDate:
            try value.encode(to: self)
        case .millisecondsSince1970:
            try (value.timeIntervalSince1970 * Double(MSEC_PER_SEC)).encode(to: self)
        case .secondsSince1970:
            try value.timeIntervalSince1970.encode(to: self)
        case .iso8601:
            let formattedDate = JSDateFormat.string(from: value)
            try formattedDate.encode(to: self)
        case .formatted(let formatter):
            let formattedDate = formatter.string(from: value)
            try formattedDate.encode(to: self)
        case .custom(let encode):
            try encode(value, self)
        @unknown default:
            try value.encode(to: self)
        }
    }

    private func encodeData(_ value: Data) throws {
        switch options.dataStrategy {
        case .deferredToData:
            try value.encode(to: self)
        case .base64:
            try value.base64EncodedString().encode(to: self)
        case .custom(let encode):
            try encode(value, self)
        @unknown default:
            try value.encode(to: self)
        }
    }
}
