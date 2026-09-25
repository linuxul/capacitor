import Foundation
import UIKit

// MARK: - CAPBridgeProtocol: Environment and Status Bar

extension CapacitorBridge {
    // this decision is needed before the bridge is instantiated,
    // so we need a class property to avoid duplication
    public static var isDevEnvironment: Bool {
        #if DEBUG
        return true
        #else
        // this is needed for SPM xcframework Capacitor.  Can eventually be removed when the SPM package moves to being source-based.
        if let debugValue = Bundle.main.object(forInfoDictionaryKey: "CAPACITOR_DEBUG") as? String, debugValue == "true" {
            return true
        }
        return false
        #endif
    }

    public var isSimEnvironment: Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }

    public var isDevEnvironment: Bool {
        return CapacitorBridge.isDevEnvironment
    }

    public var userInterfaceStyle: UIUserInterfaceStyle {
        return viewController?.traitCollection.userInterfaceStyle ?? .unspecified
    }

    public var statusBarVisible: Bool {
        get {
            return !(viewController?.prefersStatusBarHidden ?? true)
        }
        set {
            DispatchQueue.main.async { [weak self] in
                (self?.viewController as? CAPBridgeViewController)?.setStatusBarVisible(newValue)
            }
        }
    }

    public var statusBarStyle: UIStatusBarStyle {
        get {
            return viewController?.preferredStatusBarStyle ?? .default
        }
        set {
            DispatchQueue.main.async { [weak self] in
                (self?.viewController as? CAPBridgeViewController)?.setStatusBarStyle(newValue)
            }
        }
    }

    public var statusBarAnimation: UIStatusBarAnimation {
        get {
            return (viewController as? CAPBridgeViewController)?.statusBarAnimation ?? .slide
        }
        set {
            DispatchQueue.main.async { [weak self] in
                (self?.viewController as? CAPBridgeViewController)?.setStatusBarAnimation(newValue)
            }
        }
    }
}
