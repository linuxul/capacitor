import Foundation
import WebKit

/// Serves the scheme tasks under ``CapacitorBridge/httpInterceptorStartIdentifier`` while CapacitorHttp is enabled.
///
/// The request is sent to the URL in the ``CapacitorBridge/httpInterceptorUrlParam`` query parameter, and the remote
/// response is handed to WebKit on the main queue with the headers the proxy adds to it.
internal struct HttpInterceptorProxy {
    /// The origin to allow on the response to a request for `requestUrl`, when the page is served by the live reload
    /// server; nil otherwise. Called on the main queue when the response arrives.
    let liveReloadOrigin: (_ requestUrl: URL) -> String?

    func start(_ urlSchemeTask: WKURLSchemeTask) {
        var urlRequest = urlSchemeTask.request
        guard let url = urlRequest.url else {
            urlSchemeTask.didFailWithError(URLError(.badURL))
            return
        }

        let urlComponents = URLComponents(url: url, resolvingAgainstBaseURL: false)
        if let targetUrl = urlComponents?.queryItems?.first(where: { $0.name == CapacitorBridge.httpInterceptorUrlParam })?.value,
           !targetUrl.isEmpty {
            guard let target = URL(string: targetUrl) else {
                urlSchemeTask.didFailWithError(URLError(.badURL))
                return
            }
            urlRequest.url = target
        }

        let urlSession = URLSession.shared
        let task = urlSession.dataTask(with: urlRequest) { (data, response, error) in
            DispatchQueue.main.async {
                // WebKit raises if a stopped task is messaged
                guard !urlSchemeTask.stopped else { return }
                if let error = error {
                    urlSchemeTask.didFailWithError(error)
                    return
                }
                guard let response = response as? HTTPURLResponse else {
                    urlSchemeTask.didFailWithError(URLError(.badServerResponse))
                    return
                }
                guard let proxiedResponse = self.proxiedResponse(for: response, requestUrl: url) else {
                    urlSchemeTask.didFailWithError(URLError(.cannotParseResponse))
                    return
                }
                urlSchemeTask.didReceive(proxiedResponse)
                if let data = data {
                    urlSchemeTask.didReceive(data)
                }
                urlSchemeTask.didFinish()
            }
        }

        task.resume()
    }

    /// The remote response with the headers the proxy adds to it.
    func proxiedResponse(for response: HTTPURLResponse, requestUrl: URL) -> HTTPURLResponse? {
        var headers: [String: String] = [:]
        for (key, value) in response.allHeaderFields {
            headers[key.base as? String ?? String(describing: key)] = value as? String ?? String(describing: value)
        }

        // Nothing should render this. If anything does, sandbox keeps it inert.
        headers["Content-Security-Policy"] = "sandbox; frame-ancestors 'none'"

        // if using live reload, then set CORS headers
        if let origin = liveReloadOrigin(requestUrl) {
            headers["Access-Control-Allow-Origin"] = origin
            headers["Access-Control-Allow-Methods"] = "GET, HEAD, OPTIONS, TRACE"
        }

        return HTTPURLResponse(url: response.url ?? requestUrl, statusCode: response.statusCode, httpVersion: nil, headerFields: headers)
    }
}
