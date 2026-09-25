import Foundation

/// Answers requests for the `capstub` scheme without touching the network. Register it with
/// `URLProtocol.registerClass(_:)`, which covers `URLSession.shared`.
///
/// The host picks the answer:
/// - `capstub://http/...` returns an `HTTPURLResponse` with ``httpStatus``, ``httpHeaders`` and ``body``.
/// - `capstub://plain/...` returns a plain `URLResponse`, the kind a non-HTTP URL loader produces.
/// - `capstub://fail/...` fails with `URLError(.notConnectedToInternet)`.
final class StubURLProtocol: URLProtocol {
    static let scheme = "capstub"

    private static let lock = NSLock()
    private static var _body = Data()
    private static var _httpStatus = 200
    private static var _httpHeaders: [String: String] = [:]
    private static var _requests: [URLRequest] = []

    static var body: Data {
        get { lock.withLock { _body } }
        set { lock.withLock { _body = newValue } }
    }

    static var httpStatus: Int {
        get { lock.withLock { _httpStatus } }
        set { lock.withLock { _httpStatus = newValue } }
    }

    static var httpHeaders: [String: String] {
        get { lock.withLock { _httpHeaders } }
        set { lock.withLock { _httpHeaders = newValue } }
    }

    /// The requests the stub has answered, in order.
    static var requests: [URLRequest] {
        lock.withLock { _requests }
    }

    static func reset() {
        lock.withLock {
            _body = Data()
            _httpStatus = 200
            _httpHeaders = [:]
            _requests = []
        }
    }

    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.scheme == scheme
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        Self.lock.withLock { Self._requests.append(request) }
        guard let url = request.url, let client = client else {
            return
        }
        switch url.host {
        case "http":
            guard let response = HTTPURLResponse(url: url, statusCode: Self.httpStatus, httpVersion: "HTTP/1.1", headerFields: Self.httpHeaders) else {
                client.urlProtocol(self, didFailWithError: URLError(.cannotParseResponse))
                return
            }
            client.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        case "plain":
            let response = URLResponse(url: url, mimeType: "text/plain", expectedContentLength: Self.body.count, textEncodingName: nil)
            client.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        default:
            client.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
            return
        }
        client.urlProtocol(self, didLoad: Self.body)
        client.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
