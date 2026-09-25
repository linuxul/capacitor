import XCTest

@testable import Capacitor

class ByteRangeTests: XCTestCase {
    private func resolve(_ header: String, size: Int = 1000) -> ByteRange.Resolution {
        ByteRange.resolve(header, size: size)
    }

    private func partial(_ first: Int, _ last: Int) -> ByteRange.Resolution {
        .partial(ByteRange(first: first, last: last))
    }

    func testClosedRange() {
        XCTAssertEqual(resolve("bytes=0-499"), partial(0, 499))
        XCTAssertEqual(resolve("bytes=500-999"), partial(500, 999))
        XCTAssertEqual(resolve("bytes=0-0"), partial(0, 0))
        XCTAssertEqual(ByteRange(first: 500, last: 999).length, 500)
    }

    func testOpenRangeRunsToTheEnd() {
        XCTAssertEqual(resolve("bytes=0-"), partial(0, 999))
        XCTAssertEqual(resolve("bytes=999-"), partial(999, 999))
    }

    func testSuffixRangeSelectsTheFinalBytes() {
        XCTAssertEqual(resolve("bytes=-500"), partial(500, 999))
        XCTAssertEqual(resolve("bytes=-1"), partial(999, 999))
        // a suffix longer than the resource selects all of it
        XCTAssertEqual(resolve("bytes=-5000"), partial(0, 999))
    }

    func testLastOffsetIsClampedToTheResource() {
        XCTAssertEqual(resolve("bytes=900-5000"), partial(900, 999))
        XCTAssertEqual(resolve("bytes=0-99999999999999999999999"), partial(0, 999))
    }

    func testUnitAndWhitespaceAreLenient() {
        XCTAssertEqual(resolve("Bytes=1-2"), partial(1, 2))
        XCTAssertEqual(resolve("bytes= 1-2 "), partial(1, 2))
    }

    func testUnsatisfiableRanges() {
        XCTAssertEqual(resolve("bytes=1000-"), .unsatisfiable)
        XCTAssertEqual(resolve("bytes=1000-1001"), .unsatisfiable)
        XCTAssertEqual(resolve("bytes=99999999999999999999999-"), .unsatisfiable)
        XCTAssertEqual(resolve("bytes=-0"), .unsatisfiable)
        XCTAssertEqual(resolve("bytes=0-", size: 0), .unsatisfiable)
        XCTAssertEqual(resolve("bytes=-10", size: 0), .unsatisfiable)
    }

    func testMalformedRangesAreUnsatisfiable() {
        for header in ["bytes", "bytes=", "bytes=-", "bytes=abc-", "bytes=1-abc", "bytes=5-3", "bytes=+1-2", "bytes=1--2", "bytes=1 2-3", "bytes=0x10-"] {
            XCTAssertEqual(resolve(header), .unsatisfiable, header)
        }
    }

    func testUnknownUnitsAndMultipleRangesAreIgnored() {
        XCTAssertEqual(resolve("items=0-5"), .whole)
        XCTAssertEqual(resolve("bytes=0-1,5-6"), .whole)
    }

    func testContentRangeValues() {
        XCTAssertEqual(ByteRange(first: 0, last: 1).contentRange(of: 10), "bytes 0-1/10")
        XCTAssertEqual(ByteRange.unsatisfiedContentRange(of: 10), "bytes */10")
    }
}
