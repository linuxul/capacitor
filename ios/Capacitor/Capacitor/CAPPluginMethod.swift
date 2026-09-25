//
//  CAPPluginMethod.swift
//  Capacitor
//
//  Created by Steven Sherry on 4/18/24.
//  Copyright © 2024 Drifty Co. All rights reserved.
//

import Foundation

/// A method that a plugin exports to JavaScript: its name, how its result is returned, and how the bridge calls it.
///
/// Register a method by reference. It then needs no `@objc` and may be private:
///
/// ```swift
/// public let pluginMethods: [CAPPluginMethod] = [
///     .promise("echo", EchoPlugin.echo),
///     .callback("watch", EchoPlugin.watch)
/// ]
///
/// private func echo(_ call: CAPPluginCall) throws {
///     guard let value = call.getString("value") else {
///         call.reject("Must provide a value")
///         return
///     }
///     call.resolve(["value": value])
/// }
/// ```
///
/// The bridge calls these methods on its serial plugin queue, not on the main thread. An error that a method throws
/// rejects its call: a ``CAPPluginError`` with its message, code and data, any other error with its description.
///
/// An `async` method is registered with `async`. Returning resolves the call, with the `JSObject` or `Encodable` value
/// the method returns, if any:
///
/// ```swift
/// public let pluginMethods: [CAPPluginMethod] = [
///     .async("pickPhoto", CameraPlugin.pickPhoto)
/// ]
///
/// @MainActor
/// private func pickPhoto(_ call: CAPPluginCall) async throws -> Photo {
///     let picker = PhotoPicker(presentingFrom: bridge?.viewController)
///     return try await picker.pick()
/// }
/// ```
///
/// A method runs on the main thread by being `@MainActor` and registered with `async`, whether the method itself is
/// `async` or not. A synchronous `@MainActor` method registered with `promise`, `callback` or `none` runs on the
/// bridge queue: in the Swift 5 language mode the compiler accepts it without a warning.
///
/// A method is immutable and safe to share between threads: what it calls captures only the method reference it was
/// made from.
public struct CAPPluginMethod: @unchecked Sendable {
    /// How the result of the method is returned to JavaScript. The raw values are part of the JS protocol and must not change.
    public enum ReturnType: String {
        case promise, callback, none
    }

    /// The name of the method in JavaScript.
    public let name: String
    /// Return type of method (i.e. callback/promise/none)
    public let returnType: ReturnType
    /// How the bridge calls the method.
    internal let invocation: Invocation

    internal enum Invocation {
        /// Performed on the plugin through the Obj-C runtime, the way fork 8.5.3 called every method.
        case selector(Selector)
        /// Called on the bridge queue. What it throws rejects the call.
        case function((CAPPlugin, CAPPluginCall) throws -> Void)
        /// Started from the bridge queue in a task the bridge tracks. It returns the data to resolve the call with, or
        /// nil when the method returned nothing.
        case async((CAPPlugin, CAPPluginCall) async throws -> PluginCallResultData?)
    }

    internal init(name: String, returnType: ReturnType, invocation: Invocation) {
        self.name = name
        self.returnType = returnType
        self.invocation = invocation
    }

    // MARK: - Methods registered by reference

    /// A method whose call JavaScript awaits as a promise. It settles the call once, with `resolve` or `reject`, or by throwing.
    ///
    /// - Parameters:
    ///   - name: The name of the method in JavaScript.
    ///   - method: The method, as an unapplied reference such as `EchoPlugin.echo`.
    public static func promise<Plugin: CAPPlugin>(_ name: String, _ method: @escaping (Plugin) -> (CAPPluginCall) throws -> Void) -> CAPPluginMethod {
        function(name, .promise, method)
    }

    /// A method that answers through a callback, any number of times. It keeps its call alive with `call.keepAlive = true`,
    /// and the bridge saves the call when the method returns.
    ///
    /// - Parameters:
    ///   - name: The name of the method in JavaScript.
    ///   - method: The method, as an unapplied reference such as `GeolocationPlugin.watchPosition`.
    public static func callback<Plugin: CAPPlugin>(_ name: String, _ method: @escaping (Plugin) -> (CAPPluginCall) throws -> Void) -> CAPPluginMethod {
        function(name, .callback, method)
    }

    /// A method whose call JavaScript does not wait for. Nothing it sends reaches the page.
    ///
    /// - Parameters:
    ///   - name: The name of the method in JavaScript.
    ///   - method: The method, as an unapplied reference such as `ConsolePlugin.log`.
    public static func none<Plugin: CAPPlugin>(_ name: String, _ method: @escaping (Plugin) -> (CAPPluginCall) throws -> Void) -> CAPPluginMethod {
        function(name, .none, method)
    }

    private static func function<Plugin: CAPPlugin>(_ name: String, _ returnType: ReturnType,
                                                    _ method: @escaping (Plugin) -> (CAPPluginCall) throws -> Void) -> CAPPluginMethod {
        CAPPluginMethod(name: name, returnType: returnType, invocation: .function({ plugin, call in
            guard let plugin = plugin as? Plugin else {
                throw mismatch(name, expected: Plugin.self, actual: plugin)
            }
            try method(plugin)(call)
        }))
    }

    // MARK: - Async methods

    /// An `async` method whose call JavaScript awaits as a promise. When the method returns, the call is resolved
    /// without data, unless the method settled it already.
    ///
    /// The method starts from the bridge queue in a `Task` of its own, so async methods do not wait for each other and
    /// may finish in any order. When the page reloads or navigates, the bridge rejects the calls that are still running
    /// with "The plugin call was cancelled" and cancels their tasks; what a method sends after that is dropped.
    ///
    /// - Parameters:
    ///   - name: The name of the method in JavaScript.
    ///   - method: The method, as an unapplied reference such as `CameraPlugin.pickPhoto`. A `@MainActor` method runs
    ///     on the main actor.
    public static func async<Plugin: CAPPlugin>(_ name: String,
                                                _ method: @escaping (Plugin) -> (CAPPluginCall) async throws -> Void) -> CAPPluginMethod {
        asyncFunction(name, method) { _ in nil }
    }

    /// An `async` method that resolves its call with the object it returns.
    ///
    /// The method is run like one that returns nothing.
    public static func async<Plugin: CAPPlugin>(_ name: String,
                                                _ method: @escaping (Plugin) -> (CAPPluginCall) async throws -> JSObject) -> CAPPluginMethod {
        asyncFunction(name, method) { $0 }
    }

    /// An `async` method that resolves its call with the value it returns, encoded with `JSValueEncoder` into an object.
    /// A value that cannot be encoded as an object rejects the call with "Failed encoding response".
    ///
    /// The method is run like one that returns nothing.
    public static func async<Plugin: CAPPlugin, Value: Encodable>(
        _ name: String,
        _ method: @escaping (Plugin) -> (CAPPluginCall) async throws -> Value
    ) -> CAPPluginMethod {
        asyncFunction(name, method) { value in
            do {
                return try JSValueEncoder().encodeJSObject(value)
            } catch {
                throw CAPPluginError("Failed encoding response", underlyingError: error)
            }
        }
    }

    private static func asyncFunction<Plugin: CAPPlugin, Value>(
        _ name: String,
        _ method: @escaping (Plugin) -> (CAPPluginCall) async throws -> Value,
        _ data: @escaping (Value) throws -> PluginCallResultData?
    ) -> CAPPluginMethod {
        CAPPluginMethod(name: name, returnType: .promise, invocation: .async({ plugin, call in
            guard let plugin = plugin as? Plugin else {
                throw mismatch(name, expected: Plugin.self, actual: plugin)
            }
            return try data(await method(plugin)(call))
        }))
    }

    /// Why a method registered with a method of `expected` cannot be called on `actual`.
    private static func mismatch(_ name: String, expected: CAPPlugin.Type, actual: CAPPlugin) -> CAPPluginError {
        .unimplemented("Method \(name) is registered with a method of \(expected), which the plugin \(type(of: actual)) is not")
    }

    // MARK: - Methods registered by selector

    /// Registers the method `name:`, which the bridge performs through the Obj-C runtime; the method must be `@objc`.
    @available(*, deprecated, message: "Register the method by reference, for example .promise(\"echo\", EchoPlugin.echo); it then needs no @objc")
    public init(name: String, returnType: ReturnType = .promise) {
        self.init(name: name, returnType: returnType, invocation: .selector(NSSelectorFromString(name + ":")))
    }

    /// Registers the method that `selector` names; the method must be `@objc`.
    @available(*, deprecated, message: "Register the method by reference, for example .promise(\"echo\", EchoPlugin.echo); it then needs no @objc")
    public init(_ selector: Selector, returnType: ReturnType = .promise) {
        // need to drop the : from the selector string
        let rawSelector = NSStringFromSelector(selector)
        let name = rawSelector.hasSuffix(":") ? String(rawSelector.dropLast()) : rawSelector
        self.init(name: name, returnType: returnType, invocation: .selector(selector))
    }

    /// The selector the bridge performs on the plugin, of the form `name:`, for a method registered by selector; nil for
    /// a method registered by reference.
    @available(*, deprecated, message: "Only methods registered by selector have one; methods registered by reference need no @objc")
    public var selector: Selector? {
        guard case .selector(let selector) = invocation else {
            return nil
        }
        return selector
    }
}
