import Foundation
import UIKit

// MARK: - Scene Lifecycle

extension CapacitorBridge {
    /**
     Observe scene lifecycle transitions and forward them to the page as `resume` and `pause` document events.
     */
    func setupLifecycleObservers() {
        let center = NotificationCenter.default
        let resume = center.addObserver(forName: UIScene.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] notification in
            self?.triggerSceneLifecycleJSEvent("resume", for: notification)
        }
        let pause = center.addObserver(forName: UIScene.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] notification in
            self?.triggerSceneLifecycleJSEvent("pause", for: notification)
        }
        observers.append(contentsOf: [resume, pause])
    }

    /**
     Forward a scene lifecycle transition to the page as a document event, but only once
     the page exists to receive it.

     On a cold start `UIScene.willEnterForegroundNotification` is posted while the initial
     load is still in flight, before `window.Capacitor` has been defined. Evaluating
     `triggerEvent` at that point throws inside the web view and surfaces as a
     "JS Eval error" in the log. The same happens when the web content process was
     terminated and the page is being reloaded. In both cases the page is about to load
     from scratch, so there is nothing for it to resume or pause; the event is dropped
     rather than deferred, because a "resume" delivered right after a fresh load would
     make the page react to a transition it never went through.
     */
    private func triggerSceneLifecycleJSEvent(_ eventName: String, for notification: Notification) {
        guard let scene = notification.object as? UIWindowScene,
              scene === viewController?.view.window?.windowScene else {
            return
        }
        guard case .subsequentLoad = webViewDelegationHandler.webViewLoadingState, webView?.isLoading == false else {
            return
        }
        triggerDocumentJSEvent(eventName: eventName)
    }
}
