import Foundation

/// A single byte range of a local resource, resolved from an HTTP `Range` request header (RFC 9110, section 14).
///
/// Only one range is served. The web view asks for one range at a time when it streams media.
internal struct ByteRange: Equatable {
    /// The offset of the first byte of the range.
    let first: Int
    /// The offset of the last byte of the range, inclusive.
    let last: Int

    var length: Int {
        last - first + 1
    }

    /// The value of the `Content-Range` header of a 206 response.
    func contentRange(of size: Int) -> String {
        "bytes \(first)-\(last)/\(size)"
    }

    /// The value of the `Content-Range` header of a 416 response.
    static func unsatisfiedContentRange(of size: Int) -> String {
        "bytes */\(size)"
    }

    /// What to do with a request that carries a `Range` header.
    enum Resolution: Equatable {
        /// Ignore the header and answer with the whole resource (200). Used for range units other than `bytes` and for
        /// requests for several ranges, which would need a `multipart/byteranges` response.
        case whole
        /// Answer with this part of the resource (206).
        case partial(ByteRange)
        /// The header is malformed or no byte of the resource is in the range: answer with 416 and
        /// `Content-Range: bytes */size`.
        case unsatisfiable
    }

    /// Resolves a `Range` header against a resource of `size` bytes.
    ///
    /// Accepts `bytes=first-last`, `bytes=first-` and the suffix form `bytes=-count`. A last offset past the end of
    /// the resource is clamped to the last byte, and a suffix longer than the resource selects all of it.
    static func resolve(_ header: String, size: Int) -> Resolution {
        guard let equals = header.firstIndex(of: "=") else {
            return .unsatisfiable
        }
        let unit = header[..<equals].trimmingCharacters(in: .whitespaces)
        // A server must ignore a range unit it does not understand.
        guard unit.caseInsensitiveCompare("bytes") == .orderedSame else {
            return .whole
        }
        let specs = header[header.index(after: equals)...].split(separator: ",", omittingEmptySubsequences: false)
        guard specs.count == 1 else {
            return .whole
        }
        let spec = specs[0].trimmingCharacters(in: .whitespaces)
        guard let dash = spec.firstIndex(of: "-") else {
            return .unsatisfiable
        }
        let firstText = spec[..<dash]
        let lastText = spec[spec.index(after: dash)...]

        if firstText.isEmpty {
            // suffix range: the final `count` bytes
            guard let count = offset(lastText), count > 0, size > 0 else {
                return .unsatisfiable
            }
            return .partial(ByteRange(first: max(size - count, 0), last: size - 1))
        }

        guard let first = offset(firstText) else {
            return .unsatisfiable
        }
        var last = size - 1
        if !lastText.isEmpty {
            guard let requestedLast = offset(lastText), requestedLast >= first else {
                return .unsatisfiable
            }
            last = min(requestedLast, size - 1)
        }
        guard first < size else {
            return .unsatisfiable
        }
        return .partial(ByteRange(first: first, last: last))
    }

    /// Parses a byte offset. Offsets have no sign, and one too large for `Int` is past the end of any file.
    private static func offset(_ text: Substring) -> Int? {
        guard !text.isEmpty, text.allSatisfy({ $0.isASCII && $0.isNumber }) else {
            return nil
        }
        return Int(text) ?? Int.max
    }
}
