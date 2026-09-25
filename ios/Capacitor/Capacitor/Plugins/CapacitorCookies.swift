import Foundation

@objc(CAPCookiesPlugin)
public class CAPCookiesPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "CAPCookiesPlugin"
    public let jsName = "CapacitorCookies"
    public let pluginMethods: [CAPPluginMethod] = [
        .promise("getCookies", CAPCookiesPlugin.getCookies),
        .promise("setCookie", CAPCookiesPlugin.setCookie),
        .promise("deleteCookie", CAPCookiesPlugin.deleteCookie),
        .promise("clearCookies", CAPCookiesPlugin.clearCookies),
        .promise("clearAllCookies", CAPCookiesPlugin.clearAllCookies)
    ]

    var cookieManager: CapacitorCookieManager?

    @objc override public func load() {
        cookieManager = CapacitorCookieManager(bridge?.config)
    }

    func getCookies(_ call: CAPPluginCall) {
        guard let cookieManager = cookieManager(for: call) else { return }
        guard let url = cookieManager.getServerUrl(call.getString("url")) else { return call.reject("Invalid URL / Server URL")}
        call.resolve(cookieManager.getCookiesAsMap(url))
    }

    func setCookie(_ call: CAPPluginCall) {
        guard let cookieManager = cookieManager(for: call) else { return }
        guard let key = call.getString("key") else { return call.reject("Must provide key") }
        guard let value = call.getString("value") else { return call.reject("Must provide value") }

        guard let url = cookieManager.getServerUrl(call.getString("url")) else { return call.reject("Invalid domain") }

        let expires = call.getString("expires", "")
        let path = call.getString("path", "")
        cookieManager.setCookie(url, key, cookieManager.encode(value), expires, path)
        call.resolve()
    }

    func deleteCookie(_ call: CAPPluginCall) {
        guard let cookieManager = cookieManager(for: call) else { return }
        guard let key = call.getString("key") else { return call.reject("Must provide key") }
        guard let url = cookieManager.getServerUrl(call.getString("url")) else { return call.reject("Invalid URL / Server URL")}
        cookieManager.deleteCookie(url, key)
        call.resolve()
    }

    func clearCookies(_ call: CAPPluginCall) {
        guard let cookieManager = cookieManager(for: call) else { return }
        guard let url = cookieManager.getServerUrl(call.getString("url")) else { return call.reject("Invalid URL / Server URL")}
        cookieManager.clearCookies(url)
        call.resolve()
    }

    func clearAllCookies(_ call: CAPPluginCall) {
        guard let cookieManager = cookieManager(for: call) else { return }
        cookieManager.clearAllCookies()
        call.resolve()
    }

    /// The cookie manager created in ``load()``, or nil after rejecting `call` when the plugin was never loaded.
    private func cookieManager(for call: CAPPluginCall) -> CapacitorCookieManager? {
        guard let cookieManager = cookieManager else {
            call.reject("The CapacitorCookies plugin is not loaded")
            return nil
        }
        return cookieManager
    }
}
