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
        try? FileManager.default.removeItem(at: directory)
        try super.tearDownWithError()
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
}
