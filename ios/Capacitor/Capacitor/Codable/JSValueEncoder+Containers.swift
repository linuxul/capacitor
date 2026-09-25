//
//  JSValueEncoder+Containers.swift
//  Capacitor
//
//  Created by Steven Sherry on 12/8/23.
//  Copyright © 2023 Drifty Co. All rights reserved.
//

import Foundation

// The containers a `JSValueEncoderImpl` hands out, moved here from JSValueEncoder.swift. They are nested in it so that
// their names do not clash with the containers of `JSValueDecoderImpl`.

extension JSValueEncoderImpl {
    final class KeyedContainer<Key> where Key: CodingKey {
        var object: JSObject? {
            encodedKeyedValue?.reduce(into: [:]) { obj, next in
                let (key, value) = next
                switch value {
                case .value(let value):
                    obj[key] = value
                case .nestedContainer(let container):
                    obj[key] = container.data
                }
            }
        }

        var codingPath: [CodingKey]
        var userInfo: CodingUserInfo
        var options: Options
        private var encodedKeyedValue: [String: EncodedValue]?

        init(codingPath: [CodingKey], userInfo: CodingUserInfo, options: Options) {
            self.codingPath = codingPath
            self.userInfo = userInfo
            self.options = options
        }
    }
}

extension JSValueEncoderImpl.KeyedContainer: KeyedEncodingContainerProtocol {
    func insert(_ value: JSValue, for key: Key) {
        insert(.value(value), for: key)
    }

    func insert<K: CodingKey>(_ encodedValue: EncodedValue, for key: K) {
        if encodedKeyedValue == nil {
            encodedKeyedValue = [key.stringValue: encodedValue]
        } else {
            encodedKeyedValue?[key.stringValue] = encodedValue
        }
    }

    func encodeNil(forKey key: Key) throws {
        insert(NSNull(), for: key)
    }

    func encode<T>(_ value: T, forKey key: Key) throws where T: Encodable {
        let encoder = JSValueEncoderImpl(options: options)
        try encoder.encodeGeneric(value)
        insert(.nestedContainer(encoder), for: key)
    }

    // This is a perectly valid name for this method. The underscore is to avoid a conflict with the
    // protocol requirement.
    // swiftlint:disable:next identifier_name
    func _encodeIfPresent<T>(_ value: T?, forKey key: Key) throws where T: Encodable {
        switch options.optionalStrategy {
        case .explicitNulls:
            if let value = value {
                try encode(value, forKey: key)
            } else {
                try encodeNil(forKey: key)
            }
        case .undefined:
            guard let value = value else { return }
            try encode(value, forKey: key)
        }
    }

    func encodeIfPresent<T>(_ value: T?, forKey key: Key) throws where T: Encodable {
        try _encodeIfPresent(value, forKey: key)
    }

    func encodeIfPresent(_ value: Bool?, forKey key: Key) throws {
        try _encodeIfPresent(value, forKey: key)
    }

    func encodeIfPresent(_ value: String?, forKey key: Key) throws {
        try _encodeIfPresent(value, forKey: key)
    }

    func encodeIfPresent(_ value: Double?, forKey key: Key) throws {
        try _encodeIfPresent(value, forKey: key)
    }

    func encodeIfPresent(_ value: Float?, forKey key: Key) throws {
        try _encodeIfPresent(value, forKey: key)
    }

    func encodeIfPresent(_ value: Int?, forKey key: Key) throws {
        try _encodeIfPresent(value, forKey: key)
    }

    func encodeIfPresent(_ value: Int8?, forKey key: Key) throws {
        try _encodeIfPresent(value, forKey: key)
    }

    func encodeIfPresent(_ value: Int16?, forKey key: Key) throws {
        try _encodeIfPresent(value, forKey: key)
    }

    func encodeIfPresent(_ value: Int32?, forKey key: Key) throws {
        try _encodeIfPresent(value, forKey: key)
    }

    func encodeIfPresent(_ value: Int64?, forKey key: Key) throws {
        try _encodeIfPresent(value, forKey: key)
    }

    func encodeIfPresent(_ value: UInt?, forKey key: Key) throws {
        try _encodeIfPresent(value, forKey: key)
    }

    func encodeIfPresent(_ value: UInt8?, forKey key: Key) throws {
        try _encodeIfPresent(value, forKey: key)
    }

    func encodeIfPresent(_ value: UInt16?, forKey key: Key) throws {
        try _encodeIfPresent(value, forKey: key)
    }

    func encodeIfPresent(_ value: UInt32?, forKey key: Key) throws {
        try _encodeIfPresent(value, forKey: key)
    }

    func encodeIfPresent(_ value: UInt64?, forKey key: Key) throws {
        try _encodeIfPresent(value, forKey: key)
    }

    func nestedContainer<NestedKey>(keyedBy keyType: NestedKey.Type, forKey key: Key) -> KeyedEncodingContainer<NestedKey> where NestedKey: CodingKey {
        var newPath = codingPath
        newPath.append(key)

        let nestedContainer = JSValueEncoderImpl.KeyedContainer<NestedKey>(
            codingPath: newPath,
            userInfo: userInfo,
            options: options
        )

        insert(.nestedContainer(nestedContainer), for: key)
        return KeyedEncodingContainer(nestedContainer)
    }

    func nestedUnkeyedContainer(forKey key: Key) -> UnkeyedEncodingContainer {
        var newPath = codingPath
        newPath.append(key)
        let nestedContainer = JSValueEncoderImpl.UnkeyedContainer(
            codingPath: codingPath,
            userInfo: userInfo,
            options: options
        )
        insert(.nestedContainer(nestedContainer), for: key)
        return nestedContainer
    }

    enum SuperKey: String, CodingKey {
        case `super`
    }

    func superEncoder() -> Encoder {
        let encoder = JSValueEncoderImpl(options: options)
        insert(.nestedContainer(encoder), for: SuperKey.super)
        return encoder
    }

    func superEncoder(forKey key: Key) -> Encoder {
        let encoder = JSValueEncoderImpl(options: options)
        insert(.nestedContainer(encoder), for: key)
        return encoder
    }
}

extension JSValueEncoderImpl {
    class AnyKeyedContainer: JSValueEncodingContainer {
        var data: JSValue? { object() }
        var object: () -> JSObject?

        init<Key>(_ keyedContainer: KeyedContainer<Key>) where Key: CodingKey {
            object = { keyedContainer.object }
        }
    }
}

extension JSValueEncoderImpl.KeyedContainer: JSValueEncodingContainer {
    var data: JSValue? { object }
}

extension JSValueEncoderImpl {
    final class UnkeyedContainer {
        var array: JSArray? {
            encodedUnkeyedValue?.reduce(into: []) { arr, next in
                switch next {
                case .value(let value):
                    arr.append(value)
                case .nestedContainer(let container):
                    guard let data = container.data else { return }
                    arr.append(data)
                }
            }
        }

        var codingPath: [CodingKey]
        var userInfo: CodingUserInfo
        var options: Options
        private var encodedUnkeyedValue: [EncodedValue]?

        init(codingPath: [CodingKey], userInfo: CodingUserInfo, options: Options) {
            self.codingPath = codingPath
            self.userInfo = userInfo
            self.options = options
        }
    }
}

extension JSValueEncoderImpl.UnkeyedContainer: UnkeyedEncodingContainer {
    private func append(_ value: any JSValue) {
        append(.value(value))
    }

    private func append(_ value: EncodedValue) {
        if encodedUnkeyedValue == nil {
            encodedUnkeyedValue = [value]
        } else {
            encodedUnkeyedValue?.append(value)
        }
    }

    var count: Int {
        array?.count ?? 0
    }

    func encodeNil() throws {
        append(NSNull())
    }

    func encode<T>(_ value: T) throws where T: Encodable {
        let encoder = JSValueEncoderImpl(options: options)
        try encoder.encodeGeneric(value)
        append(.nestedContainer(encoder))
    }

    func nestedUnkeyedContainer() -> UnkeyedEncodingContainer {
        let nestedContainer = JSValueEncoderImpl.UnkeyedContainer(
            codingPath: codingPath,
            userInfo: userInfo,
            options: options
        )
        append(.nestedContainer(nestedContainer))
        return nestedContainer
    }

    func nestedContainer<NestedKey>(keyedBy keyType: NestedKey.Type) -> KeyedEncodingContainer<NestedKey> where NestedKey: CodingKey {
        let nestedContainer = JSValueEncoderImpl.KeyedContainer<NestedKey>(
            codingPath: codingPath,
            userInfo: userInfo,
            options: options
        )
        append(.nestedContainer(nestedContainer))
        return KeyedEncodingContainer(nestedContainer)
    }

    func superEncoder() -> Encoder {
        let encoder = JSValueEncoderImpl(options: options)
        append(.nestedContainer(encoder))
        return encoder
    }
}

extension JSValueEncoderImpl.UnkeyedContainer: JSValueEncodingContainer {
    var data: JSValue? { array }
}

extension JSValueEncoderImpl {
    final class SingleValueContainer {
        var data: JSValue?
        var codingPath: [CodingKey]
        var userInfo: CodingUserInfo
        var options: Options

        init(codingPath: [CodingKey], userInfo: CodingUserInfo, options: Options) {
            self.codingPath = codingPath
            self.userInfo = userInfo
            self.options = options
        }
    }
}

extension JSValueEncoderImpl.SingleValueContainer: SingleValueEncodingContainer {
    func encodeNil() throws {
        data = NSNull()
    }

    func encode(_ value: Bool) throws {
        data = value
    }

    func encode(_ value: String) throws {
        data = value
    }

    func encode(_ value: Double) throws {
        try encodeFloat(value)
    }

    // swiftlint:disable force_cast
    private func encodeFloat<N>(_ value: N) throws where N: FloatingPoint {
        if value.isFinite {
            data = value as! NSNumber
        } else {
            switch options.nonConformingFloatStrategy {
            case .deferred:
                data = value as! NSNumber
            case let .convertToString(positiveInfinity: pos, negativeInfinity: neg, nan: nan):
                if value == .infinity { data = pos }
                if value == -.infinity { data = neg }
                if value.isNaN { data = nan }
            case .throw:
                throw EncodingError.invalidValue(
                    value,
                    .init(codingPath: codingPath, debugDescription: "Unable to encode \(value) to JSValue")
                )
            }
        }
    }
    // swiftlint:enable force_cast

    func encode(_ value: Float) throws {
        try encodeFloat(value)
    }

    func encode(_ value: Int) throws {
        data = value as NSNumber
    }

    func encode(_ value: Int8) throws {
        data = value as NSNumber
    }

    func encode(_ value: Int16) throws {
        data = value as NSNumber
    }

    func encode(_ value: Int32) throws {
        data = value as NSNumber
    }

    func encode(_ value: Int64) throws {
        data = value as NSNumber
    }

    func encode(_ value: UInt) throws {
        data = value as NSNumber
    }

    func encode(_ value: UInt8) throws {
        data = value as NSNumber
    }

    func encode(_ value: UInt16) throws {
        data = value as NSNumber
    }

    func encode(_ value: UInt32) throws {
        data = value as NSNumber
    }

    func encode(_ value: UInt64) throws {
        data = value as NSNumber
    }

    func encode<T>(_ value: T) throws where T: Encodable {
        let encoder = JSValueEncoderImpl(options: options)
        try encoder.encodeGeneric(value)
        data = encoder.data
    }
}

extension JSValueEncoderImpl.SingleValueContainer: JSValueEncodingContainer {}
