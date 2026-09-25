import Foundation
import UserNotifications

/// Handles the notifications that ``NotificationRouter`` passes on: push notifications to its
/// `pushNotificationHandler`, local notifications to its `localNotificationHandler`. The router holds handlers weakly.
public protocol NotificationHandlerProtocol: AnyObject {
    func willPresent(notification: UNNotification) -> UNNotificationPresentationOptions
    func didReceive(response: UNNotificationResponse)
}
