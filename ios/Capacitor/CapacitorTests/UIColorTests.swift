import UIKit
import XCTest

@testable import Capacitor

class UIColorTests: XCTestCase {
    private func components(_ color: UIColor) -> [CGFloat] {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        XCTAssertTrue(color.getRed(&red, green: &green, blue: &blue, alpha: &alpha))
        return [red, green, blue, alpha]
    }

    private func assertComponents(_ color: UIColor, _ expected: [CGFloat], file: StaticString = #filePath, line: UInt = #line) {
        let actual = components(color)
        for (value, target) in zip(actual, expected) {
            XCTAssertEqual(value, target, accuracy: 0.001, "\(actual) != \(expected)", file: file, line: line)
        }
    }

    func testArgbComponentsAreScaledToUnitRange() {
        assertComponents(UIColor.capacitor.color(argb: 0xFF00_0000), [0, 0, 0, 1])
        assertComponents(UIColor.capacitor.color(argb: 0xFFFF_FFFF), [1, 1, 1, 1])
        assertComponents(UIColor.capacitor.color(argb: 0x80FF_8000), [1, 128.0 / 255, 0, 128.0 / 255])
        assertComponents(UIColor.capacitor.color(argb: 0x0000_00FF), [0, 0, 1, 0])
    }

    func testArgbMatchesTheOtherFactories() {
        let argb = UIColor.capacitor.color(argb: 0xCC11_2233)
        XCTAssertEqual(components(argb), components(UIColor.capacitor.color(r: 0x11, g: 0x22, b: 0x33, a: 0xCC)))
        XCTAssertEqual(components(argb), components(UIColor.capacitor.color(fromHex: "#112233CC")!))
    }

    func testHexStrings() {
        assertComponents(UIColor.capacitor.color(fromHex: "#FF0000")!, [1, 0, 0, 1])
        assertComponents(UIColor.capacitor.color(fromHex: "00FF0080")!, [0, 1, 0, 128.0 / 255])
        XCTAssertNil(UIColor.capacitor.color(fromHex: "#FFF"))
        XCTAssertNil(UIColor.capacitor.color(fromHex: "not a color"))
    }
}
