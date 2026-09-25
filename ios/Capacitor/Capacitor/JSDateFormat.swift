import Foundation

/// The ISO 8601 form in which the runtime reads and writes dates: the defaults of `ISO8601DateFormatter`, an internet
/// date and time in UTC with whole seconds, such as `2023-11-14T22:13:20Z`. Fractional seconds are dropped when
/// writing and not accepted when reading.
///
/// `ISO8601DateFormatter` is thread-safe, so the runtime shares one ``formatter``.
internal enum JSDateFormat {
    /// A new formatter with these options, for a caller that hands its formatter out, as
    /// ``CAPPluginCall/jsDateFormatter`` does: a plugin may change that one's options, which must not change the
    /// dates the runtime writes.
    static func makeFormatter() -> ISO8601DateFormatter {
        return ISO8601DateFormatter()
    }

    /// The formatter the runtime shares. Never change its options.
    static let formatter = makeFormatter()

    static func string(from date: Date) -> String {
        return formatter.string(from: date)
    }

    static func date(from string: String) -> Date? {
        return formatter.date(from: string)
    }
}
