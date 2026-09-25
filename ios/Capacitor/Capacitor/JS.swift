import Foundation

/**
 * A call originating from JavaScript land
 */
internal struct JSCall {
    let options: [String: Any]
    let pluginId: String
    let method: String
    let callbackId: String
}

internal protocol JSResultProtocol {
    var call: JSCall { get }
    var callbackID: String { get }
    var pluginID: String { get }
    var methodName: String { get }
    func jsonPayload() -> String
}

internal extension JSResultProtocol {
    var callbackID: String {
        return call.callbackId
    }

    var pluginID: String {
        return call.pluginId
    }

    var methodName: String {
        return call.method
    }
}

private enum SerializationResult: String {
    case undefined = "undefined"
    case empty = "{}"
}

/**
 * A result of processing a JSCall, contains
 * a reference to the original call and the new result.
 */

internal struct JSResult: JSResultProtocol {
    let call: JSCall
    /// The data the call was resolved with; nil sends `undefined`.
    let data: PluginCallResultData?

    func jsonPayload() -> String {
        guard let data = data else {
            return SerializationResult.undefined.rawValue
        }
        do {
            if let payload = try PluginCallResultJSON.serialize(data) {
                return payload
            }
        } catch PluginCallResultJSON.SerializationError.invalidObject {
            CAPLog.print("[Capacitor Plugin Error] - \(call.pluginId) - \(call.method) - Unable to serialize plugin response as JSON." +
                            "Ensure that all data passed to success callback from module method is JSON serializable!")
        } catch {
            CAPLog.print("Unable to serialize plugin response as JSON: \(error.localizedDescription)")
        }
        return SerializationResult.empty.rawValue
    }
}

internal extension JSResult {
    init(call: JSCall, callResult: CAPPluginCallResult) {
        self.call = call
        self.data = callResult.data
    }
}

internal struct JSResultError: JSResultProtocol {
    let call: JSCall
    let errorMessage: String
    let errorDescription: String
    let errorCode: String?
    /// The members of the error besides its message and code: `data`, when the call was rejected with data.
    let result: PluginCallResultData

    func jsonPayload() -> String {
        var errorDictionary: [String: Any] = [
            "message": self.errorMessage,
            "errorMessage": self.errorMessage
        ]
        errorDictionary["code"] = self.errorCode

        do {
            if let payload = try PluginCallResultJSON.serialize(result, includingFields: errorDictionary) {
                CAPLog.print("ERROR MESSAGE: ", payload.prefix(512))
                return payload
            }
        } catch PluginCallResultJSON.SerializationError.invalidObject {
            CAPLog.print("[Capacitor Plugin Error] - \(call.pluginId) - \(call.method) - Unable to serialize plugin response as JSON." +
                            "Ensure that all data passed to success callback from module method is JSON serializable!")
        } catch {
            CAPLog.print("Unable to serialize plugin response as JSON: \(error.localizedDescription)")
        }
        return SerializationResult.empty.rawValue
    }
}

internal extension JSResultError {
    init(call: JSCall, callError: CAPPluginCallError) {
        self.call = call
        errorMessage = callError.message
        errorDescription = callError.error?.localizedDescription ?? ""
        errorCode = callError.code
        result = callError.data.map { ["data": $0] } ?? [:]
    }
}

/**
 * The JavaScript the bridge evaluates in the web view.
 *
 * Plugin ids, method names and callback ids come from the page, and event names and log messages from plugins, so
 * every string is written as a JSON literal instead of being interpolated between quotes, where a quote or a line
 * break would end the literal early.
 */
internal enum BridgeScript {
    /// `window.Capacitor.fromNative({...})` for a call result. `payload` is already JavaScript (serialized JSON or
    /// `undefined`) and becomes the value of `data` on success and of `error` on failure.
    static func fromNative(_ result: JSResultProtocol, success: Bool, save: Bool, payload: String) -> String {
        let envelope: [String: Any] = [
            "callbackId": result.callbackID,
            "pluginId": result.pluginID,
            "methodName": result.methodName,
            "save": save,
            "success": success
        ]
        // the envelope is serialized with sorted keys and the payload is spliced in as the last member
        let members = literal(envelope).dropLast()
        return "window.Capacitor.fromNative(\(members),\(literal(success ? "data" : "error")):\(payload)})"
    }

    /// `window.Capacitor.triggerEvent(...)`. `data` is JavaScript supplied by the caller and is passed as is.
    static func triggerEvent(_ eventName: String, target: String, data: String? = nil) -> String {
        let arguments = [literal(eventName), literal(target)] + (data.map { [$0] } ?? [])
        return "window.Capacitor.triggerEvent(\(arguments.joined(separator: ", ")))"
    }

    static func logJs(_ message: String, level: String) -> String {
        "window.Capacitor.logJs(\(literal(message)), \(literal(level)))"
    }

    /// Runs `js`, which is JavaScript supplied by the caller, with the plugin `pluginId` bound to `plugin`.
    static func withPlugin(_ pluginId: String, js: String) -> String { // swiftlint:disable:this identifier_name
        let id = literal(pluginId)
        return """
        window.Capacitor.withPlugin(\(id), function(plugin) {
        if(!plugin) { console.error('Unable to execute JS in plugin, no such plugin found for id ' + \(id)); }
        \(js)
        });
        """
    }

    /// A JavaScript literal for a string, a number, a boolean or a JSON compatible collection.
    static func literal(_ value: Any) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed, .sortedKeys]),
              let json = String(data: data, encoding: .utf8) else {
            // only reachable for values JSON cannot represent, which the bridge never passes
            return "null"
        }
        return json
    }
}
