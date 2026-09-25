import Foundation
import WebKit

@objc(CAPWebViewAssetHandler)
open class WebViewAssetHandler: NSObject, WKURLSchemeHandler {
    private var router: Router
    private var serverUrl: URL?
    private var configuration: InstanceConfiguration?

    public init(router: Router) {
        self.router = router
        super.init()
    }

    open func setAssetPath(_ assetPath: String) {
        router.basePath = assetPath
    }

    open func setServerUrl(_ serverUrl: URL?) {
        self.serverUrl = serverUrl
    }

    open func setConfiguration(_ configuration: InstanceConfiguration?) {
        self.configuration = configuration
    }

    private func isUsingLiveReload(_ localUrl: URL) -> Bool {
        return self.serverUrl != nil && self.serverUrl?.scheme != localUrl.scheme
    }

    open func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard let url = urlSchemeTask.request.url else {
            urlSchemeTask.didFailWithError(URLError(.badURL))
            return
        }
        let stringToLoad = url.path

        if stringToLoad.starts(with: CapacitorBridge.httpInterceptorStartIdentifier) {
            // Only serve the proxy when CapacitorHttp is on. A scheme task can't tell a document from
            // a subresource, so keeping documents out relies on the check in decidePolicyFor.
            if configuration?.getPluginConfig("CapacitorHttp").getBoolean("enabled", false) == true {
                httpProxy.start(urlSchemeTask)
            } else {
                urlSchemeTask.didFailWithError(URLError(.unsupportedURL))
            }
            return
        }

        let startPath: String
        if stringToLoad.starts(with: CapacitorBridge.fileStartIdentifier) {
            startPath = stringToLoad.replacingOccurrences(of: CapacitorBridge.fileStartIdentifier, with: "")
        } else {
            startPath = router.route(for: stringToLoad)
        }

        // Build the whole response before telling WebKit anything, so a failure is reported with
        // didFailWithError alone instead of after a response.
        let response: URLResponse
        let data: Data
        do {
            (response, data) = try fileResponse(for: urlSchemeTask.request, url: url, fileUrl: URL(fileURLWithPath: startPath))
        } catch {
            urlSchemeTask.didFailWithError(error)
            return
        }
        urlSchemeTask.didReceive(response)
        urlSchemeTask.didReceive(data)
        urlSchemeTask.didFinish()
    }

    private func fileResponse(for request: URLRequest, url: URL, fileUrl: URL) throws -> (URLResponse, Data) {
        let mimeType = mimeTypeForExtension(pathExtension: url.pathExtension)
        var headers = [
            "Content-Type": mimeType,
            "Cache-Control": "no-cache"
        ]

        // if using live reload, then set CORS headers
        if isUsingLiveReload(url) {
            headers["Access-Control-Allow-Origin"] = self.serverUrl?.absoluteString
            headers["Access-Control-Allow-Methods"] = "GET, HEAD, OPTIONS, TRACE"
        }

        if let rangeString = request.value(forHTTPHeaderField: "Range"),
           let totalSize = try fileUrl.resourceValues(forKeys: [.fileSizeKey]).fileSize,
           case let resolution = ByteRange.resolve(rangeString, size: totalSize), resolution != .whole {
            headers["Accept-Ranges"] = "bytes"
            var data = Data()
            let statusCode: Int
            if case let .partial(range) = resolution {
                let fileHandle = try FileHandle(forReadingFrom: fileUrl)
                defer { try? fileHandle.close() }
                try fileHandle.seek(toOffset: UInt64(range.first))
                data = try fileHandle.read(upToCount: range.length) ?? Data()
                statusCode = 206
                headers["Content-Range"] = range.contentRange(of: totalSize)
            } else {
                statusCode = 416
                headers["Content-Range"] = ByteRange.unsatisfiedContentRange(of: totalSize)
            }
            headers["Content-Length"] = String(data.count)
            return (try httpResponse(url: url, statusCode: statusCode, headers: headers), data)
        }

        let isMedia = isMediaExtension(pathExtension: url.pathExtension)
        var data = Data()
        if !url.path.contains("cordova.js") {
            data = try Data(contentsOf: fileUrl, options: isMedia ? .mappedIfSafe : [])
        }
        if isMedia {
            return (URLResponse(url: url, mimeType: mimeType, expectedContentLength: data.count, textEncodingName: nil), data)
        }
        return (try httpResponse(url: url, statusCode: 200, headers: headers), data)
    }

    private func httpResponse(url: URL, statusCode: Int, headers: [String: String]) throws -> HTTPURLResponse {
        guard let response = HTTPURLResponse(url: url, statusCode: statusCode, httpVersion: nil, headerFields: headers) else {
            throw URLError(.cannotParseResponse)
        }
        return response
    }

    open func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {
        urlSchemeTask.stopped = true
    }

    /// The system's MIME type for `pathExtension`, else the one in ``mimeTypes``, else `application/octet-stream`;
    /// `text/html` when there is no extension.
    open func mimeTypeForExtension(pathExtension: String) -> String {
        return MimeTypes.mimeType(forPathExtension: pathExtension)
    }

    open func isMediaExtension(pathExtension: String) -> Bool {
        let mediaExtensions = ["m4v", "mov", "mp4",
                               "aac", "ac3", "aiff", "au", "flac", "m4a", "mp3", "wav"]
        if mediaExtensions.contains(pathExtension.lowercased()) {
            return true
        }
        return false
    }

    /// The proxy for CapacitorHttp requests. Its live reload check reads ``setServerUrl(_:)``'s URL when a response
    /// arrives, and it keeps the handler alive until then.
    private var httpProxy: HttpInterceptorProxy {
        HttpInterceptorProxy(liveReloadOrigin: { [self] requestUrl in
            isUsingLiveReload(requestUrl) ? serverUrl?.absoluteString ?? "" : nil
        })
    }

    /// The fallback MIME types by path extension; see ``mimeTypeForExtension(pathExtension:)``.
    public let mimeTypes = MimeTypes.table
}

private let stoppedKey: StaticString = "Capacitor.WKURLSchemeTask.stopped" // the literal's constant address is the key

extension WKURLSchemeTask {
    /// Set once WebKit has stopped the task; a stopped task must not be messaged again. The HTTP proxy reads it.
    var stopped: Bool {
        get {
            return (objc_getAssociatedObject(self, stoppedKey.utf8Start) as? NSNumber)?.boolValue ?? false
        }
        set {
            objc_setAssociatedObject(self, stoppedKey.utf8Start, NSNumber(value: newValue), .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        }
    }
}
