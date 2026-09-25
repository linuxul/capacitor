import UIKit
import WebKit
import XCTest

@testable import Capacitor

class JavaScriptPanelTests: XCTestCase {
    private let webView = WKWebView()
    private var handler: WebViewDelegationHandler!
    private var delegate: TestBridgeDelegate!
    private var bridge: MockBridge!

    /// WebKit creates the frame info; one made here has no backing frame and traps when it is deallocated, so it is leaked.
    private let frame: WKFrameInfo = {
        let frame = WKFrameInfo()
        _ = Unmanaged.passRetained(frame)
        return frame
    }()

    override func setUp() {
        super.setUp()
        handler = WebViewDelegationHandler()
        delegate = TestBridgeDelegate()
        bridge = MockBridge(with: InstanceConfiguration(with: InstanceDescriptor(), isDebug: true), delegate: delegate,
                            assetHandler: MockAssetHandler(router: CapacitorRouter()), delegationHandler: handler)
    }

    private struct Answers {
        var alert = 0
        var confirm: [Bool] = []
        var prompt: [String?] = []
    }

    private func showAllPanels() -> Answers {
        var answers = Answers()
        handler.webView(webView, runJavaScriptAlertPanelWithMessage: "alert", initiatedByFrame: frame) { answers.alert += 1 }
        handler.webView(webView, runJavaScriptConfirmPanelWithMessage: "confirm", initiatedByFrame: frame) { answers.confirm.append($0) }
        handler.webView(webView, runJavaScriptTextInputPanelWithPrompt: "prompt", defaultText: "default", initiatedByFrame: frame) { answers.prompt.append($0) }
        return answers
    }

    private var hostWindow: UIWindow? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow } ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first?.windows.first
    }

    /// Runs the main run loop until `condition` holds, for at most five seconds.
    private func waitUntil(_ condition: () -> Bool, _ description: String) {
        // generous so a busy machine does not fail the test; a condition that holds returns right away
        let deadline = Date(timeIntervalSinceNow: 20)
        while !condition(), Date() < deadline {
            RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.01))
        }
        XCTAssertTrue(condition(), description)
    }

    /// Presents `viewController` and waits until it is on screen; even an unanimated presentation completes later.
    private func present(_ viewController: UIViewController, from presenter: UIViewController) {
        presenter.present(viewController, animated: false)
        waitUntil({ viewController.viewIfLoaded?.window != nil && !viewController.isBeingPresented }, "presented")
    }

    func testPanelsAnswerRightAwayWithoutAViewController() {
        let answers = showAllPanels()
        XCTAssertEqual(answers.alert, 1)
        XCTAssertEqual(answers.confirm, [false])
        XCTAssertEqual(answers.prompt, [nil])
    }

    func testPanelsAnswerRightAwayWhenTheViewControllerIsNotInAWindow() {
        delegate.bridgedViewController = UIViewController()
        let answers = showAllPanels()
        XCTAssertEqual(answers.alert, 1)
        XCTAssertEqual(answers.confirm, [false])
        XCTAssertEqual(answers.prompt, [nil])
    }

    func testTopmostViewControllerWalksThePresentationStack() throws {
        let root = try XCTUnwrap(hostWindow?.rootViewController, "the tests need the host app's window")
        XCTAssertTrue(WebViewDelegationHandler.topmostViewController(from: root) === root)

        let first = UIViewController()
        let second = UIViewController()
        present(first, from: root)
        present(second, from: first)
        defer {
            root.dismiss(animated: false)
            waitUntil({ root.presentedViewController == nil }, "cleanup")
        }
        XCTAssertTrue(WebViewDelegationHandler.topmostViewController(from: root) === second)
    }

    func testConfirmIsPresentedAboveAModal() throws {
        let root = try XCTUnwrap(hostWindow?.rootViewController, "the tests need the host app's window")
        let modal = UIViewController()
        present(modal, from: root)
        defer {
            root.dismiss(animated: false)
            waitUntil({ root.presentedViewController == nil }, "cleanup")
        }
        delegate.bridgedViewController = root

        var answers: [Bool] = []
        handler.webView(webView, runJavaScriptConfirmPanelWithMessage: "confirm", initiatedByFrame: frame) { answers.append($0) }

        let alert = try XCTUnwrap(modal.presentedViewController as? UIAlertController, "the panel must be presented from the modal")
        XCTAssertEqual(alert.message, "confirm")
        XCTAssertEqual(answers, [], "the page gets its answer when the user taps a button")
        waitUntil({ !alert.isBeingPresented }, "presentation finished")
    }

    func testShowAlertWithPresentsOnTheMainThread() throws {
        let root = try XCTUnwrap(hostWindow?.rootViewController, "the tests need the host app's window")
        delegate.bridgedViewController = root
        defer {
            root.dismiss(animated: false)
            waitUntil({ root.presentedViewController == nil }, "cleanup")
        }

        let bridge = self.bridge!
        DispatchQueue.global().async {
            bridge.showAlertWith(title: "title", message: "message", buttonTitle: "OK")
        }
        waitUntil({ (root.presentedViewController as? UIAlertController)?.title == "title" }, "the alert is presented")
        let alert = try XCTUnwrap(root.presentedViewController)
        waitUntil({ !alert.isBeingPresented }, "presentation finished")
    }
}
