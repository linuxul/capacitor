import Foundation

open class CapacitorUrlRequest: NSObject, URLSessionTaskDelegate {
    public var request: URLRequest
    public var headers: [String: String]

    public enum CapacitorUrlRequestError: Error {
        case serializationError(String?)
    }

    public init(_ url: URL, method: String) {
        request = URLRequest(url: url)
        request.httpMethod = method
        headers = [:]
        if let lang = Locale.autoupdatingCurrent.language.languageCode?.identifier {
            let acceptLanguage: String
            if let country = Locale.autoupdatingCurrent.region?.identifier {
                acceptLanguage = "\(lang)-\(country),\(lang);q=0.5"
            } else {
                acceptLanguage = "\(lang);q=0.5"
            }
            headers["Accept-Language"] = acceptLanguage
            request.addValue(acceptLanguage, forHTTPHeaderField: "Accept-Language")
        }
    }

    public func getRequestDataAsJson(_ data: JSValue) throws -> Data? {
        // We need to check if the JSON is valid before attempting to serialize, as JSONSerialization.data will not throw an exception that can be caught, and will cause the application to crash if it fails.
        if JSONSerialization.isValidJSONObject(data) {
            return try JSONSerialization.data(withJSONObject: data)
        } else {
            throw CapacitorUrlRequest.CapacitorUrlRequestError.serializationError(
                "[ data ] argument for request of content-type [ application/json ] must be serializable to JSON"
            )
        }
    }

    public func getRequestDataAsFormUrlEncoded(_ data: JSValue) throws -> Data? {
        guard let url = request.url, var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        components.queryItems = []

        guard let obj = data as? JSObject else {
            // Throw, other data types explicitly not supported
            throw CapacitorUrlRequestError.serializationError(
                "[ data ] argument for request with content-type [ application/x-www-form-urlencoded ] may only be a plain javascript object"
            )
        }

        let allowed = CharacterSet(charactersIn: "-._*").union(.alphanumerics)

        obj.keys.forEach { (key: String) in
            let value = obj[key] as? String ?? ""
            let name = key.addingPercentEncoding(withAllowedCharacters: allowed)?.replacingOccurrences(of: "%20", with: "+") ?? key
            let encodedValue = value.addingPercentEncoding(withAllowedCharacters: allowed)?.replacingOccurrences(of: "%20", with: "+")
            components.queryItems?.append(URLQueryItem(name: name, value: encodedValue))
        }

        if let query = components.query {
            return Data(query.utf8)
        }

        return nil
    }

    public func getRequestDataAsMultipartFormData(_ data: JSValue, _ contentType: String) throws -> Data {
        guard let obj = data as? JSObject else {
            // Throw, other data types explicitly not supported.
            throw CapacitorUrlRequestError.serializationError(
                "[ data ] argument for request with content-type [ multipart/form-data ] may only be a plain javascript object"
            )
        }

        let strings: [String: String] = obj.compactMapValues { any in
            any as? String
        }

        var data = Data()
        var boundary = UUID().uuidString
        if contentType.contains("boundary="), let contentBoundary = extractBoundary(from: contentType) {
            boundary = contentBoundary
        } else {
            overrideContentType(boundary)
        }
        strings.forEach { key, value in
            data.append(Data("\r\n--\(boundary)\r\n".utf8))
            data.append(Data("Content-Disposition: form-data; name=\"\(key)\"\r\n\r\n".utf8))
            data.append(Data(value.utf8))
        }
        data.append(Data("\r\n--\(boundary)--\r\n".utf8))

        return data
    }

    private func overrideContentType(_ boundary: String) {
        let contentType = "multipart/form-data; boundary=\(boundary)"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        headers["Content-Type"] = contentType
    }

    /**
     Extracts the boundary value of the `content-type` header for multiplart/form-data requests, if provided
     The boundary value might be surrounded by double quotes (") which will be stripped away.
     */
    private func extractBoundary(from contentType: String) -> String? {
        if let boundaryRange = contentType.range(of: "boundary=") {
            var boundary = contentType[boundaryRange.upperBound...]
            if let endRange = boundary.range(of: ";") {
                boundary = boundary[..<endRange.lowerBound]
            }

            if boundary.hasPrefix("\"") && boundary.hasSuffix("\"") {
                return String(boundary.dropFirst().dropLast())
            } else {
                return String(boundary)
            }
        }

        return nil
    }

    public func getRequestDataAsString(_ data: JSValue) throws -> Data {
        guard let stringData = data as? String else {
            throw CapacitorUrlRequestError.serializationError("[ data ] argument could not be parsed as string")
        }
        return Data(stringData.utf8)
    }

    public func getRequestHeader(_ index: String) -> Any? {
        var normalized = [:] as [String: Any]
        self.headers.keys.forEach { (key: String) in
            normalized[key.lowercased()] = self.headers[key]
        }

        return normalized[index.lowercased()]
    }

    public func getRequestDataFromFormData(_ data: JSValue, _ contentType: String) throws -> Data? {
        guard let list = data as? JSArray else {
            // Throw, other data types explicitly not supported.
            throw CapacitorUrlRequestError.serializationError("Data must be an array for FormData")
        }
        var data = Data()
        var boundary = UUID().uuidString
        if contentType.contains("boundary="), let contentBoundary = extractBoundary(from: contentType) {
            boundary = contentBoundary
        } else {
            overrideContentType(boundary)
        }
        for entry in list {
            guard let item = entry as? [String: String] else {
                throw CapacitorUrlRequestError.serializationError("Data must be an array for FormData")
            }
            guard let key = item["key"], let value = item["value"] else {
                throw CapacitorUrlRequestError.serializationError("FormData entries must have a key and a value")
            }

            switch item["type"] {
            case "base64File":
                guard let fileName = item["fileName"], let fileContentType = item["contentType"] else {
                    throw CapacitorUrlRequestError.serializationError("FormData file [ \(key) ] must have a fileName and a contentType")
                }
                guard let fileData = Data(base64Encoded: value) else {
                    throw CapacitorUrlRequestError.serializationError("FormData file [ \(key) ] is not valid base64")
                }

                data.append(Data("--\(boundary)\r\n".utf8))
                data.append(Data("Content-Disposition: form-data; name=\"\(key)\"; filename=\"\(fileName)\"\r\n".utf8))
                data.append(Data("Content-Type: \(fileContentType)\r\n".utf8))
                data.append(Data("Content-Transfer-Encoding: binary\r\n".utf8))
                data.append(Data("\r\n".utf8))
                data.append(fileData)
                data.append(Data("\r\n".utf8))
            case "string":
                data.append(Data("--\(boundary)\r\n".utf8))
                data.append(Data("Content-Disposition: form-data; name=\"\(key)\"\r\n".utf8))
                data.append(Data("\r\n".utf8))
                data.append(Data(value.utf8))
                data.append(Data("\r\n".utf8))
            default:
                break
            }
        }
        data.append(Data("--\(boundary)--\r\n".utf8))

        return data
    }

    public func getRequestData(_ body: JSValue, _ contentType: String, _ dataType: String? = nil) throws -> Data? {
        if dataType == "file" {
            guard let stringData = body as? String else {
                throw CapacitorUrlRequestError.serializationError("[ data ] argument could not be parsed as string")
            }
            guard let fileData = Data(base64Encoded: stringData) else {
                throw CapacitorUrlRequestError.serializationError("[ data ] argument for a request of data type [ file ] must be base64")
            }
            return fileData
        } else if dataType == "formData" {
            return try getRequestDataFromFormData(body, contentType)
        }

        // If data can be parsed directly as a string, return that without processing.
        if let strVal = try? getRequestDataAsString(body) {
            return strVal
        } else if contentType.contains("application/json") {
            return try getRequestDataAsJson(body)
        } else if contentType.contains("application/x-www-form-urlencoded") {
            return try getRequestDataAsFormUrlEncoded(body)
        } else if contentType.contains("multipart/form-data") {
            return try getRequestDataAsMultipartFormData(body, contentType)
        } else {
            throw CapacitorUrlRequestError.serializationError("[ data ] argument could not be parsed for content type [ \(contentType) ]")
        }
    }

    public func setRequestHeaders(_ headers: [String: Any]) {
        for (key, value) in headers {
            request.setValue("\(value)", forHTTPHeaderField: key)
            self.headers[key] = "\(value)"
        }
    }

    public func setRequestBody(_ body: JSValue, _ dataType: String? = nil) throws {
        if let contentType = self.getRequestHeader("Content-Type") as? String {
            request.httpBody = try getRequestData(body, contentType, dataType)
        }
    }

    public func setContentType(_ data: String?) {
        request.setValue(data, forHTTPHeaderField: "Content-Type")
    }

    public func setTimeout(_ timeout: TimeInterval) {
        request.timeoutInterval = timeout
    }

    public func getUrlRequest() -> URLRequest {
        return request
    }

    open func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }

    open func getUrlSession(_ call: CAPPluginCall) -> URLSession {
        let disableRedirects = call.getBool("disableRedirects") ?? false
        if !disableRedirects {
            return URLSession.shared
        }
        return URLSession(configuration: URLSessionConfiguration.default, delegate: self, delegateQueue: nil)
    }
}

extension CapacitorUrlRequest.CapacitorUrlRequestError: LocalizedError {
    /// The message is what the rejected call reports to JavaScript.
    public var errorDescription: String? {
        switch self {
        case .serializationError(let message):
            return message ?? "The request data could not be serialized"
        }
    }
}
