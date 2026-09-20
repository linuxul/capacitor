import Foundation
import UIKit
import WebKit

public protocol CAPBridgeDelegate: AnyObject {
    var bridgedWebView: WKWebView? { get }
    var bridgedViewController: UIViewController? { get }
}
