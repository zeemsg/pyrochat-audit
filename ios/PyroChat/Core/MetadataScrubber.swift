import Foundation

/// One hidden field that was taken out of an attachment before it was
/// encrypted. The label is lower-case and belongs in the sheet's 14-wide
/// column; the value is shown exactly as it was found (docs/SCRUB-AND-WIPE.md
/// §B2). Never any of the file's CONTENTS — only field names and values.
struct ScrubField: Codable, Equatable, Hashable {
    var label: String
    var value: String

    init(_ label: String, _ value: String) {
        self.label = label
        self.value = value
    }
}

/// Receipt rows, deduplicated. Pure, and shared by every format scrubber so
/// the sheet reads the same whatever the file was.
enum ScrubFields {
    /// One row per label. First value wins (the strongest source is scanned
    /// first), and the order is `order` where one is given, encounter order
    /// otherwise.
    static func dedupe(_ pairs: [(String, String)], order: [String] = []) -> [ScrubField] {
        var seen: [String: String] = [:]
        var encountered: [String] = []
        for (label, value) in pairs where seen[label] == nil {
            seen[label] = value
            encountered.append(label)
        }
        var out: [ScrubField] = []
        for label in order where seen[label] != nil {
            out.append(ScrubField(label, seen.removeValue(forKey: label)!))
        }
        for label in encountered {
            if let v = seen.removeValue(forKey: label) { out.append(ScrubField(label, v)) }
        }
        return out
    }
}

/// docs/SCRUB-AND-WIPE.md §B — strip document metadata on the sender's
/// device, before encryption, on every attachment.
///
/// The original file is NEVER modified: every entry point either takes bytes
/// and returns new bytes, or reads one URL and writes another.
///
/// Honesty is the whole point of the feature: a type we cannot clean is
/// reported as such and passed through byte-for-byte (`supported == false`),
/// and the removal list is exactly the set of fields that were there — nothing
/// invented, nothing missed.
enum MetadataScrubber {

    // MARK: what we are looking at

    enum Family: String, Equatable {
        case jpeg, png, heic, pdf, office, movie, unsupported
    }

    /// Extension first (it is what the user sees and what the receiver will
    /// open it as), MIME second. Never the bytes: a 5 MB unknown attachment
    /// must cost nothing.
    static func family(filename: String, mime: String? = nil) -> Family {
        switch (filename as NSString).pathExtension.lowercased() {
        case "jpg", "jpeg", "jpe": return .jpeg
        case "png": return .png
        case "heic", "heif": return .heic
        case "pdf": return .pdf
        case "docx", "xlsx", "pptx": return .office
        case "mp4", "m4v", "mov", "m4a": return .movie
        default: break
        }
        switch (mime ?? "").lowercased() {
        case "image/jpeg", "image/jpg": return .jpeg
        case "image/png": return .png
        case "image/heic", "image/heif": return .heic
        case "application/pdf": return .pdf
        case "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
             "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
             "application/vnd.openxmlformats-officedocument.presentationml.presentation":
            return .office
        case "video/mp4", "video/quicktime", "audio/mp4", "audio/x-m4a": return .movie
        default: return .unsupported
        }
    }

    // MARK: the receipt

    struct Report: Equatable {
        /// What was taken out, in a fixed order, deduplicated by label.
        var removed: [ScrubField] = []
        /// false = a type we cannot clean. §B2: "Never claim a file was
        /// cleaned when it was not."
        var supported: Bool = true

        var anything: Bool { !removed.isEmpty }

        /// A supported type that simply carried nothing.
        static let clean = Report(removed: [], supported: true)
        /// A type the scrubber has no business touching.
        static let unsupported = Report(removed: [], supported: false)
    }

    /// §B3, verbatim shape: `removed author, gps and 1 more from report.pdf`.
    /// nil when nothing was removed — there is then nothing to say, and the
    /// details sheet carries the honest line for unscrubbable types.
    static func preSendLine(removed: [ScrubField], filename: String) -> String? {
        guard !removed.isEmpty else { return nil }
        let labels = removed.map(\.label)
        let phrase: String
        switch labels.count {
        case 1: phrase = labels[0]
        case 2: phrase = "\(labels[0]) and \(labels[1])"
        default: phrase = "\(labels[0]), \(labels[1]) and \(labels.count - 2) more"
        }
        return "removed \(phrase) from \(filename)"
    }

    // MARK: in-memory entry point

    /// Scrub bytes. Returns the cleaned bytes (the ORIGINAL bytes, unchanged,
    /// whenever nothing could be or needed to be done) and the receipt.
    ///
    /// A movie is written to a scratch file and streamed — see `scrub(fileAt:)`
    /// — because loading a 50 MB video into memory is exactly what §B5 forbids.
    /// This entry point is used only by the attachment pipeline, which is
    /// capped at 12 MiB.
    static func scrub(_ data: Data, filename: String,
                      mime: String? = nil) async -> (data: Data, report: Report) {
        switch family(filename: filename, mime: mime) {
        case .jpeg:
            let found = ImageMetadata.fields(in: data)
            guard let out = JPEGScrub.stripPreservingOrientation(data) else { return (data, .unsupported) }
            return (out, Report(removed: found, supported: true))
        case .png:
            let found = ImageMetadata.fields(in: data)
            guard let out = PNGScrub.strip(data) else { return (data, .unsupported) }
            return (out, Report(removed: found, supported: true))
        case .heic:
            let found = ImageMetadata.fields(in: data)
            guard let out = HEICScrub.strip(data) else { return (data, .unsupported) }
            return (out, Report(removed: found, supported: true))
        case .pdf:
            return PDFScrub.scrub(data)
        case .office:
            return OfficeScrub.scrub(data)
        case .movie:
            let ext = (filename as NSString).pathExtension.lowercased()
            guard let src = try? scratchURL(ext: ext.isEmpty ? "mp4" : ext) else {
                return (data, .unsupported)
            }
            let dst = (try? scratchURL(ext: ext.isEmpty ? "mp4" : ext))
            defer {
                try? FileManager.default.removeItem(at: src.deletingLastPathComponent())
                if let dst {
                    try? FileManager.default.removeItem(at: dst.deletingLastPathComponent())
                }
            }
            guard let dst, (try? data.write(to: src, options: .atomic)) != nil else {
                return (data, .unsupported)
            }
            guard let report = try? await scrub(fileAt: src, to: dst, filename: filename),
                  let out = try? Data(contentsOf: dst) else {
                return (data, .unsupported)
            }
            return (out, report)
        case .unsupported:
            // §B1: "anything else — nothing, sent as-is, and the receipt says so"
            return (data, .unsupported)
        }
    }

    // MARK: streaming entry point

    enum ScrubError: Error { case unreadable, exportFailed(String) }

    /// Read `src`, write the cleaned file to `dst`. The movie path never holds
    /// the media in memory (§B5): AVFoundation streams it, sample by sample,
    /// through a pass-through export.
    ///
    /// `dst` must not exist. `src` is never modified.
    @discardableResult
    static func scrub(fileAt src: URL, to dst: URL,
                      filename: String? = nil, mime: String? = nil) async throws -> Report {
        let name = filename ?? src.lastPathComponent
        let fam = family(filename: name, mime: mime)
        if fam == .movie {
            return try await MovieScrub.stream(from: src, to: dst)
        }
        guard let data = try? Data(contentsOf: src, options: .mappedIfSafe) else {
            throw ScrubError.unreadable
        }
        let (out, report) = await scrub(data, filename: name, mime: mime)
        try out.write(to: dst, options: .atomic)
        return report
    }

    // MARK: scratch space

    /// `scrub-*` in tmp, swept at launch and taken by every wipe path
    /// (`SecureStore.sweepPlaintextTmp`) — staged plaintext must never outlive
    /// the identity it belonged to.
    static func scratchURL(ext: String) throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("scrub-\(UUID().uuidString.lowercased())",
                                    isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("f.\(ext.isEmpty ? "bin" : ext)")
    }
}
