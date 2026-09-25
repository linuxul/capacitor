import Foundation

// swiftlint:disable identifier_name
/**
 Notification names Capacitor posts to NotificationCenter

 We want to include `capacitor` in the name(s) to uniquely identify these even though it can make the names long.
 */
extension Notification.Name {
    public static let capacitorOpenURL = Notification.Name(rawValue: "CapacitorOpenURLNotification")
    public static let capacitorOpenUniversalLink = Notification.Name(rawValue: "CapacitorOpenUniversalLinkNotification")
    public static let capacitorContinueActivity = Notification.Name(rawValue: "CapacitorContinueActivityNotification")
    public static let capacitorDidRegisterForRemoteNotifications =
        Notification.Name(rawValue: "CapacitorDidRegisterForRemoteNotificationsNotification")
    public static let capacitorDidFailToRegisterForRemoteNotifications =
        Notification.Name(rawValue: "CapacitorDidFailToRegisterForRemoteNotificationsNotification")
    public static let capacitorDecidePolicyForNavigationAction =
        Notification.Name(rawValue: "CapacitorDecidePolicyForNavigationActionNotification")
    public static let capacitorStatusBarTapped = Notification.Name(rawValue: "CapacitorStatusBarTappedNotification")
    public static let capacitorViewDidAppear = Notification.Name(rawValue: "CapacitorViewDidAppear")
    public static let capacitorViewWillTransition = Notification.Name(rawValue: "CapacitorViewWillTransition")
    public static let capacitorSceneWillConnect = Notification.Name(rawValue: "CapacitorSceneWillConnect")
    public static let capacitorSceneOpenURL = Notification.Name(rawValue: "CapacitorSceneOpenURLNotification")
    public static let capacitorSceneOpenUniversalLink =
        Notification.Name(rawValue: "CapacitorSceneOpenUniversalLinkNotification")
}

// swiftlint:enable identifier_name
