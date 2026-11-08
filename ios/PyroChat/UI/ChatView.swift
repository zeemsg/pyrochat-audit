import SwiftUI
import UIKit
import PhotosUI

/// One conversation, rendered as a terminal log with a `>` prompt composer.
struct ChatView: View {
    @ObservedObject var service: ChatService
    let contact: Contact

    @State private var draft = ""
    @State private var confirmBurn = false
    @State private var confirmBlock = false
    @State private var showSearch = false
    @State private var showVerify = false
    @State private var searchQuery = ""
    @State private var jumpTarget: String?
    @State private var deleteTarget: ChatMessage?
    @State private var deleteBothTarget: ChatMessage?
    /// docs/MESSAGE-DETAILS.md: the row whose `details` sheet is open.
    @State private var detailsTarget: ChatMessage?
    @StateObject private var recorder = VoiceRecorder()
    @StateObject private var player = VoicePlayer()
    @State private var composerFocused = false
    /// image pasted into the composer, waiting for [TX] (or [X] to discard)
    @State private var pendingPaste: PendingPaste?
    struct PendingPaste {
        let jpeg: Data; let width: Int; let height: Int
        /// docs/SCRUB-AND-WIPE.md §B1: what the re-encode threw away.
        var removed: [ScrubField] = []
    }
    /// docs/SCRUB-AND-WIPE.md §B3: a file, ALREADY scrubbed, staged under the
    /// compose bar with the line saying what came out of it. [TX] sends it.
    @State private var pendingFile: PendingFile?
    struct PendingFile {
        let data: Data; let name: String; let mime: String
        let report: MetadataScrubber.Report
    }
    /// The staged attachment's receipt, opened by tapping the line (§B3).
    @State private var showScrubList = false
    // attachments
    @State private var showAttachBar = false
    @State private var showPhotoPicker = false
    @State private var photoItem: PhotosPickerItem?
    @State private var showFileImporter = false
    @State private var attachError: String?
    @State private var viewerMessage: ChatMessage?
    @State private var previewMessage: ChatMessage?
    @State private var previewURL: URL?
    @State private var exportMessage: ChatMessage?
    @State private var shareURL: URL?

    private var settings: AppSettings { service.store.blob.settings }

    /// A voice note is being recorded. In a DEBUG build `--demo-recording` shows the same
    /// composer without opening the microphone, so the line of voices can be looked at.
    private var recording: Bool {
        #if DEBUG
        if ScreenshotDemo.recording { return true }
        #endif
        return recorder.isRecording
    }

    /// A note sent from here is still being changed into its voice: it has no row yet.
    private var changingVoice: Bool {
        #if DEBUG
        if ScreenshotDemo.changingVoice { return true }
        #endif
        return service.voiceBeingChanged[contact.hash] != nil
    }

    /// The passed-in Contact goes stale after MARK VERIFIED / key changes.
    private var liveContact: Contact {
        service.contacts.first { $0.hash == contact.hash } ?? contact
    }

    var body: some View {
        VStack(spacing: 0) {
            peerHeader
            logArea
            statusStrip
            if showAttachBar { attachBar }
            composer
        }
        .terminalScreen(settings)
        .navigationBarTitleDisplayMode(.inline)
        .navigationTitle("")
        .toolbarBackground(Theme.bg, for: .navigationBar)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { showSearch = true } label: {
                    Text("[/]").font(Theme.monoCapped(12, weight: .bold))
                        .foregroundStyle(Theme.fgDim(settings))
                }
                .accessibilityLabel("Search in conversation")
                Button { confirmBlock = true } label: {
                    Text("[block]").font(Theme.monoCapped(12, weight: .bold))
                        .foregroundStyle(Theme.fgDim(settings))
                }
                .accessibilityLabel("Block user")
                Button { Haptics.warn(); confirmBurn = true } label: {
                    Text("[burn]").font(Theme.monoCapped(12, weight: .bold))
                        .foregroundStyle(Theme.alert)
                }
                .accessibilityLabel("Burn thread")
                Menu {
                    Section("self-destruct for this chat") {
                        ForEach([("global default", TimeInterval?.none),
                                 ("off", TimeInterval?(0)),
                                 ("5s", TimeInterval?(5)), ("1m", TimeInterval?(60)),
                                 ("1h", TimeInterval?(3600)), ("1d", TimeInterval?(86400))],
                                id: \.0) { label, secs in
                            Button {
                                service.setBurnDefault(secs, for: contact.hash)
                            } label: {
                                let current = service.contacts
                                    .first { $0.hash == contact.hash }?.burnSeconds
                                if current == secs { Label(label, systemImage: "checkmark") }
                                else { Text(label) }
                            }
                        }
                    }
                } label: {
                    Text("[ttl]").font(Theme.monoCapped(12, weight: .bold))
                        .foregroundStyle(Theme.fgDim(settings))
                }
                .accessibilityLabel("Self-destruct timer for this conversation")
            }
        }
        .confirmationDialog("BLOCK <\(contact.name)>", isPresented: $confirmBlock,
                            titleVisibility: .visible) {
            Button("BLOCK") {
                service.blockContact(contact.hash)
            }
            Button("REPORT + BLOCK", role: .destructive) {
                Task { await service.reportAndBlock(contact) }
            }
            Button("ABORT", role: .cancel) {}
        } message: {
            Text("block: silently drops everything they send. report: also flags their address to the operator — no message content is shared (none exists to share) and your identity is not attached to the report. reporting burns this thread.")
        }
        .confirmationDialog("RM ON BOTH DEVICES", isPresented: Binding(
            get: { deleteBothTarget != nil },
            set: { if !$0 { deleteBothTarget = nil } }), titleVisibility: .visible) {
            Button("DELETE FOR BOTH", role: .destructive) {
                if let m = deleteBothTarget {
                    Haptics.warn()
                    Task {
                        if await !service.deleteForBoth(id: m.id, thread: contact.hash) {
                            flashError(service.lastError ?? "ERR: could not retract")
                        }
                    }
                }
                deleteBothTarget = nil
            }
            Button("ABORT", role: .cancel) { deleteBothTarget = nil }
        } message: {
            Text("retracts this message from <\(contact.name)>'s device too. needs the link up; nothing happens if it can't be delivered.")
        }
        .alert("RM MESSAGE", isPresented: Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } })) {
            Button("RM -F", role: .destructive) {
                if let m = deleteTarget {
                    service.deleteMessage(id: m.id, thread: contact.hash)
                }
                deleteTarget = nil
            }
            Button("ABORT", role: .cancel) { deleteTarget = nil }
        } message: {
            Text("removes this message from THIS device only. the other side keeps their copy.")
        }
        .alert("BURN THREAD", isPresented: $confirmBurn) {
            Button("BURN", role: .destructive) {
                Task { await service.burnThread(contact) }
            }
            Button("ABORT", role: .cancel) {}
        } message: {
            Text("wipes this conversation on BOTH devices. no undo.")
        }
        .onAppear {
            service.setActiveThread(contact.hash)
            if draft.isEmpty { draft = service.draft(for: contact.hash) }
            #if DEBUG
            // `--demo-staged-file`: docs/SCRUB-AND-WIPE.md §B3's line, staged,
            // so the compose bar can be captured with it
            if ScreenshotDemo.stagedScrub, pendingFile == nil {
                pendingFile = PendingFile(data: Data(count: 61_204), name: "report.pdf",
                                          mime: "application/pdf",
                                          report: ScreenshotDemo.scrubReport)
            }
            #endif
        }
        .onDisappear {
            service.setActiveThread(nil)
            // docs/DECOY-ACCOUNT.md §1, a flush point: the screen being left.
            service.flushDeferredWrites()
        }
        // §1, the other flush point the composer owns: losing focus.
        .onChange(of: composerFocused) { _, focused in
            if !focused { service.flushDeferredWrites() }
        }
        .onReceive(NotificationCenter.default.publisher(
            for: UIApplication.userDidTakeScreenshotNotification)) { _ in
            Task { await service.reportCapture("screenshot", in: contact) }
        }
        .onSceneCaptureStart {
            Task { await service.reportCapture("screen_record", in: contact) }
        }
        .sheet(isPresented: $showSearch) {
            searchSheet
        }
        .sheet(isPresented: $showVerify) {
            VerifyView(service: service, contactHash: contact.hash)
        }
        .sheet(item: $detailsTarget) { msg in
            MessageDetailsView(service: service, message: msg,
                               thread: contact.hash, peerName: liveContact.name)
        }
        // --- attachment pickers ---
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoItem, matching: .images)
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            Task {
                defer { photoItem = nil }
                if let err = AttachmentGate.refusal(forContentTypes: item.supportedContentTypes) {
                    flashError(err); return
                }
                guard let raw = try? await item.loadTransferable(type: Data.self),
                      let norm = ImageNormalizer.normalize(raw) else {
                    flashError("ERR: could not read image"); return
                }
                showAttachBar = false
                // §B3: staged, with the line, and it goes on [TX]. The
                // re-encode already stripped the original's fields — this
                // reads them off the ORIGINAL so the receipt can name them.
                pendingPaste = PendingPaste(jpeg: norm.jpeg, width: norm.width,
                                            height: norm.height,
                                            removed: ImageMetadata.fields(in: raw))
            }
        }
        .fileImporter(isPresented: $showFileImporter,
                      allowedContentTypes: [.item],
                      allowsMultipleSelection: false) { result in
            guard case .success(let urls) = result, let url = urls.first else { return }
            guard url.startAccessingSecurityScopedResource() else {
                flashError("ERR: file access denied"); return
            }
            defer { url.stopAccessingSecurityScopedResource() }
            let values = try? url.resourceValues(forKeys: [.contentTypeKey, .fileSizeKey])
            if let err = AttachmentGate.refusal(for: values?.contentType) {
                flashError(err); return
            }
            if let err = AttachmentGate.refusal(forFileSize: values?.fileSize ?? 0) {
                flashError(err); return
            }
            guard let data = try? Data(contentsOf: url), !data.isEmpty else {
                flashError("ERR: could not read file"); return
            }
            let name = sanitizeFilename(url.lastPathComponent)
            let mime = values?.contentType?.preferredMIMEType ?? "application/octet-stream"
            showAttachBar = false
            // docs/SCRUB-AND-WIPE.md §B: scrub the COPY we just read (the file
            // on disk is never touched), then stage it so §B3's line is on
            // screen before anything goes.
            Task {
                let (clean, report) = await MetadataScrubber.scrub(data, filename: name,
                                                                   mime: mime)
                pendingFile = PendingFile(data: clean, name: name, mime: mime,
                                          report: report)
            }
        }
        // --- viewers / export ---
        .fullScreenCover(item: $viewerMessage) { msg in
            TerminalImageViewer(service: service, message: msg, thread: contact.hash) {
                exportMessage = msg
            }
        }
        .sheet(item: $previewMessage, onDismiss: cleanupPreview) { msg in
            if let url = previewURL {
                QuickLookPreview(fileURL: url).ignoresSafeArea()
            }
        }
        .alert("EXPORT", isPresented: Binding(
            get: { exportMessage != nil },
            set: { if !$0 { exportMessage = nil } })) {
            Button("EXPORT", role: .destructive) {
                if let msg = exportMessage { performExport(msg) }
                exportMessage = nil
            }
            Button("ABORT", role: .cancel) { exportMessage = nil }
        } message: {
            Text("this decrypted copy LEAVES the encrypted store. anyone with device access can read it. no burn timer applies.")
        }
        .sheet(item: Binding(
            get: { shareURL.map { ShareItem(url: $0) } },
            set: { if $0 == nil { cleanupShare() } })) { item in
            ExportPicker(url: item.url) { saved in
                cleanupShare()
                if saved { flashError("\u{2713} exported") }
            }
        }
    }


    /// The peer's name on its own full-width line under the navigation bar.
    /// It used to be the bar's principal title, which iOS silently DROPS when the
    /// four trailing glyphs leave too little centred room — any name longer than
    /// about five characters vanished. A row of our own can never be squeezed out.
    private var peerHeader: some View {
        Button { showVerify = true } label: {
            HStack(spacing: 6) {
                Text("<\(liveContact.name)>")
                    .font(Theme.mono(14, weight: .semibold))
                    .foregroundStyle(Theme.flame)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if liveContact.verified {
                    Text("\u{2713}").font(Theme.mono(12, weight: .bold))
                        .foregroundStyle(Theme.fg(settings))
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Verify safety number")
        .accessibilityIdentifier("peerHeader")
    }

    private var logArea: some View {
        TerminalLogView(service: service, contact: contact, row: { msg in
            line(msg)
        }, footer: {
            rxProgressLines
        }, jumpTo: $jumpTarget)
    }

    @ViewBuilder
    private var statusStrip: some View {
        if service.typingPeers[contact.hash] != nil {
            Text("<\(contact.name)> is typing\u{2026}")
                .font(Theme.mono(11))
                .foregroundStyle(Theme.fgDim(settings))
                .padding(.horizontal, 12).padding(.bottom, 2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .transition(.opacity)
        }
        if let attachError {
            Text(attachError)
                .font(Theme.mono(12))
                .foregroundStyle(Theme.alert)
                .padding(.horizontal, 12).padding(.vertical, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private struct ShareItem: Identifiable {
        let url: URL
        var id: String { url.path }
    }

    // MARK: rows

    private func line(_ msg: ChatMessage) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Text("[\(Self.time.string(from: msg.timestamp))]")
                .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
                .fixedSize()
            SwiftUI.Group {
                switch msg.kind {
                case .system:
                    Text(msg.text).font(Theme.mono(12)).foregroundStyle(Theme.alert)
                case .voice:
                    voiceLine(msg)
                case .image:
                    imageLine(msg)
                case .file:
                    fileLine(msg)
                default:
                    (Text(msg.direction == .outgoing ? "<you> " : "<\(contact.name)> ")
                        .fontWeight(.semibold)
                        .foregroundColor(Theme.flame)
                     + (msg.viaWeb == true
                        ? Text("[web] ").font(Theme.mono(10))
                            .foregroundColor(Theme.fgDim(settings))
                        : Text(""))
                     + Text(msg.text))
                    .font(Theme.mono(13))
                }
            }
            .layoutPriority(1)
            // a gap the status/countdown column can never lose: at a large
            // text size the log line fills the width and would otherwise
            // run straight into it
            Spacer(minLength: 8)
            trailing(msg)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contextMenu {
            if msg.kind == .text {
                Button {
                    Pasteboard.copySensitive(msg.text)
                } label: { Label("copy", systemImage: "doc.on.doc") }
            }
            // docs/MESSAGE-DETAILS.md §1: every message, system and burn rows
            // and attachments included
            Button {
                detailsTarget = msg
            } label: { Label("details", systemImage: "info.circle") }
            Button(role: .destructive) {
                deleteTarget = msg
            } label: { Label("delete for me", systemImage: "trash") }
            if msg.direction == .outgoing {
                Button(role: .destructive) {
                    deleteBothTarget = msg
                } label: { Label("delete for both", systemImage: "trash.slash") }
            }
        }
    }

    /// Word for an outgoing status. Reciprocity: READ renders only when MY
    /// read-receipts toggle is on; otherwise it shows as DELIVERED (the
    /// upgrade is still recorded underneath).
    static func statusWord(_ s: ChatMessage.Status, showRead: Bool) -> String {
        // one implementation, shared with the `details` sheet
        MessageDetails.statusWord(s, showRead: showRead)
    }

    @ViewBuilder
    private func trailing(_ msg: ChatMessage) -> some View {
        if msg.direction == .outgoing {
            if let p = service.blobProgress[msg.id] {
                Text("TX \(Int(p * 100))%")
                    .font(Theme.mono(9)).foregroundStyle(Theme.fgDim(settings))
                    .fixedSize()
            } else if msg.status == .failed {
                Button {
                    Task {
                        if msg.kind == .text {
                            await service.retryText(messageId: msg.id, thread: contact.hash)
                        } else {
                            await service.retryBlob(messageId: msg.id, thread: contact.hash)
                        }
                    }
                } label: {
                    Text("FAILED [RETRY]")
                        .font(Theme.mono(9, weight: .bold)).foregroundStyle(Theme.alert)
                        .fixedSize()
                }
            } else {
                Text(Self.statusWord(msg.status, showRead: settings.readReceiptsOn))
                    .font(Theme.mono(9))
                    .foregroundStyle(msg.status == .failed ? Theme.alert : Theme.fgDim(settings))
                    .fixedSize()
            }
        }
        if let deadline = msg.burnDeadline {
            TimelineView(.periodic(from: .now, by: 1)) { ctx in
                let remaining = max(0, Int(deadline.timeIntervalSince(ctx.date)))
                Text("\u{1F525}\(remaining)")
                    .font(Theme.mono(10)).foregroundStyle(Theme.alert)
                    .fixedSize()                     // never wrap mid-number
                    .frame(minWidth: 34, alignment: .trailing)
            }
        } else if msg.burnAfter != nil {
            // armed but countdown not started yet (peer hasn't read it)
            Text("\u{1F525}\u{2026}")
                .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
                .frame(minWidth: 34, alignment: .trailing)
        }
    }

    private func voiceLine(_ msg: ChatMessage) -> some View {
        HStack(spacing: 6) {
            Text(msg.direction == .outgoing ? "<you>" : "<\(contact.name)>")
                .fontWeight(.semibold)
                .foregroundColor(Theme.flame)
                .font(Theme.mono(13))
            Button {
                if let data = service.voiceData(for: msg) { player.toggle(id: msg.id, data: data) }
            } label: {
                Text(player.playingId == msg.id ? "[\u{25A0}]" : "[\u{25B6}]")
                    .font(Theme.mono(13))
            }
            .disabled(service.voiceData(for: msg) == nil)
            .accessibilityLabel(player.playingId == msg.id ? "Stop voice note" : "Play voice note")
            if let data = service.voiceData(for: msg) {
                waveform(msg: msg, data: data)
            }
            Text("\(Int(msg.voiceDuration ?? 0))s")
                .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
        }
    }

    /// Real RMS waveform; drag to scrub while playing.
    private func waveform(msg: ChatMessage, data: Data) -> some View {
        let bars = Waveform.bars(for: msg.id, data: data)
        let playing = player.playingId == msg.id
        let played = playing ? player.progress : 0
        return HStack(alignment: .center, spacing: 1.5) {
            ForEach(bars.indices, id: \.self) { i in
                Capsule()
                    .fill(playing && Double(i) / Double(bars.count) <= played
                          ? Theme.flame : Theme.fgDim(settings))
                    .frame(width: 2, height: max(3, CGFloat(bars[i]) * 18))
            }
        }
        .frame(height: 20)
        .contentShape(Rectangle())
        .gesture(DragGesture(minimumDistance: 0).onEnded { v in
            guard playing else { return }
            let w = CGFloat(bars.count) * 3.5
            player.seek(id: msg.id, to: Double(max(0, min(v.location.x / w, 1))))
        })
        .accessibilityLabel("Voice note waveform")
    }

    // MARK: in-chat search

    private var searchSheet: some View {
        let matches = (service.messages[contact.hash] ?? []).filter {
            !searchQuery.trimmingCharacters(in: .whitespaces).isEmpty
            && $0.kind != .system
            && ($0.text.localizedCaseInsensitiveContains(searchQuery)
                || ($0.fileName?.localizedCaseInsensitiveContains(searchQuery) ?? false))
        }
        return VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                Text("grep:").font(Theme.mono(13)).foregroundStyle(Theme.fgDim(settings))
                TextField("", text: $searchQuery)
                    .font(Theme.mono(13))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button { showSearch = false; searchQuery = "" } label: {
                    Text("[X]").font(Theme.mono(13, weight: .bold))
                }
                .accessibilityLabel("Close search")
            }
            .padding(10).background(Theme.panel)
            if matches.isEmpty {
                Text(searchQuery.isEmpty ? "type to grep this channel"
                                         : "grep: no match.")
                    .font(Theme.mono(12)).foregroundStyle(Theme.fgDim(settings))
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                List(matches.reversed()) { m in
                    Button {
                        showSearch = false
                        searchQuery = ""
                        jumpTarget = m.id
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("[\(Self.time.string(from: m.timestamp))] \(m.direction == .outgoing ? "<you>" : "<\(contact.name)>")")
                                .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
                            Text(m.text).font(Theme.mono(12)).lineLimit(2)
                        }
                    }
                    .listRowBackground(Theme.bg)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
            Spacer(minLength: 0)
        }
        .terminalScreen(settings)
        .presentationDetents([.medium, .large])
    }

    private func imageLine(_ msg: ChatMessage) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            (Text(msg.direction == .outgoing ? "<you>" : "<\(contact.name)>")
                .foregroundColor(Theme.flame)
             + Text(" [IMG \(msg.imageWidth ?? 0)x\(msg.imageHeight ?? 0)]"))
                .font(Theme.mono(13)).fontWeight(.semibold)
            // fixed aspect box from metadata: thumbnail decode never changes
            // row height, so the scroll pin can't drift
            let w = CGFloat(msg.imageWidth ?? 4)
            let h = CGFloat(msg.imageHeight ?? 3)
            ZStack {
                if let data = service.attachmentData(for: msg),
                   let img = UIImage(data: data) {
                    Image(uiImage: img).resizable().scaledToFit()
                } else {
                    Text("[IMG missing]")
                        .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
                }
            }
            .aspectRatio(w / max(h, 1), contentMode: .fit)
            .frame(maxHeight: 220)
            .overlay(RoundedRectangle(cornerRadius: 2)
                .stroke(Theme.fg(settings).opacity(0.35), lineWidth: 1))
            .contentShape(Rectangle())
            .onTapGesture { viewerMessage = msg }
            .onLongPressGesture { exportMessage = msg }
        }
    }

    private func fileLine(_ msg: ChatMessage) -> some View {
        HStack(spacing: 6) {
            Button {
                openPreview(msg)
            } label: {
                (Text(msg.direction == .outgoing ? "<you>" : "<\(contact.name)>")
                    .foregroundColor(Theme.flame)
                 + Text(" [FILE] \(msg.fileName ?? "file") \u{00B7} \(byteLabel(msg.fileBytes))"))
                    .font(Theme.mono(13))
                    // a long name wraps; it stays left, like every other row
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            Button { exportMessage = msg } label: {
                Text("[EXPORT]").font(Theme.mono(10))
                    .foregroundStyle(Theme.fgDim(settings))
                    .fixedSize()
            }
        }
    }

    @ViewBuilder
    private var rxProgressLines: some View {
        ForEach(service.rxTransfers.filter { $0.value.thread == contact.hash }
            .sorted(by: { $0.key < $1.key }), id: \.key) { id, t in
            Text("[..] RX \(t.subtype) \(t.total > 0 ? Int(100 * t.received / t.total) : 0)%")
                .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
        }
    }

    // MARK: attachment plumbing

    private func flashError(_ text: String) {
        attachError = text
        Task {
            try? await Task.sleep(for: .seconds(4))
            if attachError == text { attachError = nil }
        }
    }

    private func openPreview(_ msg: ChatMessage) {
        guard let data = service.attachmentData(for: msg) else {
            flashError("ERR: file missing (burned?)"); return
        }
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("preview-\(msg.id)", isDirectory: true)
        let url = dir.appendingPathComponent(sanitizeFilename(msg.fileName ?? "file"))
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try data.write(to: url, options: [.atomic, .completeFileProtection])
            previewURL = url
            previewMessage = msg
        } catch {
            flashError("ERR: preview failed")
        }
    }

    private func cleanupPreview() {
        if let url = previewURL {
            try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
        }
        previewURL = nil
    }

    private func performExport(_ msg: ChatMessage) {
        guard let data = service.attachmentData(for: msg)
                ?? (msg.kind == .voice ? msg.voiceData : nil) else {
            flashError("ERR: content missing (burned?)"); return
        }
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("export-\(UUID().uuidString)", isDirectory: true)
        let name = msg.kind == .image ? "img-\(msg.id.prefix(8)).jpg"
            : sanitizeFilename(msg.fileName ?? "file")
        let url = dir.appendingPathComponent(name)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try data.write(to: url, options: [.atomic, .completeFileProtection])
            shareURL = url
        } catch {
            flashError("ERR: export failed")
        }
    }

    private func cleanupShare() {
        if let url = shareURL {
            try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
        }
        shareURL = nil
    }

    // MARK: composer

    private var attachBar: some View {
        HStack(spacing: 18) {
            Button {
                showPhotoPicker = true
            } label: {
                Text("[IMG] photos").font(Theme.mono(13))
            }
            Button {
                showFileImporter = true
            } label: {
                Text("[FILE] browse").font(Theme.mono(13))
            }
            Spacer()
            Button {
                showAttachBar = false
            } label: {
                Text("[X]").font(Theme.mono(13, weight: .bold))
                    .foregroundStyle(Theme.fgDim(settings))
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Theme.panel)
    }

    private func handlePastedImage(_ data: Data) {
        guard let norm = ImageNormalizer.normalize(data) else {
            flashError("ERR: could not read pasted image"); return
        }
        Haptics.tap()
        pendingPaste = PendingPaste(jpeg: norm.jpeg, width: norm.width,
                                    height: norm.height,
                                    removed: ImageMetadata.fields(in: data))
    }

    private var composer: some View {
      VStack(spacing: 0) {
        if let p = pendingPaste {
            HStack(spacing: 8) {
                if let img = UIImage(data: p.jpeg) {
                    Image(uiImage: img).resizable().scaledToFill()
                        .frame(width: 34, height: 34).clipped()
                        .overlay(Rectangle()
                            .stroke(Theme.fg(settings).opacity(0.4), lineWidth: 1))
                }
                Text("[IMG \(p.width)x\(p.height) \(byteLabel(p.jpeg.count))] ready")
                    .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
                Spacer()
                Button { pendingPaste = nil } label: {
                    Text("[X]").font(Theme.mono(12, weight: .bold))
                        .foregroundStyle(Theme.alert)
                }
                .accessibilityLabel("Discard pasted image")
            }
            .padding(.horizontal, 10).padding(.top, 8)
        }
        if let f = pendingFile {
            HStack(spacing: 8) {
                Text("[FILE \(f.name) \(byteLabel(f.data.count))] ready")
                    .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
                    .lineLimit(1).truncationMode(.middle)
                Spacer()
                Button { pendingFile = nil } label: {
                    Text("[X]").font(Theme.mono(12, weight: .bold))
                        .foregroundStyle(Theme.alert)
                }
                .accessibilityLabel("Discard staged file")
            }
            .padding(.horizontal, 10).padding(.top, 8)
        }
        if recording { VoicePicker(service: service) }
        if changingVoice { VoiceBeingChanged(settings: settings) }
        HStack(spacing: 8) {
            if recording {
                Text("\u{25CF} REC \(Int(recorder.elapsed))s")
                    .font(Theme.mono(14, weight: .bold))
                    .foregroundStyle(Theme.alert)
                Spacer()
                Button {
                    recorder.cancel()
                } label: {
                    Text("[X]").font(Theme.mono(13, weight: .bold))
                        .foregroundStyle(Theme.alert)
                }
                Button {
                    if let (data, duration) = recorder.finish() {
                        Task {
                            // in the voice chosen above; refused rather than sent as it was spoken
                            if await !service.sendVoice(data, duration: duration, to: contact) {
                                flashError(VoiceChanger.refusal)
                            }
                        }
                    }
                } label: {
                    Text("[SEND]").font(Theme.mono(13, weight: .bold))
                }
            } else {
                Text(">").font(Theme.mono(15, weight: .bold))
                ComposerField(text: $draft, focused: $composerFocused,
                              settings: settings) { data in
                    handlePastedImage(data)
                }
                .onChange(of: draft) { _, text in
                    service.setDraft(text, for: contact.hash)
                    if !text.isEmpty {
                        service.noteComposerActivity(in: contact.hash)
                    }
                }
                Button {
                    showAttachBar.toggle()
                } label: {
                    Text("[+]").font(Theme.mono(13, weight: .bold))
                }
                .accessibilityLabel("Attach image or file")
                Button {
                    Task { _ = await recorder.begin() }
                } label: {
                    Text("[\u{1F399}]").font(Theme.mono(13))
                }
                .accessibilityLabel("Record voice note")
                Button {
                    let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                    let paste = pendingPaste
                    let file = pendingFile
                    guard !text.isEmpty || paste != nil || file != nil else { return }
                    draft = ""
                    pendingPaste = nil
                    pendingFile = nil          // §B3: the line goes with it
                    service.setDraft("", for: contact.hash)
                    Haptics.tap()
                    Task {
                        if let p = paste {
                            await service.sendImage(jpeg: p.jpeg, width: p.width,
                                                    height: p.height, to: contact,
                                                    removed: p.removed)
                        }
                        if let f = file {
                            await service.sendFile(data: f.data, filename: f.name,
                                                   mime: f.mime, to: contact,
                                                   scrubbed: f.report)
                        }
                        if !text.isEmpty { await service.sendText(text, to: contact) }
                    }
                } label: {
                    Text("[TX]").font(Theme.mono(13, weight: .bold))
                }
                .accessibilityLabel("Send")
            }
        }
        .padding(10)
        scrubLine
      }
      .background(Theme.panel)
      .sheet(isPresented: $showScrubList) {
          ScrubReceiptView(settings: settings, filename: stagedName,
                           removed: stagedRemoved, supported: stagedSupported)
      }
    }

    // MARK: docs/SCRUB-AND-WIPE.md §B3 — one line, before it goes

    private var stagedRemoved: [ScrubField] {
        pendingFile?.report.removed ?? pendingPaste?.removed ?? []
    }
    private var stagedName: String {
        pendingFile?.name ?? "image.jpg"
    }
    private var stagedSupported: Bool {
        pendingFile?.report.supported ?? true
    }

    @ViewBuilder
    private var scrubLine: some View {
        if let line = MetadataScrubber.preSendLine(removed: stagedRemoved,
                                                   filename: stagedName) {
            Button { showScrubList = true } label: {
                Text(line)
                    .font(Theme.mono(11))
                    .foregroundStyle(Theme.fgDim(settings))
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 10)
                    .padding(.bottom, 8)
            }
            .accessibilityIdentifier("scrubLine")
        }
    }

    private static let time: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "HH:mm:ss"; return f
    }()
}
