import Foundation
import Compression

// docs/SCRUB-AND-WIPE.md §B1, the Office row: "docx / xlsx / pptx —
// docProps/core.xml and docProps/app.xml, rewriting the zip".
//
// Foundation has no zip, and this feature is not worth a third-party
// dependency, so what is needed is written out here: a central-directory
// reader and a writer. Every part that stays is copied with its ALREADY
// COMPRESSED bytes, its CRC and its sizes untouched — nothing is inflated,
// recompressed, or re-encoded. Only the two parts that are being removed are
// read (with Compression's raw DEFLATE), and only so the receipt can say what
// was in them.

// MARK: - the zip

enum MiniZip {
    struct Entry {
        var name: String
        var versionMadeBy: UInt16
        var versionNeeded: UInt16
        var flags: UInt16
        var method: UInt16
        var modTime: UInt16
        var modDate: UInt16
        var crc: UInt32
        var compressedSize: Int
        var uncompressedSize: Int
        var externalAttrs: UInt32
        var localHeaderOffset: Int
    }

    /// One member of a zip being written: either a part copied through with
    /// its original compressed bytes, or a part we rewrote (stored, because
    /// there is nothing to gain by deflating a few hundred bytes).
    struct Member {
        var name: String
        var method: UInt16
        var crc: UInt32
        var compressed: Data
        var uncompressedSize: Int
        var modTime: UInt16
        var modDate: UInt16
        var versionMadeBy: UInt16 = 20
        var versionNeeded: UInt16 = 20
        var externalAttrs: UInt32 = 0

        static func stored(name: String, content: Data,
                           modTime: UInt16, modDate: UInt16) -> Member {
            Member(name: name, method: 0, crc: MiniZip.crc32(content),
                   compressed: content, uncompressedSize: content.count,
                   modTime: modTime, modDate: modDate)
        }
    }

    // MARK: reading

    static let eocdSignature: UInt32 = 0x0605_4B50
    static let centralSignature: UInt32 = 0x0201_4B50
    static let localSignature: UInt32 = 0x0403_4B50

    /// The central directory, in file order. nil = not a zip we understand
    /// (truncated, zip64, or a comment we cannot walk past).
    static func entries(in data: Data) -> [Entry]? {
        guard data.count > 22 else { return nil }
        // EOCD, scanned backwards over the 64 KB a comment may occupy
        var eocd = -1
        let floor = max(0, data.count - 22 - 0xFFFF)
        var i = data.count - 22
        while i >= floor {
            if u32(data, i) == eocdSignature { eocd = i; break }
            i -= 1
        }
        guard eocd >= 0 else { return nil }
        let count = Int(u16(data, eocd + 10))
        let size = Int(u32(data, eocd + 12))
        let offset = Int(u32(data, eocd + 16))
        guard count != 0xFFFF, offset != 0x7FFF_FFFF, offset >= 0,
              size >= 0, offset + size <= data.count else { return nil }

        var out: [Entry] = []
        var p = offset
        for _ in 0..<count {
            guard p + 46 <= data.count, u32(data, p) == centralSignature else { return nil }
            let nameLen = Int(u16(data, p + 28))
            let extraLen = Int(u16(data, p + 30))
            let commentLen = Int(u16(data, p + 32))
            guard p + 46 + nameLen <= data.count else { return nil }
            let name = String(decoding: data[(p + 46)..<(p + 46 + nameLen)], as: UTF8.self)
            out.append(Entry(name: name,
                             versionMadeBy: u16(data, p + 4),
                             versionNeeded: u16(data, p + 6),
                             flags: u16(data, p + 8),
                             method: u16(data, p + 10),
                             modTime: u16(data, p + 12),
                             modDate: u16(data, p + 14),
                             crc: u32(data, p + 16),
                             compressedSize: Int(u32(data, p + 20)),
                             uncompressedSize: Int(u32(data, p + 24)),
                             externalAttrs: u32(data, p + 38),
                             localHeaderOffset: Int(u32(data, p + 42))))
            p += 46 + nameLen + extraLen + commentLen
        }
        return out
    }

    /// The member's bytes exactly as they sit in the file — still compressed.
    static func compressedBytes(of entry: Entry, in data: Data) -> Data? {
        let p = entry.localHeaderOffset
        guard p + 30 <= data.count, u32(data, p) == localSignature else { return nil }
        let nameLen = Int(u16(data, p + 26))
        let extraLen = Int(u16(data, p + 28))
        let start = p + 30 + nameLen + extraLen
        let end = start + entry.compressedSize
        guard end <= data.count else { return nil }
        return data[start..<end]
    }

    /// The member's content. Stored or raw-DEFLATE only, which is every zip
    /// any Office application has ever written.
    static func read(_ entry: Entry, in data: Data) -> Data? {
        guard let raw = compressedBytes(of: entry, in: data) else { return nil }
        switch entry.method {
        case 0: return Data(raw)
        case 8: return inflate(Data(raw), expected: entry.uncompressedSize)
        default: return nil
        }
    }

    static func inflate(_ data: Data, expected: Int) -> Data? {
        guard expected > 0 else { return Data() }
        guard !data.isEmpty else { return nil }
        var out = Data(count: expected)
        let n: Int = out.withUnsafeMutableBytes { dst -> Int in
            data.withUnsafeBytes { src -> Int in
                guard let d = dst.bindMemory(to: UInt8.self).baseAddress,
                      let s = src.bindMemory(to: UInt8.self).baseAddress else { return 0 }
                // COMPRESSION_ZLIB is Apple's name for RAW deflate — exactly
                // what a zip member holds (no zlib header, no gzip wrapper).
                return compression_decode_buffer(d, expected, s, data.count, nil,
                                                 COMPRESSION_ZLIB)
            }
        }
        return n == expected ? out : nil
    }

    // MARK: writing

    static func write(_ members: [Member]) -> Data {
        var out = Data()
        var central = Data()
        for m in members {
            let offset = out.count
            out.append(le32(localSignature))
            out.append(le16(m.versionNeeded))
            out.append(le16(0))                       // no flags: never a data descriptor
            out.append(le16(m.method))
            out.append(le16(m.modTime))
            out.append(le16(m.modDate))
            out.append(le32(m.crc))
            out.append(le32(UInt32(m.compressed.count)))
            out.append(le32(UInt32(m.uncompressedSize)))
            let name = Data(m.name.utf8)
            out.append(le16(UInt16(name.count)))
            out.append(le16(0))                       // no extra field
            out.append(name)
            out.append(m.compressed)

            central.append(le32(centralSignature))
            central.append(le16(m.versionMadeBy))
            central.append(le16(m.versionNeeded))
            central.append(le16(0))
            central.append(le16(m.method))
            central.append(le16(m.modTime))
            central.append(le16(m.modDate))
            central.append(le32(m.crc))
            central.append(le32(UInt32(m.compressed.count)))
            central.append(le32(UInt32(m.uncompressedSize)))
            central.append(le16(UInt16(name.count)))
            central.append(le16(0))                   // extra
            central.append(le16(0))                   // comment
            central.append(le16(0))                   // disk number
            central.append(le16(0))                   // internal attrs
            central.append(le32(m.externalAttrs))
            central.append(le32(UInt32(offset)))
            central.append(name)
        }
        let centralOffset = out.count
        out.append(central)
        out.append(le32(eocdSignature))
        out.append(le16(0))
        out.append(le16(0))
        out.append(le16(UInt16(members.count)))
        out.append(le16(UInt16(members.count)))
        out.append(le32(UInt32(central.count)))
        out.append(le32(UInt32(centralOffset)))
        out.append(le16(0))                           // no archive comment
        return out
    }

    // MARK: bytes

    static func u16(_ d: Data, _ i: Int) -> UInt16 {
        guard i + 2 <= d.count else { return 0 }
        return UInt16(d[d.startIndex + i]) | UInt16(d[d.startIndex + i + 1]) << 8
    }

    static func u32(_ d: Data, _ i: Int) -> UInt32 {
        guard i + 4 <= d.count else { return 0 }
        let b = d.startIndex + i
        return UInt32(d[b]) | UInt32(d[b + 1]) << 8 | UInt32(d[b + 2]) << 16
            | UInt32(d[b + 3]) << 24
    }

    static func le16(_ v: UInt16) -> Data { Data([UInt8(v & 0xFF), UInt8(v >> 8 & 0xFF)]) }
    static func le32(_ v: UInt32) -> Data {
        Data([UInt8(v & 0xFF), UInt8(v >> 8 & 0xFF), UInt8(v >> 16 & 0xFF), UInt8(v >> 24 & 0xFF)])
    }

    static let crcTable: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    static func crc32(_ data: Data) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for b in data { c = crcTable[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
        return c ^ 0xFFFF_FFFF
    }
}

// MARK: - the Office package

enum OfficeScrub {
    static let coreXML = "docProps/core.xml"
    static let appXML = "docProps/app.xml"
    static let contentTypes = "[Content_Types].xml"
    static let packageRels = "_rels/.rels"

    /// Element name → the row's label. Anything not listed keeps its own
    /// lower-cased local name: the list is "exactly what was present" (§B5),
    /// so an unfamiliar field is still reported rather than quietly dropped.
    static let labels: [String: String] = [
        "creator": "author", "lastModifiedBy": "last saved by", "title": "title",
        "subject": "subject", "description": "description", "keywords": "keywords",
        "category": "category", "contentStatus": "status", "created": "created",
        "modified": "modified", "revision": "revision", "lastPrinted": "last printed",
        "language": "language", "identifier": "identifier", "version": "version",
        "Application": "application", "AppVersion": "app version",
        "Company": "company", "Manager": "manager", "Template": "template",
        "TotalTime": "editing time", "DocSecurity": "doc security",
        "Pages": "pages", "Words": "words", "Characters": "characters",
        "CharactersWithSpaces": "characters+", "Lines": "lines",
        "Paragraphs": "paragraphs", "Slides": "slides", "Notes": "notes",
        "HiddenSlides": "hidden slides", "MMClips": "media clips",
        "PresentationFormat": "format", "ScaleCrop": "scale crop",
        "LinksUpToDate": "links current", "SharedDoc": "shared",
        "HyperlinksChanged": "links changed",
    ]

    static func scrub(_ data: Data) -> (data: Data, report: MetadataScrubber.Report) {
        guard let entries = MiniZip.entries(in: data) else {
            return (data, .unsupported)          // not a zip: not an Office file
        }
        let doomed = entries.filter { $0.name == coreXML || $0.name == appXML }
        guard !doomed.isEmpty else {
            // a package with no docProps at all: supported, nothing to remove
            return (data, .clean)
        }

        var found: [(String, String)] = []
        for entry in doomed.sorted(by: { $0.name < $1.name }) {   // app.xml before core.xml
            guard let xml = MiniZip.read(entry, in: data) else { continue }
            found += XMLLeaves.values(in: xml).map { (labels[$0.name] ?? $0.name.lowercased(), $0.text) }
        }

        var members: [MiniZip.Member] = []
        for entry in entries {
            if entry.name == coreXML || entry.name == appXML { continue }
            if entry.name == contentTypes || entry.name == packageRels,
               let xml = MiniZip.read(entry, in: data) {
                // the parts are gone: the package must not still point at them
                let cleaned = removeReferences(in: xml)
                members.append(.stored(name: entry.name, content: cleaned,
                                       modTime: entry.modTime, modDate: entry.modDate))
                continue
            }
            guard let raw = MiniZip.compressedBytes(of: entry, in: data) else {
                return (data, .unsupported)      // cannot copy it through safely
            }
            members.append(MiniZip.Member(name: entry.name, method: entry.method,
                                          crc: entry.crc, compressed: Data(raw),
                                          uncompressedSize: entry.uncompressedSize,
                                          modTime: entry.modTime, modDate: entry.modDate,
                                          versionMadeBy: entry.versionMadeBy,
                                          versionNeeded: entry.versionNeeded,
                                          externalAttrs: entry.externalAttrs))
        }
        let out = MiniZip.write(members)
        return (out, MetadataScrubber.Report(removed: ScrubFields.dedupe(found),
                                             supported: true))
    }

    /// `<Override PartName="/docProps/core.xml" …/>` in `[Content_Types].xml`
    /// and `<Relationship … Target="docProps/app.xml" …/>` in `_rels/.rels`.
    static func removeReferences(in xml: Data) -> Data {
        var text = String(decoding: xml, as: UTF8.self)
        for pattern in ["<Override[^>]*docProps/(core|app)\\.xml[^>]*/>",
                        "<Relationship[^>]*docProps/(core|app)\\.xml[^>]*/>"] {
            guard let re = try? NSRegularExpression(pattern: pattern) else { continue }
            text = re.stringByReplacingMatches(
                in: text, range: NSRange(text.startIndex..., in: text), withTemplate: "")
        }
        return Data(text.utf8)
    }
}

// MARK: - leaf values out of a small XML part

/// Every leaf element with text, in document order. Used on `core.xml` and
/// `app.xml` only — parts measured in hundreds of bytes.
enum XMLLeaves {
    struct Value { var name: String; var text: String }

    static func values(in xml: Data) -> [Value] {
        let parser = XMLParser(data: xml)
        let sink = Sink()
        parser.delegate = sink
        parser.parse()
        return sink.out
    }

    private final class Sink: NSObject, XMLParserDelegate {
        var out: [Value] = []
        private var current: String?
        private var buffer = ""
        private var depth = 0

        func parser(_ parser: XMLParser, didStartElement name: String,
                    namespaceURI: String?, qualifiedName qName: String?,
                    attributes: [String: String] = [:]) {
            depth += 1
            // `vt:` vectors hold the document's own headings and part titles —
            // structure, not a field about a person
            guard depth > 1, !(qName ?? name).hasPrefix("vt:") else { current = nil; return }
            current = local(name)
            buffer = ""
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            buffer += string
        }

        func parser(_ parser: XMLParser, didEndElement name: String,
                    namespaceURI: String?, qualifiedName qName: String?) {
            depth -= 1
            defer { current = nil; buffer = "" }
            guard let current, current == local(name) else { return }
            let text = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return }
            out.append(Value(name: current, text: text))
        }

        private func local(_ name: String) -> String {
            name.contains(":") ? String(name.split(separator: ":").last ?? "") : name
        }
    }
}
