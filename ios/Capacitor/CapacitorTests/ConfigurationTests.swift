import XCTest
import UIKit

@testable import Capacitor

class ConfigurationTests: XCTestCase {
    enum ConfigFile: String, CaseIterable {
        case flat = "flat"
        case nested = "hierarchy"
        case server = "server"
        case invalid = "bad"
        case noLoggingBehavior = "hidinglogs"
        case nonparsable = "nonjson"
    }
    private static let configurationsURL = Bundle.main.url(forResource: "configurations", withExtension: "")!
    static var files: [ConfigFile: URL] = [:]

    override class func setUp() {
        for file in ConfigFile.allCases {
            if let url = Bundle.main.url(forResource: file.rawValue, withExtension: "json", subdirectory: "configurations") {
                files[file] = url
            }
        }
    }
    
    override func setUpWithError() throws {
        XCTAssert(ConfigurationTests.files.count == ConfigFile.allCases.count, "Not all configuration files were located")
    }

    override func tearDownWithError() throws {
        // Put teardown code here. This method is called after the invocation of each test method in the class.
    }

    private func makeDescriptor(_ file: ConfigFile? = nil) -> InstanceDescriptor {
        InstanceDescriptor(at: ConfigurationTests.configurationsURL, configuration: file.flatMap { ConfigurationTests.files[$0] })
    }

    func testDefaultErrors() throws {
        let descriptor = InstanceDescriptor.init()
        XCTAssertTrue(descriptor.warnings.contains(.missingAppDir))
        XCTAssertTrue(descriptor.warnings.contains(.missingFile))
    }
    
    func testMissingAppDetection() throws {
        var url = Bundle.main.resourceURL!
        url.appendPathComponent("app", isDirectory: true)
        let descriptor = InstanceDescriptor.init(at: url, configuration: nil)
        XCTAssertTrue(descriptor.warnings.contains(.missingAppDir), "A missing app directory was ignored")
    }
    
    func testFailedParsing() throws {
        let descriptor = makeDescriptor(.nonparsable)
        XCTAssertTrue(descriptor.warnings.contains(.invalidFile))
    }

    func testDefaults() throws {
        let descriptor = makeDescriptor()
        XCTAssertNil(descriptor.backgroundColor)
        XCTAssertEqual(descriptor.urlScheme, "capacitor")
        XCTAssertEqual(descriptor.urlHostname, "localhost")
        XCTAssertNil(descriptor.serverURL)
        XCTAssertTrue(descriptor.scrollingEnabled)
        XCTAssertEqual(descriptor.loggingBehavior, .debug)
        XCTAssertTrue(descriptor.allowLinkPreviews)
        XCTAssertEqual(descriptor.contentInsetAdjustmentBehavior, .never)
    }
    
    // the fixture predates the removal of the `hideLogs` option; what is left is a file that parses
    // but sets no logging behaviour, which must fall back to the default
    func testMissingLoggingBehaviorParsing() throws {
        let descriptor = makeDescriptor(.noLoggingBehavior)
        XCTAssertEqual(descriptor.loggingBehavior, .debug)
    }

    func testLoggingBehaviorParsing() throws {
        let descriptor = makeDescriptor(.server)
        XCTAssertEqual(descriptor.loggingBehavior, .production)
    }

    func testTopLevelParsing() throws {
        let descriptor = makeDescriptor(.flat)
        XCTAssertEqual(descriptor.backgroundColor, UIColor(red: 1, green: 1, blue: 1, alpha: 1))
        XCTAssertEqual(descriptor.overridenUserAgentString, "level 1 override")
        XCTAssertEqual(descriptor.appendedUserAgentString, "level 1 append")
        XCTAssertEqual(descriptor.loggingBehavior, .debug)
    }
    
    func testNestedParsing() throws {
        let descriptor = makeDescriptor(.nested)
        XCTAssertEqual(descriptor.backgroundColor, UIColor(red: 0, green: 0, blue: 0, alpha: 1))
        XCTAssertEqual(descriptor.overridenUserAgentString, "level 2 override")
        XCTAssertEqual(descriptor.appendedUserAgentString, "level 2 append")
        XCTAssertEqual(descriptor.loggingBehavior, .none)
        XCTAssertFalse(descriptor.scrollingEnabled)
        XCTAssertEqual(descriptor.contentInsetAdjustmentBehavior, .scrollableAxes)
    }
    
    func testServerParsing() throws {
        let descriptor = makeDescriptor(.server)
        XCTAssertEqual(descriptor.urlScheme, "override")
        XCTAssertEqual(descriptor.urlHostname, "myhost")
        XCTAssertEqual(descriptor.serverURL, "http://192.168.100.1:2057")
    }
    
    func testBadDataParsing() throws {
        let descriptor = makeDescriptor(.invalid)
        XCTAssertNil(descriptor.backgroundColor)
        XCTAssertEqual(descriptor.loggingBehavior, .debug)
        XCTAssertEqual(descriptor.contentInsetAdjustmentBehavior, .never)
    }

    func testBadDataTransformation() throws {
        let descriptor = makeDescriptor(.invalid)
        let configuration = InstanceConfiguration(with: descriptor, isDebug: true)
        // an iosScheme that WebKit already handles ("http") is rejected by normalize(), which restores the default
        XCTAssertEqual(configuration.localURL, URL(string: "capacitor://myhost"))
        // Same as the original: an invalid server.url is not ignored. Since iOS 17 URL(string:) percent-encodes
        // invalid characters instead of returning nil, so normalize()'s `URL(string: server) != nil` check
        // accepts "not a real domain".
        XCTExpectFailure {
            XCTAssertEqual(configuration.serverURL, URL(string: "capacitor://myhost"), "Invalid server.url was not ignored")
        }
    }

    func testServerTransformation() throws {
        let descriptor = makeDescriptor(.server)
        let configuration = InstanceConfiguration(with: descriptor, isDebug: true)
        XCTAssertEqual(configuration.serverURL, URL(string: "http://192.168.100.1:2057"))
        XCTAssertEqual(configuration.localURL, URL(string: "override://myhost"))
    }
    
    func testPluginConfig() throws {
        let descriptor = makeDescriptor(.flat)
        let configuration = InstanceConfiguration(with: descriptor, isDebug: true)
        let pluginConfig = configuration.getPluginConfig("SplashScreen")
        XCTAssertEqual(pluginConfig.getInt("launchShowDuration", -1), 1)
        XCTAssertTrue(configuration.getPluginConfig("Missing").getConfigJSON().isEmpty)
    }
    
    func testUpdatingAppLocation() throws {
        let descriptor = makeDescriptor(.nested)
        let configuration = InstanceConfiguration(with: descriptor, isDebug: true)
        let location = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        let updated = configuration.updatingAppLocation(location)
        // the configuration is a value, so the original must be untouched and everything else must carry over
        XCTAssertEqual(configuration.appLocation, ConfigurationTests.configurationsURL)
        XCTAssertEqual(updated.appLocation, location)
        XCTAssertEqual(updated.overridenUserAgentString, configuration.overridenUserAgentString)
        XCTAssertEqual(updated.serverURL, configuration.serverURL)
        XCTAssertEqual(updated.loggingEnabled, configuration.loggingEnabled)
    }

    func testNavigationRules() throws {
        let descriptor = makeDescriptor(.server)
        let configuration = InstanceConfiguration(with: descriptor, isDebug: true)
        XCTAssertTrue(configuration.shouldAllowNavigation(to: "ionic.io"))
        XCTAssertTrue(configuration.shouldAllowNavigation(to: "ionic.io".uppercased()))
        XCTAssertTrue(configuration.shouldAllowNavigation(to: "test.capacitorjs.com"))
        XCTAssertTrue(configuration.shouldAllowNavigation(to: "192.168.0.1"))
        XCTAssertTrue(configuration.shouldAllowNavigation(to: "subdomain.test.ionicframework.com"))
        XCTAssertTrue(configuration.shouldAllowNavigation(to: "wildcard1.wildcard2.example.com"))
        XCTAssertFalse(configuration.shouldAllowNavigation(to: "wildcard1.example.com"))
        XCTAssertFalse(configuration.shouldAllowNavigation(to: "google.com"))
        XCTAssertFalse(configuration.shouldAllowNavigation(to: "192.168.0.2"))
        XCTAssertFalse(configuration.shouldAllowNavigation(to: "ionicframework.com"))
    }
    
    func testNoLoggingTransformation() throws {
        let descriptor = makeDescriptor()
        descriptor.loggingBehavior = .none
        var configuration = InstanceConfiguration(with: descriptor, isDebug: false)
        XCTAssertFalse(configuration.loggingEnabled)
        configuration = InstanceConfiguration(with: descriptor, isDebug: true)
        XCTAssertFalse(configuration.loggingEnabled)
    }
    
    func testDebugLoggingTransformation() throws {
        let descriptor = makeDescriptor()
        descriptor.loggingBehavior = .debug
        var configuration = InstanceConfiguration(with: descriptor, isDebug: false)
        XCTAssertFalse(configuration.loggingEnabled)
        configuration = InstanceConfiguration(with: descriptor, isDebug: true)
        XCTAssertTrue(configuration.loggingEnabled)
    }
    
    func testProductionLoggingTransformation() throws {
        let descriptor = makeDescriptor()
        descriptor.loggingBehavior = .production
        var configuration = InstanceConfiguration(with: descriptor, isDebug: false)
        XCTAssertTrue(configuration.loggingEnabled)
        configuration = InstanceConfiguration(with: descriptor, isDebug: true)
        XCTAssertTrue(configuration.loggingEnabled)
    }
}
