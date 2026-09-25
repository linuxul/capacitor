import XCTest
import UIKit
import WebKit

@testable import Capacitor

@objc(CAPTestFixturePlugin)
private class TestFixturePlugin: CAPPlugin, CAPBridgedPlugin {
    let identifier = "CAPTestFixturePlugin"
    let jsName = "TestFixture"
    let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "echo", returnType: .promise),
        CAPPluginMethod(name: "watch", returnType: .callback),
        CAPPluginMethod(#selector(TestFixturePlugin.fire(_:)), returnType: .none)
    ]

    @objc func echo(_ call: CAPPluginCall) {
        call.resolve(["value": call.getString("value") ?? ""])
    }

    @objc func watch(_ call: CAPPluginCall) {
        call.keepAlive = true
    }

    @objc func fire(_ call: CAPPluginCall) {}
}

private extension CAPPluginCallResult {
    func string(_ key: String) -> String? {
        guard case .dictionary(let data)? = resultData else {
            return nil
        }
        return data[key] as? String
    }
}

class PluginTests: XCTestCase {
    private func makeCall(_ options: JSObject = [:], method: String = "test", onSuccess: @escaping (CAPPluginCallResult) -> Void = { _ in },
                          onError: @escaping (CAPPluginCallError) -> Void = { _ in }) -> CAPPluginCall {
        return CAPPluginCall(callbackId: UUID().uuidString, methodName: method, options: options,
                             success: { result, _ in onSuccess(result) }, error: onError)
    }

    func testPluginMethodContract() {
        let plugin = TestFixturePlugin()
        XCTAssertEqual(plugin.pluginMethods.map(\.name), ["echo", "watch", "fire"])
        XCTAssertEqual(plugin.pluginMethods.map { NSStringFromSelector($0.selector) }, ["echo:", "watch:", "fire:"])
        // these strings are part of the JS protocol
        XCTAssertEqual(plugin.pluginMethods.map(\.returnType.rawValue), ["promise", "callback", "none"])
        XCTAssertEqual(plugin.getMethod(named: "watch")?.returnType, .callback)
        XCTAssertNil(plugin.getMethod(named: "missing"))
    }

    func testPluginIsLoadableThroughTheObjCRuntime() throws {
        XCTAssertEqual(NSStringFromClass(CAPPlugin.self), "CAPPlugin")
        XCTAssertEqual(NSStringFromClass(CAPPluginCall.self), "CAPPluginCall")
        XCTAssertEqual(NSStringFromClass(CAPPluginMethod.self), "CAPPluginMethod")
        let type = try XCTUnwrap(NSClassFromString("CAPTestFixturePlugin") as? CAPPlugin.Type)
        let plugin = try XCTUnwrap(type.init() as? CAPPlugin & CAPBridgedPlugin)
        XCTAssertEqual(plugin.jsName, "TestFixture")
        // the selectors that JavaScript can invoke on every plugin
        for name in ["addListener:", "removeListener:", "removeAllListeners:", "checkPermissions:", "requestPermissions:", "echo:"] {
            XCTAssertTrue(plugin.responds(to: NSSelectorFromString(name)), "\(name) is not visible to the Obj-C runtime")
        }
    }

    func testMethodDispatchThroughSelector() {
        let plugin = TestFixturePlugin()
        var resolved: String?
        let call = makeCall(["value": "hello"], onSuccess: { resolved = $0.string("value") })
        plugin.perform(NSSelectorFromString("echo:"), with: call)
        XCTAssertEqual(resolved, "hello")
    }

    func testListenersReceiveNotifications() {
        let plugin = TestFixturePlugin()
        var received: [String] = []
        let listener = makeCall(["eventName": "ping"], onSuccess: { received.append($0.string("id") ?? "nil") })
        XCTAssertFalse(plugin.hasListeners("ping"))
        plugin.notifyListeners("ping", data: ["id": "dropped"])

        plugin.perform(NSSelectorFromString("addListener:"), with: listener)
        XCTAssertTrue(listener.keepAlive)
        XCTAssertTrue(plugin.hasListeners("ping"))
        XCTAssertEqual(plugin.getListeners("ping")?.count, 1)

        plugin.notifyListeners("ping", data: ["id": "one"])
        plugin.notifyListeners("ping", data: nil)
        XCTAssertEqual(received, ["one", "nil"])

        plugin.removeEventListener("ping", listener: listener)
        XCTAssertFalse(plugin.hasListeners("ping"))
    }

    func testRetainedArgumentsAreDeliveredOnceToTheFirstListener() {
        let plugin = TestFixturePlugin()
        plugin.notifyListeners("ping", data: ["id": "a"], retainUntilConsumed: true)
        plugin.notifyListeners("ping", data: ["id": "b"], retainUntilConsumed: true)
        XCTAssertEqual(plugin.retainedEventArguments?["ping"]?.count, 2)

        var first: [String] = []
        var second: [String] = []
        plugin.addEventListener("ping", listener: makeCall(onSuccess: { first.append($0.string("id") ?? "") }))
        plugin.addEventListener("ping", listener: makeCall(onSuccess: { second.append($0.string("id") ?? "") }))
        XCTAssertEqual(first, ["a", "b"])
        XCTAssertEqual(second, [])
        XCTAssertNil(plugin.retainedEventArguments?["ping"])
    }

    func testMissingListenerArgumentsDoNotCrash() {
        let plugin = TestFixturePlugin()
        var rejected = false
        // nil was harmless to message in Obj-C, so none of these may trap
        plugin.removeListener(makeCall())
        plugin.removeListener(makeCall(["eventName": "ping"]))
        plugin.removeListener(makeCall(["callbackId": "unknown"]))
        plugin.addListener(makeCall(onError: { _ in rejected = true }))
        XCTAssertTrue(rejected)
        XCTAssertEqual(plugin.eventListeners?.isEmpty, true)
    }

    func testRemoveAllListeners() {
        let plugin = TestFixturePlugin()
        plugin.addEventListener("ping", listener: makeCall())
        plugin.addEventListener("pong", listener: makeCall())

        // the bridge removes listeners without a call
        plugin.removeAllListeners()
        XCTAssertFalse(plugin.hasListeners("ping"))
        XCTAssertFalse(plugin.hasListeners("pong"))

        plugin.addEventListener("ping", listener: makeCall())
        var resolved = false
        plugin.perform(NSSelectorFromString("removeAllListeners:"), with: makeCall(onSuccess: { _ in resolved = true }))
        XCTAssertTrue(resolved)
        XCTAssertFalse(plugin.hasListeners("ping"))
    }

    func testRemoveAllListenersReleasesTheSavedListenerCalls() {
        let bridge = RecordingBridge(delegate: TestBridgeDelegate())
        bridge.registerPluginInstance(TestFixturePlugin())
        let send = { (method: String, options: [String: Any], callbackId: String) in
            bridge.handleJSCall(call: JSCall(options: options, pluginId: "TestFixture", method: method, callbackId: callbackId))
            bridge.dispatchQueue.sync {}
        }
        send("addListener", ["eventName": "ping"], "listener-1")
        send("addListener", ["eventName": "pong"], "listener-2")
        send("watch", [:], "watch-1")
        XCTAssertNotNil(bridge.savedCall(withID: "listener-1"))
        XCTAssertNotNil(bridge.savedCall(withID: "listener-2"))

        send("removeAllListeners", [:], "remove-all")
        XCTAssertNil(bridge.savedCall(withID: "listener-1"))
        XCTAssertNil(bridge.savedCall(withID: "listener-2"))
        XCTAssertNotNil(bridge.savedCall(withID: "watch-1"), "calls that are not listeners stay saved")
        XCTAssertEqual(bridge.messages.last?.callbackId, "remove-all")
        XCTAssertEqual(bridge.messages.last?.success, true)
    }

    func testListenersAreThreadSafe() {
        let plugin = TestFixturePlugin()
        DispatchQueue.concurrentPerform(iterations: 500) { index in
            let listener = makeCall()
            plugin.addEventListener("event\(index % 4)", listener: listener)
            plugin.notifyListeners("event\(index % 4)", data: ["index": index], retainUntilConsumed: index % 2 == 0)
            _ = plugin.hasListeners("event\(index % 4)")
            plugin.removeEventListener("event\(index % 4)", listener: listener)
        }
        for event in 0..<4 {
            XCTAssertFalse(plugin.hasListeners("event\(event)"))
        }
    }

    func testRuntimeHooksInstallOnlyOnce() throws {
        let selector = NSSelectorFromString("handleTapAction:")
        _ = CapacitorRuntimeHooks.install
        let installed = method_getImplementation(try XCTUnwrap(class_getInstanceMethod(UIStatusBarManager.self, selector)))
        _ = CapacitorRuntimeHooks.install
        // look the method up again after installing: a hook added with class_addMethod is a different Method than the inherited one
        XCTAssertEqual(installed, method_getImplementation(try XCTUnwrap(class_getInstanceMethod(UIStatusBarManager.self, selector))))
    }

    /// The status bar can't be tapped from a unit test, so send the private action to the real status bar manager
    /// of the host app instead: the installed hook has to post the notification and still reach UIKit's implementation.
    func testStatusBarTapHookPostsNotification() throws {
        _ = CapacitorRuntimeHooks.install
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let statusBarManager = try XCTUnwrap(scene?.statusBarManager, "the tests need the host app's window scene")
        let selector = NSSelectorFromString("handleTapAction:")
        XCTAssertTrue(statusBarManager.responds(to: selector))

        var posts = 0
        let token = NotificationCenter.default.addObserver(forName: .capacitorStatusBarTapped, object: nil, queue: nil) { _ in posts += 1 }
        defer { NotificationCenter.default.removeObserver(token) }
        // the hook posts synchronously before forwarding to UIKit, so no wait is needed
        statusBarManager.perform(selector, with: nil)
        XCTAssertEqual(posts, 1, "a hook installed twice would chain and post twice")
    }

    /// The keyboard hook replaces a private WKContentView method, which only exists once WebKit has loaded its view classes.
    func testKeyboardHookIsInstalledOnWKContentView() throws {
        _ = WKWebView(frame: .zero)
        _ = CapacitorRuntimeHooks.install
        let contentView: AnyClass = try XCTUnwrap(NSClassFromString("WK" + "ContentView"))
        let selector = sel_getUid("_elementDidFocus:userIsInteracting:blurPreviousNode:activityStateChanges:userObject:")
        let method = try XCTUnwrap(class_getInstanceMethod(contentView, selector), "WebKit no longer has the method Capacitor hooks for keyboardShouldRequireUserInteraction")
        // the hook replaces the IMP with one made by imp_implementationWithBlock; WebKit's own IMP is not a block
        XCTAssertNotNil(imp_getBlock(method_getImplementation(method)), "the keyboardShouldRequireUserInteraction hook is not installed")
    }
}
