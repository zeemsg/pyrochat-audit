import Foundation
import PDFKit

/// docs/SCRUB-AND-WIPE.md §B1, the PDF row: "the Info dictionary (author,
/// producer, creator, dates) and any XMP packet".
///
/// PDFKit owns the Info dictionary: read it, report it, replace it with an
/// empty one, write the document back out. The XMP packet is a different
/// animal — it lives in a stream hanging off the catalogue and PDFKit will
/// happily carry it through — so it is blanked in the OUTPUT bytes, in place,
/// with spaces. That is exactly what XMP's own padding rule exists for: the
/// packet is designed to be overwritable without moving anything, so every
/// byte offset in the cross-reference table stays valid and the document still
/// renders.
enum PDFScrub {

    /// Info-dictionary keys worth reporting, in the order they appear in the
    /// sheet. `PDFDocumentAttribute` values are the PDF names themselves.
    static let infoKeys: [(PDFDocumentAttribute, String)] = [
        (.authorAttribute, "author"),
        (.titleAttribute, "title"),
        (.subjectAttribute, "subject"),
        (.creatorAttribute, "creator"),
        (.producerAttribute, "producer"),
        (.keywordsAttribute, "keywords"),
        (.creationDateAttribute, "created"),
        (.modificationDateAttribute, "modified"),
    ]

    static func scrub(_ data: Data) -> (data: Data, report: MetadataScrubber.Report) {
        guard let doc = PDFDocument(data: data) else {
            // not a PDF we can open: say so rather than pretending
            return (data, .unsupported)
        }
        var removed: [ScrubField] = []
        let attrs: [AnyHashable: Any] = doc.documentAttributes ?? [:]
        for (key, label) in infoKeys {
            guard let raw = attrs[key.rawValue] ?? attrs[key] else { continue }
            let value = display(raw)
            guard !value.isEmpty else { continue }
            removed.append(ScrubField(label, value))
        }
        let xmp = xmpRange(in: data)
        if let xmp, !removed.contains(where: { $0.label == "xmp" }) {
            removed.append(ScrubField("xmp", "\(xmp.count) bytes"))
        }

        doc.documentAttributes = [:]
        guard var out = doc.dataRepresentation() else {
            // PDFKit could not write it back — the original is untouched and
            // nothing was cleaned, so the receipt must not claim otherwise
            return (data, .unsupported)
        }
        out = blankXMP(in: out)
        // PDFKit stamps its own Info dictionary on the way out (a generic
        // "Producer"/"Creator" naming the framework, never the user). Strip it
        // by name so the file leaves with nothing at all.
        out = blankInfoStrings(in: out)
        return (out, MetadataScrubber.Report(removed: removed, supported: true))
    }

    static func display(_ raw: Any) -> String {
        if let s = raw as? String { return s.trimmingCharacters(in: .whitespacesAndNewlines) }
        if let d = raw as? Date {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.dateFormat = "yyyy-MM-dd HH:mm:ss"
            return f.string(from: d)
        }
        if let a = raw as? [Any] { return a.map { display($0) }.joined(separator: ", ") }
        return ""
    }

    /// The XMP packet, as a byte range of the file (`<?xpacket begin` through
    /// the `?>` that closes `<?xpacket end`).
    static func xmpRange(in data: Data) -> Range<Int>? {
        guard let start = data.range(of: Data("<?xpacket begin".utf8)) else { return nil }
        guard let end = data.range(of: Data("<?xpacket end".utf8),
                                   in: start.upperBound..<data.endIndex) else { return nil }
        guard let close = data.range(of: Data("?>".utf8),
                                     in: end.upperBound..<data.endIndex) else { return nil }
        return start.lowerBound..<close.upperBound
    }

    /// Space-fill every XMP packet. Same length, same offsets, no metadata.
    static func blankXMP(in data: Data) -> Data {
        var out = data
        var from = 0
        while from < out.count,
              let start = out.range(of: Data("<?xpacket begin".utf8),
                                    in: from..<out.count),
              let end = out.range(of: Data("<?xpacket end".utf8),
                                  in: start.upperBound..<out.count),
              let close = out.range(of: Data("?>".utf8), in: end.upperBound..<out.count) {
            let range = start.lowerBound..<close.upperBound
            out.replaceSubrange(range, with: Data(repeating: 0x20, count: range.count))
            from = close.upperBound
        }
        return out
    }

    /// Whatever PDFKit put back into the Info dictionary on the way out (it
    /// stamps a generic `Producer`/`Creator` naming the framework, never the
    /// user), blanked in the bytes.
    ///
    /// The value is found through its OWN `/Name (…)` entry rather than by
    /// searching for its text: a PDF literal escapes its parentheses, so the
    /// string in the file is not the string PDFKit hands back. Only printable
    /// ASCII is overwritten, so a compressed stream can never be scribbled on
    /// by accident, and the replacement is the same length — every offset, and
    /// every `/Length`, stays exactly as it was.
    static let infoNames = ["/Producer", "/Creator", "/Author", "/Title",
                            "/Subject", "/Keywords", "/CreationDate", "/ModDate"]

    static func blankInfoStrings(in data: Data) -> Data {
        var out = data
        for name in infoNames {
            let needle = Data(name.utf8)
            var from = 0
            while from < out.count, let hit = out.range(of: needle, in: from..<out.count) {
                from = hit.upperBound
                var i = hit.upperBound
                while i < out.count, out[i] == 0x20 { i += 1 }
                guard i < out.count, out[i] == 0x28 else { continue }        // '('
                var j = i + 1
                var escaped = false
                var printable = true
                while j < out.count {
                    let b = out[j]
                    if escaped { escaped = false; j += 1; continue }
                    if b == 0x5C { escaped = true; j += 1; continue }        // backslash
                    if b == 0x29 { break }                                   // ')'
                    if b < 0x20 || b > 0x7E { printable = false }
                    j += 1
                }
                guard j < out.count, j > i + 1, printable else { continue }
                out.replaceSubrange((i + 1)..<j, with: Data(repeating: 0x20, count: j - i - 1))
                from = j
            }
        }
        return out
    }
}
