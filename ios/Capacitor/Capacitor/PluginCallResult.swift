import Foundation

public typealias PluginCallResultData = [String: Any]

/// A successful result of a plugin call, as the call's success handler receives it.
///
/// Results are immutable and hold JSON values (strings, numbers, booleans, `NSNull`, dates, arrays and dictionaries of
/// them), so they may be passed between threads.
public struct CAPPluginCallResult: @unchecked Sendable {
    /// The data the call was resolved with, or nil when it was resolved without data (JavaScript receives `undefined`).
    public let data: PluginCallResultData?

    public init(_ data: PluginCallResultData?) {
        self.data = data
    }
}

/// A failed result of a plugin call, as the call's error handler receives it.
///
/// Like ``CAPPluginCallResult``, it is immutable and its data holds JSON values.
public struct CAPPluginCallError: @unchecked Sendable {
    /// The message JavaScript receives as the error's `message`.
    public let message: String
    /// The code JavaScript receives as the error's `code`.
    public let code: String?
    /// The error that caused the rejection, for diagnostics. It is not sent to JavaScript.
    public let error: Error?
    /// The data the call was rejected with, which JavaScript receives as the error's `data`.
    public let data: PluginCallResultData?

    public init(message: String, code: String?, error: Error?, data: PluginCallResultData?) {
        self.message = message
        self.code = code
        self.error = error
        self.data = data
    }
}

/// Serializes result data into the JSON the bridge sends to the page.
internal enum PluginCallResultJSON {
    enum SerializationError: Error {
        case invalidObject
    }

    /// The JSON for `dictionary`, with `Date` values written as ISO 8601 strings. Keys of `includingFields` are added
    /// unless `dictionary` has them already.
    static func serialize(_ dictionary: PluginCallResultData, includingFields fields: PluginCallResultData? = nil) throws -> String? {
        var dictionary = dictionary
        if let fields {
            dictionary.merge(fields) { (current, _) in current }
        }
        dictionary = prepare(dictionary: dictionary)
        guard JSONSerialization.isValidJSONObject(dictionary) else {
            throw SerializationError.invalidObject
        }
        let data = try JSONSerialization.data(withJSONObject: dictionary, options: [])
        return String(data: data, encoding: .utf8)
    }

    private static func prepare(dictionary: PluginCallResultData) -> PluginCallResultData {
        return dictionary.mapValues(prepare(value:))
    }

    private static func prepare(value: Any) -> Any {
        if let date = value as? Date {
            return JSDateFormat.string(from: date)
        } else if let aDictionary = value as? PluginCallResultData {
            return prepare(dictionary: aDictionary)
        } else if let anArray = value as? [Any] {
            return anArray.map(prepare(value:))
        }
        return value
    }
}
