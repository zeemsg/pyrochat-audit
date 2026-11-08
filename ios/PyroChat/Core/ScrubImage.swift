import Foundation
import ImageIO
import UniformTypeIdentifiers

// docs/SCRUB-AND-WIPE.md §B1, the image row:
// "JPEG / HEIC / PNG — already re-encoded today; extend to PNG text chunks
//  and HEIC EXIF".
//
// A photo picked from the library is still re-rasterized (ImageNormalizer),
// which is the strongest possible strip. These three scrubbers exist for the
// OTHER door: an image attached with [FILE] browse, which was never decoded,
// never re-encoded, and would otherwise go out with everything its camera put
// in it.
//
// JPEG and PNG are stripped by hand, at the container level: not one byte of
// image data is touched, so the picture that arrives is bit-for-bit the
// picture that was sent, minus the fields. HEIC has no hand-rollable
// container, so ImageIO re-muxes it.

// MARK: - what was in there

/// Reads the identifying metadata out of image bytes and turns it into the
/// receipt's rows. Only fields the scrubbers actually remove are reported
/// (§B5: "nothing invented, nothing missed") — never the width, the colour
/// model or the DPI, which are not metadata about a person.
enum ImageMetadata {

    /// The order rows appear in, and the dedupe order: one row per label,
    /// first value wins.
    static let order = ["author", "gps", "make", "model", "software", "lens",
                        "serial", "owner", "date", "copyright", "title",
                        "description", "comment", "location", "xmp"]

    static func fields(in data: Data) -> [ScrubField] {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil)
                as? [CFString: Any] else { return [] }
        var found: [(String, String)] = []

        func take(_ dict: [CFString: Any]?, _ key: CFString, _ label: String) {
            guard let raw = dict?[key] else { return }
            let value = text(raw)
            guard !value.isEmpty else { return }
            found.append((label, value))
        }

        let tiff = props[kCGImagePropertyTIFFDictionary] as? [CFString: Any]
        let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any]
        let aux = props[kCGImagePropertyExifAuxDictionary] as? [CFString: Any]
        let gps = props[kCGImagePropertyGPSDictionary] as? [CFString: Any]
        let iptc = props[kCGImagePropertyIPTCDictionary] as? [CFString: Any]
        let png = props[kCGImagePropertyPNGDictionary] as? [CFString: Any]

        take(tiff, kCGImagePropertyTIFFArtist, "author")
        take(iptc, kCGImagePropertyIPTCByline, "author")
        take(png, kCGImagePropertyPNGAuthor, "author")
        if let coords = gpsValue(gps) { found.append(("gps", coords)) }
        take(tiff, kCGImagePropertyTIFFMake, "make")
        take(tiff, kCGImagePropertyTIFFModel, "model")
        take(tiff, kCGImagePropertyTIFFSoftware, "software")
        take(png, kCGImagePropertyPNGSoftware, "software")
        take(exif, kCGImagePropertyExifLensModel, "lens")
        take(aux, kCGImagePropertyExifAuxLensModel, "lens")
        take(exif, kCGImagePropertyExifBodySerialNumber, "serial")
        take(aux, kCGImagePropertyExifAuxSerialNumber, "serial")
        take(exif, kCGImagePropertyExifCameraOwnerName, "owner")
        take(exif, kCGImagePropertyExifDateTimeOriginal, "date")
        take(tiff, kCGImagePropertyTIFFDateTime, "date")
        take(png, kCGImagePropertyPNGCreationTime, "date")
        take(tiff, kCGImagePropertyTIFFCopyright, "copyright")
        take(png, kCGImagePropertyPNGCopyright, "copyright")
        take(png, kCGImagePropertyPNGTitle, "title")
        take(png, kCGImagePropertyPNGDescription, "description")
        take(iptc, kCGImagePropertyIPTCCaptionAbstract, "description")
        take(png, kCGImagePropertyPNGComment, "comment")
        take(exif, kCGImagePropertyExifUserComment, "comment")
        take(iptc, kCGImagePropertyIPTCCity, "location")
        if let n = xmpByteCount(in: data) { found.append(("xmp", "\(n) bytes")) }

        return dedupe(found)
    }

    /// One row per label, in `order`, first value wins.
    static func dedupe(_ pairs: [(String, String)]) -> [ScrubField] {
        ScrubFields.dedupe(pairs, order: order)
    }

    /// `45.5017, -73.5673` — the spec's spelling (§B2).
    static func gpsValue(_ gps: [CFString: Any]?) -> String? {
        guard let gps,
              let lat = (gps[kCGImagePropertyGPSLatitude] as? NSNumber)?.doubleValue,
              let lon = (gps[kCGImagePropertyGPSLongitude] as? NSNumber)?.doubleValue
        else { return nil }
        let latRef = (gps[kCGImagePropertyGPSLatitudeRef] as? String ?? "N").uppercased()
        let lonRef = (gps[kCGImagePropertyGPSLongitudeRef] as? String ?? "E").uppercased()
        return coordinate(lat, negativeIf: latRef == "S")
            + ", " + coordinate(lon, negativeIf: lonRef == "W")
    }

    static func coordinate(_ v: Double, negativeIf negative: Bool) -> String {
        let signed = negative ? -abs(v) : v
        return String(format: "%.4f", signed)
    }

    /// An XMP packet is a fact about the file we can state without inventing a
    /// value for it: it was there, it was this big, and it is gone.
    static func xmpByteCount(in data: Data) -> Int? {
        guard let start = data.range(of: Data("<?xpacket begin".utf8)) else { return nil }
        guard let end = data.range(of: Data("<?xpacket end".utf8),
                                   in: start.upperBound..<data.endIndex) else { return nil }
        // through the closing `?>` of the end instruction
        let tail = data.range(of: Data("?>".utf8), in: end.upperBound..<data.endIndex)
        return (tail?.upperBound ?? end.upperBound) - start.lowerBound
    }

    static func text(_ raw: Any) -> String {
        if let s = raw as? String { return s.trimmingCharacters(in: .whitespacesAndNewlines) }
        if let n = raw as? NSNumber { return n.stringValue }
        if let d = raw as? Data { return String(data: d, encoding: .utf8) ?? "" }
        if let a = raw as? [Any] { return a.map { text($0) }.joined(separator: " ") }
        return ""
    }
}

// MARK: - JPEG

/// Marker-level strip. Everything from SOS onwards (the entropy-coded scan)
/// is copied verbatim, so the decoded picture is unchanged.
enum JPEGScrub {
    /// Segments that carry metadata and nothing the decoder needs:
    /// APP1 (EXIF / XMP), APP2 only when it is an MPF index (Apple depth
    /// pairs) — never an ICC profile, APP11 (JUMBF), APP13 (Photoshop/IPTC),
    /// and COM comments.
    static func strip(_ data: Data) -> Data? {
        let bytes = [UInt8](data)
        guard bytes.count > 4, bytes[0] == 0xFF, bytes[1] == 0xD8 else { return nil }
        var out = Data([0xFF, 0xD8])
        var i = 2
        while i + 3 < bytes.count {
            guard bytes[i] == 0xFF else { return nil }          // not a marker: malformed
            var marker = bytes[i + 1]
            var j = i + 1
            while marker == 0xFF, j + 1 < bytes.count {          // fill bytes
                j += 1
                marker = bytes[j]
            }
            if marker == 0xD9 {                                  // EOI
                out.append(contentsOf: [0xFF, 0xD9])
                return out
            }
            if marker == 0xDA {                                  // SOS: the rest is the scan
                out.append(data[i...])
                return out
            }
            guard j + 2 < bytes.count else { return nil }
            let length = Int(bytes[j + 1]) << 8 | Int(bytes[j + 2])
            guard length >= 2, j + 1 + length <= bytes.count else { return nil }
            let segment = data[i...(j + length)]                 // FF marker + payload
            let payload = length > 2 ? data[(j + 3)...(j + length)] : Data()
            if !drops(marker: marker, payload: payload) {
                out.append(segment)
            }
            i = j + 1 + length
        }
        return out
    }

    static func drops(marker: UInt8, payload: Data) -> Bool {
        switch marker {
        case 0xE1, 0xEB, 0xED, 0xFE: return true                  // APP1, APP11, APP13, COM
        case 0xE2: return payload.starts(with: Data("MPF\0".utf8))  // keep ICC_PROFILE
        default: return false
        }
    }

    /// A 34-byte APP1 carrying exactly one tag: Orientation. Re-inserted when
    /// the original EXIF said anything but "up", so stripping the metadata
    /// never rotates somebody's photo.
    static func orientationSegment(_ orientation: Int) -> Data {
        var d = Data([0xFF, 0xE1, 0x00, 0x22])                    // APP1, length 34
        d.append(Data("Exif\0\0".utf8))
        d.append(Data([0x49, 0x49, 0x2A, 0x00,                    // "II*\0" little-endian TIFF
                       0x08, 0x00, 0x00, 0x00,                    // IFD0 at offset 8
                       0x01, 0x00,                                // one entry
                       0x12, 0x01,                                // tag 0x0112 Orientation
                       0x03, 0x00,                                // type SHORT
                       0x01, 0x00, 0x00, 0x00,                    // count 1
                       UInt8(orientation & 0xFF), 0x00, 0x00, 0x00,
                       0x00, 0x00, 0x00, 0x00]))                  // no next IFD
        return d
    }

    /// The whole job: strip, then put the orientation back.
    static func stripPreservingOrientation(_ data: Data) -> Data? {
        guard var out = strip(data) else { return nil }
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let o = (props[kCGImagePropertyOrientation] as? NSNumber)?.intValue,
              o > 1, o <= 8 else { return out }
        // straight after SOI, where APP segments belong
        out.insert(contentsOf: orientationSegment(o), at: 2)
        return out
    }
}

// MARK: - PNG

/// Chunk-level strip: IHDR/PLTE/IDAT/IEND and the rendering chunks stay,
/// every text and metadata chunk goes.
enum PNGScrub {
    static let signature: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]
    /// §B1: "PNG text chunks". `eXIf` carries a whole EXIF block (GPS
    /// included) and `tIME` is a timestamp.
    static let dropped: Set<String> = ["tEXt", "zTXt", "iTXt", "eXIf", "tIME", "dSIG"]

    static func strip(_ data: Data) -> Data? {
        let bytes = [UInt8](data)
        guard bytes.count > 8, Array(bytes[0..<8]) == signature else { return nil }
        var out = Data(signature)
        var i = 8
        while i + 8 <= bytes.count {
            let length = Int(bytes[i]) << 24 | Int(bytes[i + 1]) << 16
                | Int(bytes[i + 2]) << 8 | Int(bytes[i + 3])
            guard length >= 0, i + 12 + length <= bytes.count else { return nil }
            let type = String(decoding: bytes[(i + 4)..<(i + 8)], as: UTF8.self)
            if !dropped.contains(type) {
                out.append(data[i..<(i + 12 + length)])
            }
            i += 12 + length
            if type == "IEND" { break }
        }
        return out
    }
}

// MARK: - HEIC

/// No hand-rollable container here, so ImageIO re-muxes the file with the
/// metadata dictionaries replaced by null. The encoded image payload is
/// copied, not re-compressed. If the platform refuses to write HEIC (some
/// simulators have no encoder) we return nil and the caller reports the file
/// as one we cannot clean — never as cleaned.
enum HEICScrub {
    static func strip(_ data: Data) -> Data? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let type = CGImageSourceGetType(src) else { return nil }
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, type, 1, nil) else { return nil }
        var removal: [CFString: Any] = [
            kCGImagePropertyExifDictionary: kCFNull as Any,
            kCGImagePropertyExifAuxDictionary: kCFNull as Any,
            kCGImagePropertyGPSDictionary: kCFNull as Any,
            kCGImagePropertyIPTCDictionary: kCFNull as Any,
            kCGImagePropertyTIFFDictionary: kCFNull as Any,
            kCGImagePropertyMakerAppleDictionary: kCFNull as Any,
            kCGImagePropertyPNGDictionary: kCFNull as Any,
        ]
        // the one thing in those dictionaries that is not metadata about a
        // person: which way up the picture goes
        if let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
           let o = props[kCGImagePropertyOrientation] {
            removal[kCGImagePropertyOrientation] = o
        }
        CGImageDestinationAddImageFromSource(dest, src, 0, removal as CFDictionary)
        guard CGImageDestinationFinalize(dest), out.length > 0 else { return nil }
        return out as Data
    }
}
