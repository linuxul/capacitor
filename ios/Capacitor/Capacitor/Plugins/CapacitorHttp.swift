import Foundation

/// A plugin that performs the requests of the `CapacitorHttp` plugin in its place, for example to pin TLS certificates.
///
/// `CapacitorHttp` hands every request to the first registered plugin that conforms, in registration order, and
/// performs the request itself when none does. A handler that only wants to change some requests can pass the others
/// to `HttpRequestHandler.request(_:_:_:)`, which is what `CapacitorHttp` calls by default.
public protocol CapacitorHttpRequestHandling: AnyObject {
    /// Performs the request that `call` describes and settles `call`.
    ///
    /// Called on the bridge queue.
    ///
    /// - Parameters:
    ///   - call: The call of `request`, `get`, `post`, `put`, `patch` or `delete`, whose options are the `HttpOptions` of
    ///     JavaScript.
    ///   - httpMethod: The method of `get` and the others (`"GET"` and so on), or nil for `request`, which takes the
    ///     method from its `method` option.
    ///   - config: The configuration of the bridge, for its cookie and server settings.
    /// - Throws: An error that rejects the call.
    func performHttpRequest(_ call: CAPPluginCall, httpMethod: String?, config: InstanceConfiguration?) throws
}

@objc(CAPHttpPlugin)
public class CAPHttpPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "CAPHttpPlugin"
    public let jsName = "CapacitorHttp"
    public let pluginMethods: [CAPPluginMethod] = [
        .promise("request", CAPHttpPlugin.request),
        .promise("get", CAPHttpPlugin.get),
        .promise("post", CAPHttpPlugin.post),
        .promise("put", CAPHttpPlugin.put),
        .promise("patch", CAPHttpPlugin.patch),
        .promise("delete", CAPHttpPlugin.delete)
    ]

    func http(_ call: CAPPluginCall, _ httpMethod: String?) throws {
        let config = bridge?.config
        if let handler = requestHandler {
            try handler.performHttpRequest(call, httpMethod: httpMethod, config: config)
        } else {
            try HttpRequestHandler.request(call, httpMethod, config)
        }
    }

    /// The first registered plugin that handles the requests of this one.
    private var requestHandler: CapacitorHttpRequestHandling? {
        guard let bridge = bridge as? CapacitorBridge else {
            return nil
        }
        return bridge.pluginRegistry.all.lazy.compactMap { $0 as? CapacitorHttpRequestHandling }.first
    }

    func request(_ call: CAPPluginCall) throws {
        try http(call, nil)
    }

    func get(_ call: CAPPluginCall) throws {
        try http(call, "GET")
    }

    func post(_ call: CAPPluginCall) throws {
        try http(call, "POST")
    }

    func put(_ call: CAPPluginCall) throws {
        try http(call, "PUT")
    }

    func patch(_ call: CAPPluginCall) throws {
        try http(call, "PATCH")
    }

    func delete(_ call: CAPPluginCall) throws {
        try http(call, "DELETE")
    }
}
