import Foundation
import UIKit
import WebKit

extension WKWebView: CapacitorExtension {}
public extension CapacitorExtensionTypeWrapper where T == WKWebView {
    var keyboardShouldRequireUserInteraction: Bool? {
        return (self.baseType.associatedKeyboardFlagValue as? NSNumber)?.boolValue
    }

    // the readonly nature of the wrapper extension means we can't use a computed property with a setter
    func setKeyboardShouldRequireUserInteraction(_ flag: Bool? = nil) {
        if let flag = flag {
            self.baseType.associatedKeyboardFlagValue = NSNumber(value: flag)
        } else {
            self.baseType.associatedKeyboardFlagValue = nil
        }
    }
}

private var associatedKeyboardFlagHandle: UInt8 = 0

/// Runtime hooks that used to be installed from Obj-C `+load` methods. Swift has no equivalent, so they are installed lazily, exactly
/// once, before the first web view or bridge is created (see `CAPBridgeViewController.loadView()` and `CapacitorBridge.init`).
internal enum CapacitorRuntimeHooks {
    // dispatch_once isn't available in Swift, but lazy static properties use the same mechanism under the hood so
    // we can safely assume that this block of code will only execute once.
    static let install: Void = {
        swizzleKeyboardMethods()
        swizzleStatusBarTapAction()
    }()

    private typealias FiveArgClosureType = @convention(c) (Any, Selector, UnsafeRawPointer, Bool, Bool, Bool, Any?) -> Void
    private typealias TapActionClosureType = @convention(c) (AnyObject, Selector, AnyObject?) -> Void

    /// Hooks the private `WKContentView._elementDidFocus:userIsInteracting:...` so a web view that has
    /// `keyboardShouldRequireUserInteraction` set passes `!flag` as `userIsInteracting`; a web view that
    /// never set it keeps WebKit's own value.
    private static func swizzleKeyboardMethods() {
        // The private class name is assembled at runtime so it never appears in the binary as a single
        // literal. `testKeyboardHookIsInstalledOnWKContentView` mirrors the same split. Do not fold these.
        let frameworkName = "WK"
        let className = "ContentView"
        guard let targetClass = NSClassFromString(frameworkName + className) else {
            return
        }

        let containingWebView = { (object: Any?) -> WKWebView? in
            var view = object as? UIView
            while view != nil {
                if let webview = view as? WKWebView {
                    return webview
                }
                view = view?.superview
            }
            return nil
        }

        let swizzleFiveArgClosure = { (method: Method, selector: Selector) in
            let originalImp: IMP = method_getImplementation(method)
            let original: FiveArgClosureType = unsafeBitCast(originalImp, to: FiveArgClosureType.self)
            // swiftlint:disable:next identifier_name
            let block: @convention(block) (Any, UnsafeRawPointer, Bool, Bool, Bool, Any?) -> Void = { (me, arg0, arg1, arg2, arg3, arg4) in
                if let webview = containingWebView(me), let flag = webview.capacitor.keyboardShouldRequireUserInteraction {
                    original(me, selector, arg0, !flag, arg2, arg3, arg4)
                } else {
                    original(me, selector, arg0, arg1, arg2, arg3, arg4)
                }
            }
            let imp: IMP = imp_implementationWithBlock(block)
            method_setImplementation(method, imp)
        }

        let selectorMkIV: Selector = sel_getUid("_elementDidFocus:userIsInteracting:blurPreviousNode:activityStateChanges:userObject:")

        if let method = class_getInstanceMethod(targetClass, selectorMkIV) {
            swizzleFiveArgClosure(method, selectorMkIV)
        }
    }

    /// Posts `Notification.Name.capacitorStatusBarTapped` whenever the status bar is tapped, then forwards to the original implementation.
    private static func swizzleStatusBarTapAction() {
        let targetClass: AnyClass = UIStatusBarManager.self
        let selector = NSSelectorFromString("handleTapAction:")
        guard let method = class_getInstanceMethod(targetClass, selector) else {
            return
        }

        let originalImp: IMP = method_getImplementation(method)
        let original: TapActionClosureType = unsafeBitCast(originalImp, to: TapActionClosureType.self)
        // swiftlint:disable:next identifier_name
        let block: @convention(block) (AnyObject, AnyObject?) -> Void = { (me, action) in
            NotificationCenter.default.post(name: .capacitorStatusBarTapped, object: nil)
            original(me, selector, action)
        }
        let imp: IMP = imp_implementationWithBlock(block)
        // if the method is inherited, add an override to this class instead of replacing the implementation of the superclass
        if !class_addMethod(targetClass, selector, imp, method_getTypeEncoding(method)) {
            method_setImplementation(method, imp)
        }
    }
}

internal extension WKWebView {
    var associatedKeyboardFlagValue: Any? {
        get {
            return objc_getAssociatedObject(self, &associatedKeyboardFlagHandle)
        }
        set {
            objc_setAssociatedObject(self, &associatedKeyboardFlagHandle, newValue, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        }
    }
}
