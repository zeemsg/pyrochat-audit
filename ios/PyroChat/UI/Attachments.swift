import SwiftUI
import UIKit
import UniformTypeIdentifiers
import PhotosUI
import QuickLook

/// Attachment admission control: video is refused outright.
/// NOTE (accepted consequence, per requirements): .audiovisualContent is the
/// parent of audio too, so standalone audio files (mp3/m4a) are also refused
/// — voice notes cover audio.
enum AttachmentGate {
    static let blockedTypes: [UTType] = [.movie, .video, .audiovisualContent]

    static func refusal(for type: UTType?) -> String? {
        guard let type else { return nil }   // unknown type ⇒ allow as generic file
        return blockedTypes.contains(where: { type.conforms(to: $0) })
            ? "ERR: video files are not supported" : nil
    }

    static func refusal(forContentTypes types: [UTType]) -> String? {
        types.compactMap { refusal(for: $0) }.first
    }

    static func refusal(forFileSize bytes: Int) -> String? {
        bytes > ChatService.maxFilePlaintextBytes
            ? "ERR: file exceeds 12 MiB limit" : nil
    }
}

enum ImageNormalizer {
    /// Decode (HEIC/PNG/JPEG/…), downscale so max(w,h) ≤ 2048, re-encode
    /// JPEG q0.8. PRIVACY FEATURE: re-rasterizing produces brand-new pixels —
    /// ALL EXIF/GPS/serial metadata from the source is stripped, and drawing
    /// bakes in orientation. HEIC is handled by the same re-encode.
    static func normalize(_ data: Data, maxDimension: CGFloat = 2048,
                          quality: CGFloat = 0.8) -> (jpeg: Data, width: Int, height: Int)? {
        guard let src = UIImage(data: data), src.size.width > 0, src.size.height > 0 else {
            return nil
        }
        let scale = min(1, maxDimension / max(src.size.width, src.size.height))
        let target = CGSize(width: (src.size.width * scale).rounded(.down),
                            height: (src.size.height * scale).rounded(.down))
        let fmt = UIGraphicsImageRendererFormat.default()
        fmt.scale = 1
        let out = UIGraphicsImageRenderer(size: target, format: fmt)
            .image { _ in src.draw(in: CGRect(origin: .zero, size: target)) }
        guard let jpeg = out.jpegData(compressionQuality: quality) else { return nil }
        return (jpeg, Int(target.width), Int(target.height))
    }
}

/// Terminal-style byte label: 312K, 2.4M.
func byteLabel(_ bytes: Int?) -> String {
    guard let bytes else { return "?" }
    if bytes < 1024 { return "\(bytes)B" }
    if bytes < 1024 * 1024 { return "\(bytes / 1024)K" }
    return String(format: "%.1fM", Double(bytes) / (1024 * 1024))
}

/// Full-screen image viewer: black ground, pinch zoom 1–5x, drag-to-pan when
/// zoomed, double-tap resets. Auto-dismisses if the message burns while open.
struct TerminalImageViewer: View {
    @ObservedObject var service: ChatService
    let message: ChatMessage
    let thread: String
    let onExport: () -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var zoom: CGFloat = 1
    @State private var steadyZoom: CGFloat = 1
    @State private var pan: CGSize = .zero
    @State private var steadyPan: CGSize = .zero
    /// Swipe-down-to-close: how far the unzoomed image has been dragged down.
    @State private var dismissDrag: CGFloat = 0
    static let dismissThreshold: CGFloat = 120

    private var settings: AppSettings { service.store.blob.settings }
    private var stillExists: Bool {
        service.messages[thread]?.contains { $0.id == message.id } == true
    }

    var body: some View {
        ZStack(alignment: .top) {
            // the ground fades as the picture is pulled down, so the swipe reads as "letting go"
            Color.black.opacity(1 - min(dismissDrag / 500, 0.6)).ignoresSafeArea()
            if let data = service.attachmentData(for: message),
               let img = UIImage(data: data) {
                Image(uiImage: img)
                    .resizable()
                    .scaledToFit()
                    .scaleEffect(zoom * (1 - min(dismissDrag / 2500, 0.15)))
                    .offset(x: pan.width, y: pan.height + dismissDrag)
                    .gesture(
                        MagnificationGesture()
                            .onChanged { zoom = min(max(steadyZoom * $0, 1), 5) }
                            .onEnded { _ in
                                steadyZoom = zoom
                                if zoom <= 1.01 { resetTransform() }
                            }
                            .simultaneously(with: DragGesture()
                                .onChanged { v in
                                    if zoom > 1 {
                                        pan = CGSize(width: steadyPan.width + v.translation.width,
                                                     height: steadyPan.height + v.translation.height)
                                    } else {
                                        // unzoomed: a downward pull follows the finger; upward does nothing
                                        dismissDrag = max(0, v.translation.height)
                                    }
                                }
                                .onEnded { v in
                                    if zoom > 1 { steadyPan = pan; return }
                                    let flung = v.predictedEndTranslation.height > Self.dismissThreshold * 2
                                    if dismissDrag > Self.dismissThreshold || flung {
                                        dismiss()
                                    } else {
                                        withAnimation(.spring(duration: 0.25)) { dismissDrag = 0 }
                                    }
                                }))
                    .onTapGesture(count: 2) { resetTransform() }
                    .accessibilityIdentifier("imageViewerImage")
                    .accessibilityAddTraits(.isImage)
            } else {
                Text("[IMG missing]")
                    .font(Theme.mono(13)).foregroundStyle(Theme.fgDim(settings))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            HStack {
                Button { dismiss() } label: {
                    Text("[CLOSE]").font(Theme.mono(13, weight: .bold))
                        .foregroundStyle(Theme.fg(settings))
                }
                Spacer()
                Button {
                    dismiss()
                    onExport()
                } label: {
                    Text("[EXPORT]").font(Theme.mono(13, weight: .bold))
                        .foregroundStyle(Theme.fgDim(settings))
                }
            }
            .padding(16)
        }
        .onChange(of: stillExists) { _, exists in
            if !exists { dismiss() }   // burned while open
        }
    }

    private func resetTransform() {
        withAnimation(.easeOut(duration: 0.15)) {
            zoom = 1; steadyZoom = 1; pan = .zero; steadyPan = .zero
        }
    }
}

/// QuickLook wrapper for file previews. The decrypted bytes are written to a
/// tmp preview-<msgId>/ directory just before presentation; the caller (and
/// the launch-time sweep + obliterate) delete it.
struct QuickLookPreview: UIViewControllerRepresentable {
    let fileURL: URL

    func makeUIViewController(context: Context) -> QLPreviewController {
        let vc = QLPreviewController()
        vc.dataSource = context.coordinator
        return vc
    }

    func updateUIViewController(_ vc: QLPreviewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(url: fileURL) }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController,
                               previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }
    }
}

/// The explicit export path: the system Files "save" picker, opened straight
/// on the folder chooser (On My iPhone, iCloud Drive, any file provider).
/// A copy of the staged file lands exactly where the user picks; the staged
/// original is removed by the caller afterwards. (The share sheet used to be
/// here — it is built for sending to apps, and "Save to Files" was one option
/// among many, so the file rarely ended up where the user wanted it.)
struct ExportPicker: UIViewControllerRepresentable {
    let url: URL
    /// Called once, with `true` when the file was saved and `false` on cancel.
    let onComplete: (Bool) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onComplete: onComplete) }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let vc = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
        vc.shouldShowFileExtensions = true
        vc.delegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ vc: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onComplete: (Bool) -> Void
        private var done = false
        init(onComplete: @escaping (Bool) -> Void) { self.onComplete = onComplete }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard !done else { return }; done = true; onComplete(!urls.isEmpty)
        }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            guard !done else { return }; done = true; onComplete(false)
        }
    }
}
