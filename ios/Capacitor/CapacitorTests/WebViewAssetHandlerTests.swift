import WebKit
import XCTest

@testable import Capacitor

/// Records what a scheme handler does with a task, in order, and fails the test when it breaks WebKit's contract:
/// a response first, then data, then exactly one of `didFinish` or `didFailWithError`.
private final class FakeSchemeTask: NSObject, WKURLSchemeTask {
    enum Event {
        case response(URLResponse)
        case data(Data)
        case finish
        case fail(Error)
    }

    let request: URLRequest
    private(set) var events: [Event] = []
    var onComplete: () -> Void = {}

    init(_ request: URLRequest) {
        self.request = request
    }

    convenience init(url: String, headers: [String: String] = [:]) {
        var request = URLRequest(url: URL(string: url)!)
        headers.forEach { request.setValue($1, forHTTPHeaderField: $0) }
        self.init(request)
    }

    var response: URLResponse? {
        events.lazy.compactMap { if case let .response(response) = $0 { return response } else { return nil } }.first
    }

    var httpResponse: HTTPURLResponse? {
        response as? HTTPURLResponse
    }

    var body: Data {
        events.reduce(into: Data()) { if case let .data(data) = $1 { $0.append(data) } }
    }

    var finished: Bool {
        events.contains { if case .finish = $0 { return true } else { return false } }
    }

    var error: Error? {
        events.lazy.compactMap { if case let .fail(error) = $0 { return error } else { return nil } }.first
    }

    private var isComplete: Bool {
        finished || error != nil
    }

    func didReceive(_ response: URLResponse) {
        XCTAssertFalse(isComplete, "response after the task completed")
        XCTAssertNil(self.response, "second response")
        events.append(.response(response))
    }

    func didReceive(_ data: Data) {
        XCTAssertFalse(isComplete, "data after the task completed")
        XCTAssertNotNil(response, "data before a response")
        events.append(.data(data))
    }

    func didFinish() {
        XCTAssertFalse(isComplete, "task completed twice")
        XCTAssertNotNil(response, "finished without a response")
        events.append(.finish)
        onComplete()
    }

    func didFailWithError(_ error: Error) {
        XCTAssertFalse(isComplete, "task completed twice")
        events.append(.fail(error))
        onComplete()
    }
}

class WebViewAssetHandlerTests: XCTestCase {
    private var directory: URL!
    private var handler: WebViewAssetHandler!
    private let webView = WKWebView()

    override func setUpWithError() throws {
        try super.setUpWithError()
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data("0123456789".utf8).write(to: directory.appendingPathComponent("digits.txt"))
        handler = WebViewAssetHandler(router: CapacitorRouter())
        handler.setAssetPath(directory.path)
    }

    override func tearDownWithError() throws {
        URLProtocol.unregisterClass(StubURLProtocol.self)
        StubURLProtocol.reset()
        try? FileManager.default.removeItem(at: directory)
        try super.tearDownWithError()
    }

    private func enableHttpProxy() {
        let descriptor = InstanceDescriptor()
        descriptor.pluginConfigurations = ["CapacitorHttp": ["enabled": true] as JSObject]
        handler.setConfiguration(InstanceConfiguration(with: descriptor, isDebug: true))
        URLProtocol.registerClass(StubURLProtocol.self)
    }

    private func proxy(_ target: String) -> FakeSchemeTask {
        var components = URLComponents(string: "capacitor://localhost\(CapacitorBridge.httpInterceptorStartIdentifier)")!
        components.queryItems = [URLQueryItem(name: CapacitorBridge.httpInterceptorUrlParam, value: target)]
        return FakeSchemeTask(url: components.url!.absoluteString)
    }

    /// Starts a proxied request and waits until the handler completes it.
    private func completeProxy(_ target: String) -> FakeSchemeTask {
        let task = proxy(target)
        let completed = expectation(description: "task completed")
        task.onComplete = { completed.fulfill() }
        handler.webView(webView, start: task)
        wait(for: [completed], timeout: 5)
        return task
    }

    private func start(_ task: FakeSchemeTask) -> FakeSchemeTask {
        handler.webView(webView, start: task)
        return task
    }

    private func range(_ value: String) -> FakeSchemeTask {
        start(FakeSchemeTask(url: "capacitor://localhost/digits.txt", headers: ["Range": value]))
    }

    func testServesAWholeFile() {
        let task = start(FakeSchemeTask(url: "capacitor://localhost/digits.txt"))
        XCTAssertEqual(task.httpResponse?.statusCode, 200)
        XCTAssertEqual(String(decoding: task.body, as: UTF8.self), "0123456789")
        XCTAssertTrue(task.finished)
    }

    func testServesAByteRange() {
        let task = range("bytes=2-4")
        XCTAssertEqual(task.httpResponse?.statusCode, 206)
        XCTAssertEqual(task.httpResponse?.value(forHTTPHeaderField: "Content-Range"), "bytes 2-4/10")
        XCTAssertEqual(task.httpResponse?.value(forHTTPHeaderField: "Content-Length"), "3")
        XCTAssertEqual(String(decoding: task.body, as: UTF8.self), "234")
        XCTAssertTrue(task.finished)
    }

    func testServesASuffixRange() {
        let task = range("bytes=-3")
        XCTAssertEqual(task.httpResponse?.statusCode, 206)
        XCTAssertEqual(task.httpResponse?.value(forHTTPHeaderField: "Content-Range"), "bytes 7-9/10")
        XCTAssertEqual(String(decoding: task.body, as: UTF8.self), "789")
    }

    func testClampsARangePastTheEnd() {
        let task = range("bytes=8-100")
        XCTAssertEqual(task.httpResponse?.statusCode, 206)
        XCTAssertEqual(task.httpResponse?.value(forHTTPHeaderField: "Content-Range"), "bytes 8-9/10")
        XCTAssertEqual(String(decoding: task.body, as: UTF8.self), "89")
    }

    func testAnswersUnsatisfiableAndMalformedRangesWith416() {
        for header in ["bytes=10-", "bytes=5-3", "bytes", "bytes=abc-"] {
            let task = range(header)
            XCTAssertEqual(task.httpResponse?.statusCode, 416, header)
            XCTAssertEqual(task.httpResponse?.value(forHTTPHeaderField: "Content-Range"), "bytes */10", header)
            XCTAssertTrue(task.body.isEmpty, header)
            XCTAssertTrue(task.finished, header)
        }
    }

    func testIgnoresRangesItDoesNotServe() {
        let task = range("bytes=0-1,4-5")
        XCTAssertEqual(task.httpResponse?.statusCode, 200)
        XCTAssertEqual(String(decoding: task.body, as: UTF8.self), "0123456789")
    }

    func testFailsATaskWithoutAURL() {
        var request = URLRequest(url: URL(string: "capacitor://localhost/")!)
        request.url = nil
        let task = start(FakeSchemeTask(request))
        XCTAssertEqual((task.error as? URLError)?.code, .badURL)
        XCTAssertNil(task.response)
    }

    func testFailsAMissingFileWithoutAResponse() {
        let task = start(FakeSchemeTask(url: "capacitor://localhost/missing.txt"))
        XCTAssertNotNil(task.error)
        XCTAssertNil(task.response)
        XCTAssertFalse(task.finished)
    }

    func testFailsTheProxyWhenCapacitorHttpIsOff() {
        let task = start(proxy("capstub://http/data"))
        XCTAssertEqual((task.error as? URLError)?.code, .unsupportedURL)
    }

    func testProxiesAnHttpResponse() throws {
        enableHttpProxy()
        StubURLProtocol.body = Data("remote".utf8)
        StubURLProtocol.httpStatus = 201
        StubURLProtocol.httpHeaders = ["Content-Type": "text/plain", "X-Remote": "yes"]
        let task = completeProxy("capstub://http/data")
        let response = try XCTUnwrap(task.httpResponse)
        XCTAssertEqual(response.statusCode, 201)
        XCTAssertEqual(response.value(forHTTPHeaderField: "X-Remote"), "yes")
        XCTAssertEqual(response.value(forHTTPHeaderField: "Content-Security-Policy"), "sandbox; frame-ancestors 'none'")
        XCTAssertEqual(String(decoding: task.body, as: UTF8.self), "remote")
        XCTAssertTrue(task.finished)
    }

    func testFailsTheProxyForANonHttpResponse() {
        enableHttpProxy()
        StubURLProtocol.body = Data("plain".utf8)
        let task = completeProxy("capstub://plain/data")
        XCTAssertEqual((task.error as? URLError)?.code, .badServerResponse)
        XCTAssertNil(task.response, "a response without HTTP status must not reach WebKit")
    }

    func testFailsTheProxyWhenTheRequestFails() {
        enableHttpProxy()
        let task = completeProxy("capstub://fail/data")
        XCTAssertEqual((task.error as? URLError)?.code, .notConnectedToInternet)
    }

    func testFailsTheProxyForAnUnparsableTarget() {
        enableHttpProxy()
        let task = start(proxy("http://exa mple.com/%"))
        XCTAssertEqual((task.error as? URLError)?.code, .badURL)
    }

    func testDoesNotMessageAStoppedTask() {
        enableHttpProxy()
        let task = proxy("capstub://http/data")
        handler.webView(webView, start: task)
        handler.webView(webView, stop: task)
        // the stub answers on URLSession's queue and the handler hops to the main queue; let both run
        let served = expectation(for: NSPredicate { _, _ in StubURLProtocol.requests.count == 1 }, evaluatedWith: nil)
        wait(for: [served], timeout: 5)
        RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.3))
        XCTAssertTrue(task.events.isEmpty)
    }
}
