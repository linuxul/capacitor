import Foundation

public typealias CAPPluginCallSuccessHandler = (_ result: CAPPluginCallResult, _ call: CAPPluginCall) -> Void
public typealias CAPPluginCallErrorHandler = (_ error: CAPPluginCallError) -> Void

/// A single invocation of a plugin method from JavaScript.
///
/// The class stays visible to the Obj-C runtime under this name because methods registered by selector take it as
/// their argument; its members are Swift only.
@objc(CAPPluginCall)
open class CAPPluginCall: NSObject {
    /// Whether the call should be retained by the bridge after the plugin method returns so that it can be resolved later or repeatedly.
    ///
    /// Safe to read and write from any thread: plugins set it on the bridge queue or in completion handlers, while the
    /// bridge reads it when it saves the call and whenever a result is sent.
    public var keepAlive: Bool {
        get { stateLock.withLock { lockedKeepAlive } }
        set { stateLock.withLock { lockedKeepAlive = newValue } }
    }
    public let callbackId: String
    public let methodName: String
    public let options: JSObject
    /// The raw handlers behind ``resolve()`` and ``reject(_:_:_:_:)``. Calling them directly bypasses the rule that a
    /// call settles once; ``CAPPlugin/notifyListeners(_:data:)`` does so to deliver every event to a listener.
    public let successHandler: CAPPluginCallSuccessHandler
    public let errorHandler: CAPPluginCallErrorHandler

    /// The JavaScript name of the plugin the call was made to, set by the bridge for diagnostics.
    internal var pluginName: String?

    /// Guards the mutable state of the call.
    private let stateLock = NSLock()
    private var lockedKeepAlive = false
    private var settled = false

    public init(callbackId: String, methodName: String, options: JSObject, success: @escaping CAPPluginCallSuccessHandler, error: @escaping CAPPluginCallErrorHandler) {
        self.callbackId = callbackId
        self.methodName = methodName
        self.options = options
        self.successHandler = success
        self.errorHandler = error
        super.init()
    }

    /// Claims the right to send a result. A call that is not kept alive settles once: the first resolve or reject is
    /// sent and every later one is dropped and logged, because the page has already released the promise. A call that is
    /// kept alive may send any number of results.
    internal func claimSettlement(_ attempt: StaticString) -> Bool {
        let claimed: Bool = stateLock.withLock {
            if lockedKeepAlive {
                return true
            }
            if settled {
                return false
            }
            settled = true
            return true
        }
        if !claimed {
            CAPLog.print("⚡️  \(pluginName ?? "Plugin").\(methodName) (callbackId \(callbackId)) already settled; dropping \(attempt)")
        }
        return claimed
    }

    /// Whether a call that is not kept alive has sent its result. A call that is kept alive never settles.
    internal var isSettled: Bool {
        stateLock.withLock { settled }
    }

    /// Resolves the call without data, unless it has settled or is kept alive. Nothing is logged when it is not
    /// resolved: this is how the bridge answers an async method that returned nothing.
    internal func resolveIfUnsettled() {
        if claimUnsettled() {
            successHandler(CAPPluginCallResult(nil), self)
        }
    }

    /// Rejects the call with `message`, unless it has settled or is kept alive, without logging when it is not rejected.
    internal func rejectIfUnsettled(_ message: String) {
        if claimUnsettled() {
            errorHandler(CAPPluginCallError(message: message, code: nil, error: nil, data: nil))
        }
    }

    private func claimUnsettled() -> Bool {
        stateLock.withLock {
            if lockedKeepAlive || settled {
                return false
            }
            settled = true
            return true
        }
    }
}

extension CAPPluginCall: JSValueContainer {
    public var jsObjectRepresentation: JSObject {
        return options
    }

    /// Parses the ISO 8601 strings that `getDate(_:)` reads. Formatters are thread-safe, so one is shared by every call.
    /// It is not the runtime's own formatter, so changing its options changes only what `getDate(_:)` accepts.
    public static let jsDateFormatter = JSDateFormat.makeFormatter()
}

public extension CAPPluginCall {
    // Unless the call is kept alive, only the first resolve, reject, unimplemented or unavailable is sent.

    /// Resolves the call with no data. JavaScript receives `undefined`; use `resolve([:])` to send `{}`.
    func resolve() {
        guard claimSettlement("resolve()") else { return }
        successHandler(CAPPluginCallResult(nil), self)
    }

    /// Resolves the call with `data`.
    func resolve(_ data: PluginCallResultData) {
        guard claimSettlement("resolve(_:)") else { return }
        successHandler(CAPPluginCallResult(data), self)
    }

    func reject(_ message: String, _ code: String? = nil, _ error: Error? = nil, _ data: PluginCallResultData? = nil) {
        guard claimSettlement("reject(_:_:_:_:)") else { return }
        errorHandler(CAPPluginCallError(message: message, code: code, error: error, data: data))
    }

    func unimplemented() {
        unimplemented("not implemented")
    }

    func unimplemented(_ message: String) {
        guard claimSettlement("unimplemented(_:)") else { return }
        errorHandler(CAPPluginCallError(message: message, code: "UNIMPLEMENTED", error: nil, data: [:]))
    }

    func unavailable() {
        unavailable("not available")
    }

    func unavailable(_ message: String) {
        guard claimSettlement("unavailable(_:)") else { return }
        errorHandler(CAPPluginCallError(message: message, code: "UNAVAILABLE", error: nil, data: [:]))
    }
}

// MARK: Codable Support
public extension CAPPluginCall {
    /// Encodes the given value to a ``JSObject`` and resolves the call. If an error is thrown during encoding, ``reject(_:_:_:_:)`` is called.
    /// - Parameters:
    ///   - data: The value to encode
    ///   - encoder: The encoder to use. Defaults to `JSValueEncoder()`
    ///   - messageForRejectionFromError: A closure that takes the error thrown from ``JSValueEncoder/encodeJSObject(_:)``
    ///   and returns a string to be provided to ``reject(_:_:_:_:)``. Defaults to a function that returns "Failed encoding response".
    func resolve<T: Encodable>(
        with data: T,
        encoder: JSValueEncoder = JSValueEncoder(),
        messageForRejectionFromError: (Error) -> String = { _ in "Failed encoding response" }
    ) {
        do {
            let encoded = try encoder.encodeJSObject(data)
            resolve(encoded)
        } catch {
            let message = messageForRejectionFromError(error)
            reject(message, nil, error)
        }
    }

    /// Decodes the options to the given type.
    /// - Parameters:
    ///   - type: The type to decode to.
    ///   - decoder: The decoder to use. Defaults to `JSValueDecoder()`.
    /// - Throws: If the options cannot be decoded.
    /// - Returns: The decoded value.
    func decode<T: Decodable>(_ type: T.Type, decoder: JSValueDecoder = JSValueDecoder()) throws -> T {
        try decoder.decode(type, from: options)
    }
}
