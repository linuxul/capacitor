import Foundation

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

    func http(_ call: CAPPluginCall, _ httpMethod: String?) {
        do {
            if let clazz = NSClassFromString("SSLPinningHttpRequestHandlerClass") {
                // swiftlint:disable force_cast
                (clazz as! NSObject.Type).perform(NSSelectorFromString("request:"), with: [
                    "call": call,
                    "httpMethod": httpMethod as Any,
                    "config": self.bridge?.config as Any
                ])
                // swiftlint:enable force_cast
            } else {
                try HttpRequestHandler.request(call, httpMethod, self.bridge?.config)
            }
        } catch let error {
            call.reject(error.localizedDescription)
        }
    }

    func request(_ call: CAPPluginCall) {
        http(call, nil)
    }

    func get(_ call: CAPPluginCall) {
        http(call, "GET")
    }

    func post(_ call: CAPPluginCall) {
        http(call, "POST")
    }

    func put(_ call: CAPPluginCall) {
        http(call, "PUT")
    }

    func patch(_ call: CAPPluginCall) {
        http(call, "PATCH")
    }

    func delete(_ call: CAPPluginCall) {
        http(call, "DELETE")
    }
}
