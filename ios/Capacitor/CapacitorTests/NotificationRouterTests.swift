import UserNotifications
import XCTest

@testable import Capacitor

/// A handler that is not an NSObject, which the protocol allows since it is a Swift protocol.
private final class SwiftHandler: NotificationHandlerProtocol {
    func willPresent(notification: UNNotification) -> UNNotificationPresentationOptions {
        [.banner]
    }

    func didReceive(response: UNNotificationResponse) {}
}

class NotificationRouterTests: XCTestCase {
    func testHandlersNeedNotBeObjCObjectsAndAreHeldWeakly() {
        let router = NotificationRouter()
        var push: SwiftHandler? = SwiftHandler()
        let local = SwiftHandler()
        router.pushNotificationHandler = push
        router.localNotificationHandler = local
        XCTAssertTrue(router.pushNotificationHandler === push)
        XCTAssertTrue(router.localNotificationHandler === local)

        push = nil
        XCTAssertNil(router.pushNotificationHandler)
        XCTAssertTrue(router.localNotificationHandler === local)
    }
}
