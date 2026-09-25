import Foundation

// MARK: - CAPBridgeProtocol: Paths, Files, Assets

extension CapacitorBridge {
    /// Serves the web app from `path` from now on. Callable from any thread; plugins call it from the bridge queue.
    ///
    /// The configuration is updated right away, so `config.appLocation` reads the new path as soon as this returns. The
    /// asset handler's router is only used on the main thread, where WebKit starts scheme tasks, so it is updated there;
    /// a reload scheduled on the main queue after this call already loads from the new path.
    public func setServerBasePath(_ path: String) {
        let url = URL(fileURLWithPath: path, isDirectory: true)
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        config = config.updatingAppLocation(url)
        let assetPath = url.path
        if Thread.isMainThread {
            webViewAssetHandler.setAssetPath(assetPath)
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.webViewAssetHandler.setAssetPath(assetPath)
            }
        }
    }

    /**
     Translate a URL from the web view into a file URL for native iOS.

     The web view may be handling several different types of URLs:
     - res:// (shortcut scheme to web assets)
     - file:// (fully qualified URL to file on the local device)
     - base64:// (to be implemented)
     - [web view scheme]:// (already converted once to load in the web view, to be implemented)
     */
    public func localURL(fromWebURL webURL: URL?) -> URL? {
        guard let inputURL = webURL else {
            return nil
        }

        let url: URL

        switch inputURL.scheme {
        case "res":
            url = config.appLocation.appendingPathComponent(inputURL.path)
        case "file":
            url = inputURL
        default:
            return nil
        }

        return url
    }

    /**
     Translate a file URL for native iOS into a URL to load in the web view.
     */
    public func portablePath(fromLocalURL localURL: URL?) -> URL? {
        guard let inputURL = localURL else {
            return nil
        }

        return self.config.localURL.appendingPathComponent(CapacitorBridge.fileStartIdentifier).appendingPathComponent(inputURL.path)
    }
}
