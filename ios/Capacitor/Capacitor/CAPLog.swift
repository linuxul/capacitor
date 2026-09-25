import Foundation

public class CAPLog {
    /// Whether ``print(_:separator:terminator:)`` writes anything. The bridge sets it from the logging behaviour in the
    /// configuration. Safe to read and write from any thread.
    public static var enableLogging: Bool {
        get { enabled.value }
        set { enabled.value = newValue }
    }

    private static let enabled = LockedFlag(true)

    public static func print(_ items: Any..., separator: String = " ", terminator: String = "\n") {
        if enableLogging {
            for (itemIndex, item) in items.enumerated() {
                Swift.print("\(item)".prefix(4068), terminator: itemIndex == items.count - 1 ? terminator : separator)
            }
        }
    }
}

/// A Boolean behind a lock.
private final class LockedFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var flag: Bool

    init(_ flag: Bool) {
        self.flag = flag
    }

    var value: Bool {
        get { lock.withLock { flag } }
        set { lock.withLock { flag = newValue } }
    }
}
