//
//  JSValueDecoder+Containers.swift
//  Capacitor
//
//  Created by Steven Sherry on 12/8/23.
//  Copyright © 2023 Drifty Co. All rights reserved.
//

import Foundation

// The containers a `JSValueDecoderImpl` hands out, moved here from JSValueDecoder.swift. They are nested in it so that
// their names do not clash with the containers of `JSValueEncoderImpl`.

extension JSValueDecoderImpl {
    final class KeyedContainer<Key> where Key: CodingKey {
        var data: JSObject
        var codingPath: [CodingKey]
        var userInfo: CodingUserInfo
        var allKeys: [Key]
        var options: Options

        init(data: JSObject, codingPath: [CodingKey], userInfo: CodingUserInfo, options: Options) {
            self.data = data
            self.codingPath = codingPath
            self.userInfo = userInfo
            self.allKeys = data.keys.compactMap(Key.init(stringValue:))
            self.options = options
        }
    }
}

extension JSValueDecoderImpl.KeyedContainer: KeyedDecodingContainerProtocol {
    func contains(_ key: Key) -> Bool {
        allKeys.contains { $0.stringValue == key.stringValue }
    }

    func decodeNil(forKey key: Key) throws -> Bool {
        data[key.stringValue] == nil || data[key.stringValue] is NSNull
    }

    func decode<T>(_ type: T.Type, forKey key: Key) throws -> T where T: Decodable {
        guard let rawValue = data[key.stringValue] else {
            throw DecodingError.keyNotFound(key, on: data, codingPath: codingPath)
        }

        var newPath = codingPath
        newPath.append(key)
        let decoder = JSValueDecoderImpl(data: rawValue, options: options, codingPath: newPath)
        return try decoder.decodeData(as: T.self)
    }

    func nestedUnkeyedContainer(forKey key: Key) throws -> UnkeyedDecodingContainer {
        var newPath = codingPath
        newPath.append(key)
        guard let data = data[key.stringValue] as? JSArray else {
            throw DecodingError.typeMismatch(
                JSArray.self,
                on: data[key.stringValue] ?? "null value",
                codingPath: newPath
            )
        }

        return JSValueDecoderImpl.UnkeyedContainer(data: data, codingPath: newPath, userInfo: userInfo, options: options)
    }

    func nestedContainer<NestedKey>(keyedBy type: NestedKey.Type, forKey key: Key) throws -> KeyedDecodingContainer<NestedKey> where NestedKey: CodingKey {
        var newPath = codingPath
        newPath.append(key)
        guard let data = data[key.stringValue] as? JSObject else {
            throw DecodingError.typeMismatch(
                JSObject.self,
                on: data[key.stringValue] ?? "null value",
                codingPath: newPath
            )
        }

        let container = JSValueDecoderImpl.KeyedContainer<NestedKey>(data: data, codingPath: newPath, userInfo: userInfo, options: options)
        return KeyedDecodingContainer(container)
    }

    enum SuperKey: String, CodingKey { case `super` }

    func superDecoder() throws -> Decoder {
        var newPath = codingPath
        newPath.append(SuperKey.super)
        guard let data = data[SuperKey.super.stringValue] else {
            throw DecodingError.keyNotFound(SuperKey.super, on: data, codingPath: newPath)
        }

        return JSValueDecoderImpl(data: data, options: options, codingPath: newPath)
    }

    func superDecoder(forKey key: Key) throws -> Decoder {
        var newPath = codingPath
        newPath.append(key)
        guard let data = data[key.stringValue] else {
            throw DecodingError.keyNotFound(key, on: data, codingPath: newPath)
        }

        return JSValueDecoderImpl(data: data, options: options, codingPath: newPath)
    }
}

extension JSValueDecoderImpl {
    final class UnkeyedContainer {
        var data: JSArray
        var codingPath: [CodingKey]
        var userInfo: CodingUserInfo
        private(set) var currentIndex = 0
        var options: Options

        init(data: JSArray, codingPath: [CodingKey], userInfo: CodingUserInfo, options: Options) {
            self.data = data
            self.codingPath = codingPath
            self.userInfo = userInfo
            self.options = options
        }
    }
}

extension JSValueDecoderImpl.UnkeyedContainer: UnkeyedDecodingContainer {
    var count: Int? {
        data.count
    }

    var isAtEnd: Bool {
        currentIndex >= data.endIndex
    }

    /// The coding path of the element at ``currentIndex``.
    private var currentPath: [CodingKey] {
        codingPath + [JSValueDecoderImpl.IndexKey(currentIndex)]
    }

    /// Returns the element at ``currentIndex`` without consuming it. The index only advances once the element has been
    /// decoded, so a failed decode leaves the container where it was, the same as `JSONDecoder`.
    private func peek<T>(_ type: T.Type) throws -> JSValue {
        guard !isAtEnd else {
            throw DecodingError.valueNotFound(type, .init(codingPath: currentPath, debugDescription: "Unkeyed container is at end."))
        }
        return data[currentIndex]
    }

    func decodeNil() throws -> Bool {
        guard try peek(Any?.self) is NSNull else {
            return false
        }
        currentIndex += 1
        return true
    }

    func decode<T>(_ type: T.Type) throws -> T where T: Decodable {
        let decoder = JSValueDecoderImpl(data: try peek(type), options: options, codingPath: currentPath)
        let value = try decoder.decodeData(as: T.self)
        currentIndex += 1
        return value
    }

    func nestedUnkeyedContainer() throws -> UnkeyedDecodingContainer {
        let value = try peek(UnkeyedDecodingContainer.self)
        guard let data = value as? JSArray else {
            throw DecodingError.typeMismatch(JSArray.self, on: value, codingPath: currentPath)
        }

        let container = JSValueDecoderImpl.UnkeyedContainer(data: data, codingPath: currentPath, userInfo: userInfo, options: options)
        currentIndex += 1
        return container
    }

    func nestedContainer<NestedKey>(keyedBy type: NestedKey.Type) throws -> KeyedDecodingContainer<NestedKey> where NestedKey: CodingKey {
        let value = try peek(KeyedDecodingContainer<NestedKey>.self)
        guard let data = value as? JSObject else {
            throw DecodingError.typeMismatch(JSObject.self, on: value, codingPath: currentPath)
        }

        let container = JSValueDecoderImpl.KeyedContainer<NestedKey>(data: data, codingPath: currentPath, userInfo: userInfo, options: options)
        currentIndex += 1
        return KeyedDecodingContainer(container)
    }

    func superDecoder() throws -> Decoder {
        let decoder = JSValueDecoderImpl(data: try peek(Decoder.self), options: options, codingPath: currentPath)
        currentIndex += 1
        return decoder
    }
}

extension JSValueDecoderImpl {
    /// The coding key of an element of an unkeyed container, which is identified by its position.
    struct IndexKey: CodingKey {
        let intValue: Int?
        let stringValue: String

        init(_ index: Int) {
            intValue = index
            stringValue = "Index \(index)"
        }

        init?(stringValue: String) {
            return nil
        }

        init?(intValue: Int) {
            self.init(intValue)
        }
    }
}

extension JSValueDecoderImpl {
    final class SingleValueContainer {
        var data: JSValue
        var codingPath: [CodingKey]
        var userInfo: CodingUserInfo
        var options: Options

        init(data: JSValue, codingPath: [CodingKey], userInfo: CodingUserInfo, options: Options) {
            self.data = data
            self.codingPath = codingPath
            self.userInfo = userInfo
            self.options = options
        }
    }
}

extension JSValueDecoderImpl.SingleValueContainer: SingleValueDecodingContainer {
    func decodeNil() -> Bool {
        return data is NSNull
    }

    private func cast<T>(to type: T.Type) throws -> T {
        guard let data = data as? T else {
            throw DecodingError.typeMismatch(type, on: data, codingPath: codingPath)
        }

        return data
    }

    private func castFloat<N>(to type: N.Type) throws -> N where N: FloatingPoint {
        if let data = data as? String,
           case let .convertFromString(positiveInfinity: pos, negativeInfinity: neg, nan: nan) = options.nonConformingStrategy {
            switch data {
            case pos:
                return N.infinity
            case neg:
                return -N.infinity
            case nan:
                return N.nan
            default:
                throw DecodingError.typeMismatch(type, on: data, codingPath: codingPath)
            }
        }

        let data = try cast(to: N.self)
        if !data.isFinite, case .throw = options.nonConformingStrategy {
            throw DecodingError.dataCorrupted(.init(codingPath: codingPath, debugDescription: "\(data) is a non-conforming floating point number"))
        }
        return data
    }

    func decode(_ type: Bool.Type) throws -> Bool {
        try cast(to: type)
    }

    func decode(_ type: String.Type) throws -> String {
        try cast(to: type)
    }

    func decode(_ type: Double.Type) throws -> Double {
        try castFloat(to: type)
    }

    func decode(_ type: Float.Type) throws -> Float {
        try castFloat(to: type)
    }

    func decode(_ type: Int.Type) throws -> Int {
        try cast(to: type)
    }

    func decode(_ type: Int8.Type) throws -> Int8 {
        try cast(to: type)
    }

    func decode(_ type: Int16.Type) throws -> Int16 {
        try cast(to: type)
    }

    func decode(_ type: Int32.Type) throws -> Int32 {
        try cast(to: type)
    }

    func decode(_ type: Int64.Type) throws -> Int64 {
        try cast(to: type)
    }

    func decode(_ type: UInt.Type) throws -> UInt {
        try cast(to: type)
    }

    func decode(_ type: UInt8.Type) throws -> UInt8 {
        try cast(to: type)
    }

    func decode(_ type: UInt16.Type) throws -> UInt16 {
        try cast(to: type)
    }

    func decode(_ type: UInt32.Type) throws -> UInt32 {
        try cast(to: type)
    }

    func decode(_ type: UInt64.Type) throws -> UInt64 {
        try cast(to: type)
    }

    func decode<T>(_ type: T.Type) throws -> T where T: Decodable {
        let decoder = JSValueDecoderImpl(data: data, options: options, codingPath: codingPath)
        return try decoder.decodeData(as: T.self)
    }
}
