import UniformTypeIdentifiers
import XCTest

@testable import Capacitor

class MimeTypesTests: XCTestCase {
    func testPrefersTheSystemType() {
        XCTAssertEqual(MimeTypes.mimeType(forPathExtension: "html"), "text/html")
        XCTAssertEqual(MimeTypes.mimeType(forPathExtension: "png"), "image/png")
        // the table says application/x-javascript, but the system's type comes first
        XCTAssertEqual(MimeTypes.table["js"], "application/x-javascript")
        XCTAssertEqual(MimeTypes.mimeType(forPathExtension: "js"), "text/javascript")
    }

    func testFallsBackToTheTable() {
        XCTAssertNil(UTType(filenameExtension: "jck")?.preferredMIMEType, "the system must not know this extension")
        XCTAssertEqual(MimeTypes.mimeType(forPathExtension: "jck"), "application/liquidmotion")
    }

    func testServesUnknownExtensionsAsBinary() {
        XCTAssertEqual(MimeTypes.mimeType(forPathExtension: "zzqx"), "application/octet-stream")
    }

    func testServesAPathWithoutExtensionAsHTML() {
        XCTAssertEqual(MimeTypes.mimeType(forPathExtension: ""), "text/html")
    }

    func testTheAssetHandlerUsesTheSameTypes() {
        let handler = WebViewAssetHandler(router: CapacitorRouter())
        XCTAssertEqual(handler.mimeTypes, MimeTypes.table)
        for pathExtension in ["", "html", "js", "jck", "zzqx"] {
            XCTAssertEqual(handler.mimeTypeForExtension(pathExtension: pathExtension),
                           MimeTypes.mimeType(forPathExtension: pathExtension), pathExtension)
        }
    }
}
