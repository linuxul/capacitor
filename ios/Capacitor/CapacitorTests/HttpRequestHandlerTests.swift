import XCTest

@testable import Capacitor

class HttpRequestHandlerTests: XCTestCase {
    override func setUp() {
        super.setUp()
        StubURLProtocol.reset()
        URLProtocol.registerClass(StubURLProtocol.self)
    }

    override func tearDown() {
        URLProtocol.unregisterClass(StubURLProtocol.self)
        StubURLProtocol.reset()
        super.tearDown()
    }

    private func queryItems(_ url: URL?) -> [String: [String?]] {
        let items = URLComponents(url: url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
        return Dictionary(grouping: items, by: \.name).mapValues { $0.map(\.value) }
    }

    /// Runs `HttpRequestHandler.request` and waits for the call to settle.
    private func request(_ options: JSObject, method: String? = "GET") throws -> (data: JSObject?, error: CAPPluginCallError?) {
        var result: (data: JSObject?, error: CAPPluginCallError?) = (nil, nil)
        let settled = expectation(description: "call settled")
        let call = CAPPluginCall(callbackId: UUID().uuidString, methodName: "request", options: options, success: { callResult, _ in
            result.data = JSTypes.coerceDictionaryToJSObject(callResult.data)
            settled.fulfill()
        }, error: { error in
            result.error = error
            settled.fulfill()
        })
        try HttpRequestHandler.request(call, method, nil)
        wait(for: [settled], timeout: 5)
        return result
    }

    // MARK: - URL parameters

    func testEncodedUrlParamsAcceptEveryJSONType() throws {
        let params: [String: Any] = [
            "text": "a b",
            "int": 2,
            "double": 1.5,
            "yes": true,
            "no": false,
            "none": NSNull(),
            "list": ["x", 3, true] as [Any]
        ]
        let url = try HttpRequestHandler.CapacitorHttpRequestBuilder()
            .setUrl("https://example.com/path?kept=1")
            .setUrlParams(params)
            .url
        XCTAssertEqual(queryItems(url), [
            "kept": ["1"],
            "text": ["a b"],
            "int": ["2"],
            "double": ["1.5"],
            "yes": ["true"],
            "no": ["false"],
            "none": ["null"],
            "list": ["x", "3", "true"]
        ])
    }

    func testUnencodedUrlParamsAcceptNumbersAndBooleans() throws {
        let url = try HttpRequestHandler.CapacitorHttpRequestBuilder()
            .setUrl("https://example.com/path")
            .setUrlParams(["page": 2, "all": true, "ids": [1, 2]], false)
            .url
        XCTAssertEqual(queryItems(url), ["page": ["2"], "all": ["true"], "ids": ["1", "2"]])
    }

    func testUrlParamsWithoutAUrlAreIgnored() {
        let builder = HttpRequestHandler.CapacitorHttpRequestBuilder().setUrlParams(["page": 2])
        XCTAssertNil(builder.url)
    }

    // MARK: - Responses

    func testResolvesAnHttpResponse() throws {
        StubURLProtocol.httpHeaders = ["Content-Type": "application/json"]
        StubURLProtocol.body = Data(#"{"ok":true}"#.utf8)
        let result = try request(["url": "capstub://http/items", "params": ["page": 2] as JSObject])
        XCTAssertNil(result.error)
        XCTAssertEqual(result.data?["status"] as? Int, 200)
        XCTAssertEqual((result.data?["data"] as? JSObject)?["ok"] as? Bool, true)
        XCTAssertEqual(queryItems(StubURLProtocol.requests.first?.url)["page"], ["2"])
    }

    func testRejectsAResponseThatIsNotHTTP() throws {
        StubURLProtocol.body = Data("plain".utf8)
        let result = try request(["url": "capstub://plain/items"])
        XCTAssertNil(result.data)
        XCTAssertEqual(result.error?.code, NSURLErrorDomain)
        XCTAssertEqual((result.error?.error as? URLError)?.code, .badServerResponse)
    }

    func testRejectsAFailedRequest() throws {
        let result = try request(["url": "capstub://fail/items"])
        XCTAssertEqual((result.error?.error as? URLError)?.code, .notConnectedToInternet)
    }

    func testRejectsInvalidFileData() throws {
        let result = try request([
            "url": "capstub://http/upload",
            "headers": ["Content-Type": "application/octet-stream"] as JSObject,
            "dataType": "file",
            "data": "not base64!"
        ], method: "POST")
        XCTAssertEqual(result.error?.message, "[ data ] argument for a request of data type [ file ] must be base64")
        XCTAssertTrue(StubURLProtocol.requests.isEmpty, "a malformed request must not be sent")
    }

    // MARK: - FormData

    private func formData(_ entries: [[String: String]]) throws -> Data? {
        let request = CapacitorUrlRequest(URL(string: "https://example.com")!, method: "POST")
        let list: JSArray = entries.map { $0 as JSObject }
        return try request.getRequestDataFromFormData(list, "multipart/form-data; boundary=BOUNDARY")
    }

    func testBuildsFormData() throws {
        let body = try XCTUnwrap(formData([
            ["type": "string", "key": "name", "value": "cap"],
            ["type": "base64File", "key": "file", "value": Data("hi".utf8).base64EncodedString(), "fileName": "a.txt", "contentType": "text/plain"]
        ]))
        let text = String(decoding: body, as: UTF8.self)
        XCTAssertTrue(text.contains("name=\"name\"\r\n\r\ncap\r\n"))
        XCTAssertTrue(text.contains("name=\"file\"; filename=\"a.txt\"\r\nContent-Type: text/plain\r\n"))
        XCTAssertTrue(text.contains("\r\n\r\nhi\r\n"))
        XCTAssertTrue(text.hasSuffix("--BOUNDARY--\r\n"))
    }

    func testRejectsMalformedFormData() {
        let file = ["type": "base64File", "key": "file", "value": "aGk=", "fileName": "a.txt", "contentType": "text/plain"]
        var invalidBase64 = file
        invalidBase64["value"] = "not base64!"
        var noFileName = file
        noFileName["fileName"] = nil
        let malformed: [[String: String]] = [
            invalidBase64,
            noFileName,
            ["type": "string", "key": "name"],
            ["type": "string", "value": "cap"]
        ]
        for entry in malformed {
            XCTAssertThrowsError(try formData([entry]), "\(entry)") { error in
                XCTAssertTrue(error is CapacitorUrlRequest.CapacitorUrlRequestError)
                XCTAssertFalse(error.localizedDescription.contains("operation couldn"), "the message should say what is wrong")
            }
        }
    }
}
