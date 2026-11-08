import SwiftUI
import AVFoundation
import UIKit

/// Camera QR scanner for linking a desktop browser. Terminal chrome; the
/// simulator (no camera) gets a paste field driving the same parse path.
struct LinkDesktopView: View {
    @ObservedObject var service: ChatService
    @Environment(\.dismiss) private var dismiss

    @State private var scanError: String?
    @State private var linked = false
    @State private var cameraDenied = false
    @State private var pasted = ""

    private var settings: AppSettings { service.store.blob.settings }
    #if PRIVATE_SERVER_UI
    private var relayHost: String {
        let u = URL(string: settings.relayURL)
        let port = u?.port.map { ":\($0)" } ?? ""
        return (u?.host ?? "?") + port
    }
    #endif

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("── LINK DESKTOP ──").font(Theme.mono(15, weight: .bold))
            if linked, let m = service.mirror {
                linkedPane(m)
            } else {
                #if PRIVATE_SERVER_UI
                Text("open  http(s)://\(relayHost)/web  in a browser and scan the code it shows")
                    .font(Theme.mono(12)).foregroundStyle(Theme.fgDim(settings))
                #else
                Text("open  pyrochat.ca/link  in a browser and scan the code it shows")
                    .font(Theme.mono(12)).foregroundStyle(Theme.fgDim(settings))
                #endif
                #if targetEnvironment(simulator)
                pasteField
                #else
                if cameraDenied { deniedPane } else { scanner }
                #endif
            }
            if let scanError {
                Text("! \(scanError)").font(Theme.mono(12)).foregroundStyle(Theme.alert)
            }
            Spacer()
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .terminalScreen(settings)
        .navigationBarTitleDisplayMode(.inline)
    }

    private var scanner: some View {
        QRScanner(accept: { raw in handle(raw) },
                  onDenied: { cameraDenied = true })
        .frame(maxHeight: 380)
        .overlay(RoundedRectangle(cornerRadius: 2)
            .stroke(Theme.fg(settings).opacity(0.5), lineWidth: 1))
    }

    private var pasteField: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("simulator: paste the pyro-link code")
                .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
            TextField("pyro-link:v1:…", text: $pasted)
                .font(Theme.mono(11))
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .padding(8).background(Theme.panel)
            Button { handle(pasted) } label: {
                Text("[ LINK ]").font(Theme.mono(13, weight: .bold))
            }
        }
    }

    private var deniedPane: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("ERR: camera access denied")
                .font(Theme.mono(13)).foregroundStyle(Theme.alert)
            Text("settings \u{2192} privacy \u{2192} camera \u{2192} pyrochat")
                .font(Theme.mono(12)).foregroundStyle(Theme.fgDim(settings))
            Button {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            } label: {
                Text("[ OPEN SETTINGS ]").font(Theme.mono(13, weight: .bold))
            }
        }
    }

    private func linkedPane(_ m: MirrorEngine) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("LINKED \u{25AE}").font(Theme.mono(16, weight: .bold))
                .foregroundStyle(Theme.flame)
            if let b = m.browserName {
                Text("to  \(b)").font(Theme.mono(13, weight: .semibold))
            }
            Text("code  \(m.sas)")
                .font(Theme.mono(20, weight: .bold))
            Text("the same 6 digits must show in your browser — if they differ, someone hijacked the code: unlink immediately.")
                .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
            Text("burn-after messages never appear in the browser. lock or background this phone and the link drops.")
                .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
            HStack(spacing: 14) {
                Button { dismiss() } label: {
                    Text("[ DONE ]").font(Theme.mono(13, weight: .bold))
                }
                Button {
                    service.stopMirror()
                    linked = false
                } label: {
                    Text("[ UNLINK ]").font(Theme.mono(13, weight: .bold))
                        .foregroundStyle(Theme.alert)
                }
            }
        }
    }

    /// Returns true only when a link actually started (the scanner then
    /// stops). Every other outcome keeps the camera live.
    @discardableResult
    private func handle(_ raw: String) -> Bool {
        guard let relayURL = URL(string: settings.relayURL) else { return false }
        do {
            let link = try MirrorLink.parse(raw, expectedRelay: relayURL)
            Haptics.tap()
            _ = try service.startMirror(link)
            scanError = nil
            linked = true
            return true
        } catch MirrorLink.ParseError.notPyroLink {
            if raw.hasPrefix("pyro-add:") {
                scanError = "that's a CONTACT code — scan it from [qr] on the home screen"
            }
            // anything else: someone's lunch QR — keep scanning silently
        } catch MirrorLink.ParseError.wrongRelay(let expected, let got) {
            Haptics.warn()
            #if PRIVATE_SERVER_UI
            scanError = "QR targets relay \(got) — this phone is on \(expected). change relay in [cfg] or reload the page."
            #else
            _ = (expected, got)
            scanError = "this code belongs to a different server — open pyrochat.ca/link and scan that page instead."
            #endif
        } catch MirrorLink.ParseError.malformed {
            scanError = "unreadable link QR — reload the browser page"
        } catch {
            scanError = "link failed: \(error.localizedDescription)"
        }
        return false
    }
}

/// AVFoundation QR scanner wrapped for SwiftUI; stops the session on the
/// first successful string (debounce against repeated detections).
struct QRScanner: UIViewRepresentable {
    /// Called for every decoded code. Return TRUE to accept it — the camera
    /// then stops (one-shot). Return FALSE to keep scanning (wrong kind of
    /// code, malformed, someone's lunch menu). The host decides; the
    /// scanner never latches on a code the host rejected.
    let accept: (String) -> Bool
    let onDenied: () -> Void

    func makeUIView(context: Context) -> ScannerView {
        let v = ScannerView()
        v.accept = accept
        v.onDenied = onDenied
        v.startIfAuthorized()
        return v
    }

    func updateUIView(_ uiView: ScannerView, context: Context) {}

    static func dismantleUIView(_ uiView: ScannerView, coordinator: ()) {
        uiView.stop()
    }

    final class ScannerView: UIView, AVCaptureMetadataOutputObjectsDelegate {
        var accept: ((String) -> Bool)?
        var onDenied: (() -> Void)?
        private let session = AVCaptureSession()
        private let queue = DispatchQueue(label: "ca.pyrochat.qr")
        private var fired = false

        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

        func startIfAuthorized() {
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self else { return }
                    granted ? self.start() : self.onDenied?()
                }
            }
        }

        private func start() {
            guard let device = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: device) else { return }
            let output = AVCaptureMetadataOutput()
            session.beginConfiguration()
            if session.canAddInput(input) { session.addInput(input) }
            if session.canAddOutput(output) { session.addOutput(output) }
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
            session.commitConfiguration()
            (layer as? AVCaptureVideoPreviewLayer)?.session = session
            (layer as? AVCaptureVideoPreviewLayer)?.videoGravity = .resizeAspectFill
            queue.async { self.session.startRunning() }
        }

        func stop() {
            queue.async { self.session.stopRunning() }
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput,
                            didOutput objects: [AVMetadataObject],
                            from connection: AVCaptureConnection) {
            guard !fired,
                  let qr = objects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject })
                    .first(where: { $0.type == .qr }),
                  let value = qr.stringValue else { return }
            if accept?(value) == true {
                fired = true
                stop()
            }
        }
    }
}
