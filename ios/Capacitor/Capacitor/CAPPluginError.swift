import Foundation

/// An error that rejects the plugin call it is thrown from, with its message, code and data.
///
/// Throw it from a plugin method registered by reference (`.promise`, `.callback`, `.none` or `.async`), or pass it to
/// ``CAPPluginCall/reject(_:)``. The call is rejected as `call.reject(message, code, underlyingError, data)` would
/// reject it. Any other error a method throws rejects the call with its `localizedDescription`.
///
/// ```swift
/// private func open(_ call: CAPPluginCall) throws {
///     guard let path = call.getString("path") else {
///         throw CAPPluginError("Must provide a path", code: "INVALID_ARGUMENT")
///     }
///     ...
/// }
/// ```
public struct CAPPluginError: Error {
    /// The message JavaScript receives as the error's `message`.
    public var message: String
    /// The code JavaScript receives as the error's `code`.
    public var code: String?
    /// The data JavaScript receives as the error's `data`.
    public var data: PluginCallResultData?
    /// The error that caused this one, for diagnostics. It is not sent to JavaScript.
    public var underlyingError: Error?

    public init(_ message: String, code: String? = nil, data: PluginCallResultData? = nil, underlyingError: Error? = nil) {
        self.message = message
        self.code = code
        self.data = data
        self.underlyingError = underlyingError
    }

    /// The error ``CAPPluginCall/unimplemented(_:)`` rejects with: the method is not implemented on this platform.
    public static func unimplemented(_ message: String = "not implemented") -> CAPPluginError {
        CAPPluginError(message, code: "UNIMPLEMENTED", data: [:])
    }

    /// The error ``CAPPluginCall/unavailable(_:)`` rejects with: the method cannot be used right now, for example
    /// because the device lacks the hardware.
    public static func unavailable(_ message: String = "not available") -> CAPPluginError {
        CAPPluginError(message, code: "UNAVAILABLE", data: [:])
    }
}

extension CAPPluginError: LocalizedError {
    public var errorDescription: String? {
        message
    }
}

public extension CAPPluginCall {
    /// Rejects the call with what `error` describes: a ``CAPPluginError`` with its message, code and data; any other
    /// error with its `localizedDescription`. A `CancellationError` rejects with "The plugin call was cancelled".
    ///
    /// Unless the call is kept alive, this is ignored when the call has settled already.
    func reject(_ error: Error) {
        guard claimSettlement("reject(_:) with an error") else { return }
        errorHandler(CAPPluginCallError(rejecting: error))
    }
}

extension CAPPluginCallError {
    /// The error result for a call that is rejected with `error`.
    init(rejecting error: Error) {
        switch error {
        case let pluginError as CAPPluginError:
            self.init(message: pluginError.message, code: pluginError.code, error: pluginError.underlyingError ?? pluginError, data: pluginError.data)
        case is CancellationError:
            self.init(message: "The plugin call was cancelled", code: nil, error: error, data: nil)
        default:
            self.init(message: error.localizedDescription, code: nil, error: error, data: nil)
        }
    }
}
