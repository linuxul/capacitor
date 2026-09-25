import Foundation
import UIKit

@objc(CAPWebViewPlugin)
public class CAPWebViewPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "CAPWebViewPlugin"
    public let jsName = "WebView"
    public let pluginMethods: [CAPPluginMethod] = [
        .promise("setServerAssetPath", CAPWebViewPlugin.setServerAssetPath),
        .promise("setServerBasePath", CAPWebViewPlugin.setServerBasePath),
        .promise("getServerBasePath", CAPWebViewPlugin.getServerBasePath),
        .promise("persistServerBasePath", CAPWebViewPlugin.persistServerBasePath)
    ]

    func setServerAssetPath(_ call: CAPPluginCall) {
        guard let path = call.getString("path") else {
            call.reject("Must provide a path")
            return
        }
        let assetPath = Bundle.main.url(forResource: path, withExtension: nil)?.path ?? path
        withBridgeViewController(call) { viewController in
            viewController.setServerBasePath(path: assetPath)
            call.resolve()
        }
    }

    func setServerBasePath(_ call: CAPPluginCall) {
        guard let path = call.getString("path") else {
            call.reject("Must provide a path")
            return
        }
        withBridgeViewController(call) { viewController in
            viewController.setServerBasePath(path: path)
            call.resolve()
        }
    }

    func getServerBasePath(_ call: CAPPluginCall) {
        withBridgeViewController(call) { viewController in
            call.resolve([
                "path": viewController.getServerBasePath()
            ])
        }
    }

    func persistServerBasePath(_ call: CAPPluginCall) {
        withBridgeViewController(call) { viewController in
            KeyValueStore.standard["serverBasePath"] = viewController.getServerBasePath()
            call.resolve()
        }
    }

    /// Runs `body` on the main thread with the bridge's view controller, or rejects `call` when the bridge is not
    /// hosted by a `CAPBridgeViewController`. Plugin methods start on the bridge queue, where UIKit must not be used.
    private func withBridgeViewController(_ call: CAPPluginCall, _ body: @escaping (CAPBridgeViewController) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let viewController = self?.bridge?.viewController as? CAPBridgeViewController else {
                call.reject("The web view is not hosted by a CAPBridgeViewController")
                return
            }
            body(viewController)
        }
    }
}
