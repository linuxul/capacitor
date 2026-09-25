import JavaScriptCore
import XCTest
import WebKit

@testable import Capacitor

/// Ends a string literal written between single or double quotes and breaks one with line terminators.
private let hostile = "it's \"quoted\" \\ next line\n\r\u{2028}\u{2029}'); window.injected = true; ('"

@objc(CAPJSExportProxyPlugin)
private final class ProxyPlugin: CAPPlugin, CAPBridgedPlugin {
    let identifier = "CAPJSExportProxyPlugin"
    let jsName = "Proxy"
    let pluginMethods: [CAPPluginMethod] = [
        .promise("echo", ProxyPlugin.handle),
        .callback("watch", ProxyPlugin.handle),
        .none("fire", ProxyPlugin.handle),
        .async("run", ProxyPlugin.run)
    ]

    private func handle(_ call: CAPPluginCall) {}

    private func run(_ call: CAPPluginCall) async {}
}

/// A plugin whose JavaScript name and method names would break a script that quoted them by hand.
@objc(CAPJSExportHostileNamePlugin)
private final class HostileNamePlugin: CAPPlugin, CAPBridgedPlugin {
    let identifier = "CAPJSExportHostileNamePlugin"
    let jsName = hostile
    let pluginMethods: [CAPPluginMethod] = [
        .promise(hostile, HostileNamePlugin.handle),
        .callback(hostile + " watch", HostileNamePlugin.handle)
    ]

    private func handle(_ call: CAPPluginCall) {}
}

/// A JavaScript context with a stand-in `window`, for running the scripts the bridge injects. Its `window.Capacitor`
/// records each call a plugin proxy makes in `calls`; every script run must not throw.
private final class PageContext {
    private let context: JSContext
    private var exceptions: [String] = []

    init?() {
        guard let context = JSContext() else { return nil }
        self.context = context
        context.exceptionHandler = { [weak self] _, value in
            self?.exceptions.append(value?.toString() ?? "exception")
        }
        run("""
        var calls = [];
        function recorder(fn, result) {
          return function () {
            var args = Array.prototype.slice.call(arguments).map(function (arg) {
              return typeof arg === 'function' ? '<function>' : arg;
            });
            calls.push({ fn: fn, args: args, text: fn + '(' + JSON.stringify(args).slice(1, -1) + ')' });
            return result;
          };
        }
        var window = { Capacitor: {
          addListener: recorder('addListener', 'listener'),
          nativePromise: recorder('nativePromise', 'promise'),
          nativeCallback: recorder('nativeCallback', 'callback')
        } };
        """)
    }

    @discardableResult
    func run(_ script: String, file: StaticString = #filePath, line: UInt = #line) -> JavaScriptCore.JSValue? {
        let value = context.evaluateScript(script)
        XCTAssertEqual(exceptions, [], script, file: file, line: line)
        exceptions.removeAll()
        return value
    }

    func set(_ value: String, as name: String) {
        context.setObject(value, forKeyedSubscript: name as NSString)
    }

    /// Each recorded call as `fn(arguments as JSON)`.
    var callTexts: [String] {
        run("calls.map(function (call) { return call.text; })")?.toArray() as? [String] ?? []
    }

    var calls: [(fn: String, args: [Any])] {
        let recorded = run("calls")?.toArray() as? [[String: Any]] ?? []
        return recorded.map { ($0["fn"] as? String ?? "", $0["args"] as? [Any] ?? []) }
    }
}

class JSExportTests: XCTestCase {

    override func setUpWithError() throws {
        // Put setup code here. This method is called before the invocation of each test method in the class.
    }

    override func tearDownWithError() throws {
        // Put teardown code here. This method is called after the invocation of each test method in the class.
    }

    func testBridgeBundle() throws {
        let contentController = WKUserContentController()
        try Capacitor.JSExport.exportBridgeJS(userContentController: contentController)
        // A clean return only proves the resource URL resolved and was read; an empty or truncated
        // resource would inject just as quietly. Check the bridge JS actually landed, with content.
        let script = try XCTUnwrap(contentController.userScripts.first)
        XCTAssertFalse(script.source.isEmpty)
    }

    // MARK: - Global script

    func testTheServerURLReachesThePageAsItIs() throws {
        let urls = [
            "capacitor://localhost",
            "http://192.168.0.10:8100/app?q=a%20b#top",
            "capacitor://it's\\here\nnext\u{2028}line",
            // ends the literal of a hand-quoted script and runs the rest
            "capacitor://localhost'; window.injected = true; '",
            hostile
        ]
        for url in urls {
            let contentController = WKUserContentController()
            try JSExport.exportCapacitorGlobalJS(userContentController: contentController, isDebug: true, loggingEnabled: false, localUrl: url)
            XCTAssertEqual(contentController.userScripts.count, 1)
            let script = try XCTUnwrap(contentController.userScripts.first)
            XCTAssertEqual(script.injectionTime, .atDocumentStart)
            XCTAssertTrue(script.isForMainFrameOnly)
            XCTAssertTrue(script.source.hasPrefix("window.Capacitor = { DEBUG: true, isLoggingEnabled: false, Plugins: {} }; window.WEBVIEW_SERVER_URL = "),
                          script.source)

            let page = try XCTUnwrap(PageContext())
            page.run(script.source)
            XCTAssertEqual(page.run("typeof window.WEBVIEW_SERVER_URL")?.toString(), "string")
            XCTAssertEqual(page.run("window.WEBVIEW_SERVER_URL")?.toString(), url)
            XCTAssertEqual(page.run("typeof window.injected")?.toString(), "undefined", script.source)
            XCTAssertEqual(page.run("[window.Capacitor.DEBUG, window.Capacitor.isLoggingEnabled, typeof window.Capacitor.Plugins].join()")?.toString(),
                           "true,false,object")
        }
    }

    // MARK: - Plugin proxies

    /// The proxy script of `ProxyPlugin` up to its header. Names are JSON literals, so for ordinary names this is the
    /// script that quoted them by hand with single quotes, in double quotes.
    private let expectedProxy = """
    (function(w) {
    var a = (w.Capacitor = w.Capacitor || {});
    var p = (a.Plugins = a.Plugins || {});
    var t = (p["Proxy"] = {});
    t.addListener = function(eventName, callback) {
    return w.Capacitor.addListener("Proxy", eventName, callback);
    }
    t.removeAllListeners = function() {
    return w.Capacitor.nativePromise("Proxy", "removeAllListeners");
    }
    t["echo"] = function(_options) {
    return w.Capacitor.nativePromise("Proxy", "echo", _options);
    }
    t["watch"] = function(_options, _callback) {
    return w.Capacitor.nativeCallback("Proxy", "watch", _options, _callback);
    }
    t["fire"] = function(_options) {
    return w.Capacitor.nativeCallback("Proxy", "fire", _options);
    }
    t["run"] = function(_options) {
    return w.Capacitor.nativePromise("Proxy", "run", _options);
    }
    })(window);
    """

    func testThePluginProxyOfAnOrdinaryPlugin() throws {
        let contentController = WKUserContentController()
        JSExport.exportJS(for: ProxyPlugin(), in: contentController)
        XCTAssertEqual(contentController.userScripts.count, 1)
        let script = try XCTUnwrap(contentController.userScripts.first)
        XCTAssertEqual(script.injectionTime, .atDocumentStart)
        XCTAssertTrue(script.isForMainFrameOnly)

        let headerStart = "\n(function(w) {\nvar a = (w.Capacitor = w.Capacitor || {});\nvar h = (a.PluginHeaders = a.PluginHeaders || []);\nh.push("
        let parts = script.source.components(separatedBy: headerStart)
        XCTAssertEqual(parts.count, 2, script.source)
        XCTAssertEqual(parts.first, expectedProxy)
        XCTAssertEqual(parts.last?.hasSuffix(");\n})(window);"), true, script.source)

        let page = try XCTUnwrap(PageContext())
        page.run(script.source)
        XCTAssertEqual(page.run("Object.keys(window.Capacitor.Plugins.Proxy)")?.toArray() as? [String],
                       ["addListener", "removeAllListeners", "echo", "watch", "fire", "run"])
        XCTAssertEqual(page.run("window.Capacitor.PluginHeaders.map(function (h) { return h.name; })")?.toArray() as? [String], ["Proxy"])

        let returned = page.run("""
        var t = window.Capacitor.Plugins.Proxy;
        [t.echo({ value: 1 }), t.watch({ value: 2 }, function () {}), t.fire({ value: 3 }), t.run({ value: 4 }),
         t.addListener('changed', function () {}), t.removeAllListeners()];
        """)
        XCTAssertEqual(returned?.toArray() as? [String], ["promise", "callback", "callback", "promise", "listener", "promise"])
        XCTAssertEqual(page.callTexts, [
            #"nativePromise("Proxy","echo",{"value":1})"#,
            #"nativeCallback("Proxy","watch",{"value":2},"<function>")"#,
            #"nativeCallback("Proxy","fire",{"value":3})"#,
            #"nativePromise("Proxy","run",{"value":4})"#,
            #"addListener("Proxy","changed","<function>")"#,
            #"nativePromise("Proxy","removeAllListeners")"#
        ])
    }

    func testAPluginProxyKeepsNamesThatWouldBreakAHandQuotedScript() throws {
        let contentController = WKUserContentController()
        JSExport.exportJS(for: HostileNamePlugin(), in: contentController)
        let script = try XCTUnwrap(contentController.userScripts.first)

        let page = try XCTUnwrap(PageContext())
        page.run(script.source)
        page.set(hostile, as: "name")
        XCTAssertEqual(page.run("Object.keys(window.Capacitor.Plugins)")?.toArray() as? [String], [hostile])
        XCTAssertEqual(page.run("Object.keys(window.Capacitor.Plugins[name])")?.toArray() as? [String],
                       ["addListener", "removeAllListeners", hostile, hostile + " watch"])
        XCTAssertEqual(page.run("window.Capacitor.PluginHeaders[0].name")?.toString(), hostile)

        page.run("""
        var t = window.Capacitor.Plugins[name];
        t[name]({});
        t[name + ' watch']({}, function () {});
        t.addListener('changed', function () {});
        t.removeAllListeners();
        """)
        let calls = page.calls
        XCTAssertEqual(calls.map { $0.fn }, ["nativePromise", "nativeCallback", "addListener", "nativePromise"])
        XCTAssertEqual(calls.map { $0.args.first as? String }, [hostile, hostile, hostile, hostile])
        XCTAssertEqual(calls.map { $0.args.dropFirst().first as? String }, [hostile, hostile + " watch", "changed", "removeAllListeners"])
        XCTAssertEqual(page.run("typeof window.injected")?.toString(), "undefined")
    }
}
