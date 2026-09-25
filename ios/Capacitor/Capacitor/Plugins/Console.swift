import Foundation

@objc(CAPConsolePlugin)
public class CAPConsolePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "CAPConsolePlugin"
    public let jsName = "Console"
    public let pluginMethods: [CAPPluginMethod] = [
        .none("log", CAPConsolePlugin.log)
    ]

    public func log(_ call: CAPPluginCall) {
        let message = call.getString("message") ?? ""
        let level = call.getString("level") ?? "log"
        CAPLog.print("⚡️  [\(level)] - \(message)")
    }
}
