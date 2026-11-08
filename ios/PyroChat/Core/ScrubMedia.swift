import Foundation
import AVFoundation

/// docs/SCRUB-AND-WIPE.md §B1, the media row: "mp4 / mov / m4a — metadata
/// atoms: location, make, model, software, and the Apple `com.apple.*` keys".
///
/// §B5 forbids the obvious implementation: "Scrubbing a 50 MB video does not
/// load it entirely into memory." So this never sees the samples. A
/// pass-through export copies them from one file to the other, and the only
/// thing in memory at any moment is AVFoundation's own window over the track
/// — the metadata is read from the atoms and the output is written with none.
enum MovieScrub {

    /// The keys worth naming in the receipt, and the label each gets.
    static func label(for item: AVMetadataItem) -> String? {
        let key = (item.key as? String) ?? item.identifier?.rawValue ?? ""
        let lower = key.lowercased()
        if lower.contains("location") || lower.contains("iso6709") { return "gps" }
        if lower.contains("make") { return "make" }
        if lower.contains("model") { return "model" }
        if lower.contains("software") || lower.contains("encoder") { return "software" }
        if lower.contains("creationdate") || lower.contains("©day") { return "date" }
        if lower.contains("artist") || lower.contains("author") || lower.contains("©art") {
            return "author"
        }
        if lower.contains("title") || lower.contains("©nam") { return "title" }
        if lower.contains("description") || lower.contains("©des") { return "description" }
        if lower.contains("comment") || lower.contains("©cmt") { return "comment" }
        if lower.contains("copyright") || lower.contains("©cpy") { return "copyright" }
        // §B1 names them explicitly: every remaining `com.apple.*` key is
        // reported under its own last path component, never swallowed
        if lower.hasPrefix("com.apple.") {
            let tail = lower.split(separator: ".").last.map(String.init) ?? "apple"
            return tail.isEmpty ? "apple" : tail
        }
        return nil
    }

    static func value(of item: AVMetadataItem) async -> String {
        if let s = (try? await item.load(.stringValue))??
            .trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty { return s }
        if let n = (try? await item.load(.numberValue)) ?? nil { return n.stringValue }
        if let d = (try? await item.load(.dateValue)) ?? nil {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.dateFormat = "yyyy-MM-dd HH:mm:ss"
            return f.string(from: d)
        }
        if let data = (try? await item.load(.dataValue)) ?? nil,
           let s = String(data: data, encoding: .utf8) {
            return s.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return ""
    }

    /// Every metadata item the container carries, across the common, format
    /// and per-track lists — the union is what the export will drop.
    static func fields(of asset: AVAsset) async -> [ScrubField] {
        var items: [AVMetadataItem] = []
        if let all = try? await asset.load(.metadata) { items += all }
        if let common = try? await asset.load(.commonMetadata) { items += common }
        if let formats = try? await asset.load(.availableMetadataFormats) {
            for format in formats {
                if let more = try? await asset.loadMetadata(for: format) { items += more }
            }
        }
        var pairs: [(String, String)] = []
        for item in items {
            guard let label = label(for: item) else { continue }
            let value = await self.value(of: item)
            guard !value.isEmpty else { continue }
            pairs.append((label, value))
        }
        return ScrubFields.dedupe(pairs, order: ["author", "gps", "make", "model",
                                                 "software", "date", "title",
                                                 "description", "comment", "copyright"])
    }

    static func fileType(for url: URL) -> AVFileType {
        switch url.pathExtension.lowercased() {
        case "mov": return .mov
        case "m4a": return .m4a
        case "m4v": return .m4v
        default: return .mp4
        }
    }

    /// Read `src`, write a metadata-free copy to `dst`. Samples are passed
    /// through untouched — no decode, no re-encode, no whole-file buffer.
    ///
    /// The remux is done with a reader/writer pair rather than
    /// `AVAssetExportSession`, because an export session will not write NO
    /// metadata: an empty `metadata` array is treated as "no override", and
    /// its `forSharing` filter deliberately keeps make, model and software —
    /// three of the five things §B1 names. A writer we own starts with nothing
    /// and is given nothing.
    static func stream(from src: URL, to dst: URL) async throws -> MetadataScrubber.Report {
        let asset = AVURLAsset(url: src)
        let tracks = (try? await asset.load(.tracks)) ?? []
        guard !tracks.isEmpty else {
            // not something AVFoundation can open: pass it through honestly
            try? FileManager.default.removeItem(at: dst)
            try FileManager.default.copyItem(at: src, to: dst)
            return .unsupported
        }
        let found = await fields(of: asset)
        try await remux(asset: asset, tracks: tracks, to: dst)
        return MetadataScrubber.Report(removed: found, supported: true)
    }

    /// One compressed sample at a time, from every track, into a writer that
    /// was never told anything about the file it came from.
    static func remux(asset: AVAsset, tracks: [AVAssetTrack], to dst: URL) async throws {
        try? FileManager.default.removeItem(at: dst)
        let reader = try AVAssetReader(asset: asset)
        let writer = try AVAssetWriter(outputURL: dst, fileType: fileType(for: dst))
        writer.metadata = []
        writer.shouldOptimizeForNetworkUse = true

        var pairs: [(AVAssetReaderTrackOutput, AVAssetWriterInput)] = []
        for track in tracks {
            let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
            output.alwaysCopiesSampleData = false
            guard reader.canAdd(output) else { continue }
            let formats = (try? await track.load(.formatDescriptions)) ?? []
            let input = AVAssetWriterInput(mediaType: track.mediaType, outputSettings: nil,
                                           sourceFormatHint: formats.first)
            input.expectsMediaDataInRealTime = false
            // the one "metadata" a track carries that is not about a person:
            // which way up it plays
            if let transform = try? await track.load(.preferredTransform) {
                input.transform = transform
            }
            guard writer.canAdd(input) else { continue }
            reader.add(output)
            writer.add(input)
            pairs.append((output, input))
        }
        guard !pairs.isEmpty, writer.startWriting(), reader.startReading() else {
            throw MetadataScrubber.ScrubError.exportFailed(
                writer.error?.localizedDescription ?? reader.error?.localizedDescription
                ?? "remux could not start")
        }
        writer.startSession(atSourceTime: .zero)

        await withTaskGroup(of: Void.self) { group in
            for (index, pair) in pairs.enumerated() {
                group.addTask {
                    await pump(pair.0, into: pair.1,
                               on: DispatchQueue(label: "ca.pyrochat.scrub.\(index)"))
                }
            }
            await group.waitForAll()
        }
        guard reader.status != .failed else {
            writer.cancelWriting()
            throw MetadataScrubber.ScrubError.exportFailed(
                reader.error?.localizedDescription ?? "read failed")
        }
        await writer.finishWriting()
        guard writer.status == .completed else {
            throw MetadataScrubber.ScrubError.exportFailed(
                writer.error?.localizedDescription ?? "write failed")
        }
    }

    private static func pump(_ output: AVAssetReaderTrackOutput,
                             into input: AVAssetWriterInput,
                             on queue: DispatchQueue) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let done = Locked(false)
            input.requestMediaDataWhenReady(on: queue) {
                while input.isReadyForMoreMediaData {
                    guard let sample = output.copyNextSampleBuffer() else {
                        input.markAsFinished()
                        if done.exchange(true) == false { continuation.resume() }
                        return
                    }
                    if !input.append(sample) {
                        input.markAsFinished()
                        if done.exchange(true) == false { continuation.resume() }
                        return
                    }
                }
            }
        }
    }

    /// The smallest possible mutex: `requestMediaDataWhenReady` can re-enter,
    /// and a continuation may be resumed exactly once.
    private final class Locked: @unchecked Sendable {
        private var value: Bool
        private let lock = NSLock()
        init(_ value: Bool) { self.value = value }
        func exchange(_ new: Bool) -> Bool {
            lock.lock(); defer { lock.unlock() }
            let old = value
            value = new
            return old
        }
    }
}
