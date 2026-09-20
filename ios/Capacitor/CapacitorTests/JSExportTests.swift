import XCTest
import WebKit

@testable import Capacitor

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
}
