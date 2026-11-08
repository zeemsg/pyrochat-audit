import SwiftUI
import UIKit
import PhotosUI

// Group channels UI (docs/GROUPS.md §7): the `── new group ──` screen, the
// group chat (same terminal log as a 1:1 chat, coloured `<alice>` names,
// aggregated status words) and the `── members ──` sheet.

private func groupColor(_ rgb: GroupMath.RGB) -> Color {
    Color(red: rgb.r, green: rgb.g, blue: rgb.b)
}

// MARK: - new group

struct NewGroupView: View {
    @ObservedObject var service: ChatService
    /// Called with the created group; the caller navigates into it.
    let onCreated: (Group) -> Void

    @State private var name = ""
    @State private var picked: Set<String> = []
    @State private var policy: GroupPolicy = .burnRemove
    @State private var error: String?
    @State private var busy = false

    private var settings: AppSettings { service.store.blob.settings }
    private var candidates: [Contact] {
        service.contacts.filter { !service.isBlocked($0.hash) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }
    private var full: Bool { picked.count >= GroupMath.maxPicked }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 8) {
                    Text("name:").font(Theme.mono(13))
                    Text("#").font(Theme.mono(13, weight: .bold)).foregroundStyle(Theme.flame)
                    TextField("", text: $name)
                        .font(Theme.mono(13))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityLabel("Group name")
                }
                .padding(8).background(Theme.panel)

                Text("members: \(picked.count)/\(GroupMath.maxPicked) (you are the creator)")
                    .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
                if candidates.isEmpty {
                    Text("no contacts yet — open a channel with someone first.")
                        .font(Theme.mono(12)).foregroundStyle(Theme.fgDim(settings))
                }
                ForEach(candidates) { c in
                    let on = picked.contains(c.hash)
                    Button {
                        if on { picked.remove(c.hash) } else if !full { picked.insert(c.hash) }
                    } label: {
                        HStack(spacing: 8) {
                            Text(on ? "[x]" : "[ ]").font(Theme.mono(13, weight: .bold))
                                .foregroundStyle(on ? Theme.fg(settings) : Theme.fgDim(settings))
                            Text("<\(c.name)>").font(Theme.mono(13, weight: .semibold))
                                .foregroundStyle(Theme.flame)
                            if c.verified {
                                Text("\u{2713}").font(Theme.mono(12, weight: .bold))
                            }
                            Spacer()
                        }
                        .contentShape(Rectangle())
                    }
                    .disabled(!on && full)
                    .opacity(!on && full ? 0.4 : 1)
                    .accessibilityLabel(on ? "Remove \(c.name)" : "Add \(c.name)")
                }

                HStack {
                    Text("screenshot policy:").font(Theme.mono(13))
                    Spacer()
                    Picker(selection: $policy) {
                        ForEach(GroupPolicy.allCases, id: \.self) { p in
                            Text(p.label).font(Theme.mono(13)).tag(p)
                        }
                    } label: { EmptyView() }
                    .pickerStyle(.menu)
                    .tint(Theme.fg(settings))
                }
                .padding(8).background(Theme.panel)

                if let error {
                    Text(error).font(Theme.mono(12)).foregroundStyle(Theme.alert)
                }
                Button {
                    create()
                } label: {
                    Text("[ CREATE ]").font(Theme.mono(15, weight: .bold))
                        .frame(maxWidth: .infinity)
                }
                .disabled(busy || GroupMath.normalizeName(name) == nil)
                .accessibilityLabel("Create group")
            }
            .padding(16)
        }
        .terminalScreen(settings)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Theme.bg, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text("── new group ──").font(Theme.monoCapped(13)).foregroundStyle(Theme.fgDim(settings))
            }
        }
    }

    private func create() {
        busy = true
        error = nil
        let members = candidates.filter { picked.contains($0.hash) }
        Task {
            defer { busy = false }
            do {
                let g = try await service.createGroup(name: name, members: members, policy: policy)
                Haptics.tap()
                onCreated(g)
            } catch {
                self.error = "\(error)"
            }
        }
    }
}

// MARK: - group chat

struct GroupChatView: View {
    @ObservedObject var service: ChatService
    let groupID: String
    @Environment(\.dismiss) private var dismiss

    @State private var draft = ""
    @State private var showMembers = false
    @State private var showSearch = false
    @State private var searchQuery = ""
    @State private var jumpTarget: String?
    @State private var deleteTarget: ChatMessage?
    @State private var deleteAllTarget: ChatMessage?
    /// docs/MESSAGE-DETAILS.md: the row whose `details` sheet is open.
    @State private var detailsTarget: ChatMessage?
    @StateObject private var recorder = VoiceRecorder()
    @StateObject private var player = VoicePlayer()
    @State private var composerFocused = false
    @State private var pendingPaste: ChatView.PendingPaste?
    /// docs/SCRUB-AND-WIPE.md §B3 — the staged, already-scrubbed attachment.
    @State private var pendingFile: ChatView.PendingFile?
    @State private var showScrubList = false
    @State private var showAttachBar = false
    @State private var showPhotoPicker = false
    @State private var photoItem: PhotosPickerItem?
    @State private var showFileImporter = false
    @State private var flash: String?
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
        return service.voiceBeingChanged[groupID] != nil
    }

    private var group: Group? { service.group(id: groupID) }

    var body: some View {
        if let g = group {
            content(g)
        } else {
            // burned / removed while open: nothing remains to show
            Color.clear.onAppear { dismiss() }
        }
    }

    private func content(_ g: Group) -> some View {
        VStack(spacing: 0) {
            groupHeader(g)
            TerminalLogView(service: service, thread: g.id,
                            emptyHeader: "── encrypted group channel #\(g.name) ──",
                            row: { msg in line(msg, g) },
                            footer: { rxProgressLines },
                            jumpTo: $jumpTarget)
            statusStrip(g)
            if showAttachBar { attachBar }
            if g.isLeft { leftStrip } else { composer }
        }
        .terminalScreen(settings)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Theme.bg, for: .navigationBar)
        .navigationTitle("")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { showSearch = true } label: {
                    Text("[/]").font(Theme.monoCapped(12, weight: .bold))
                        .foregroundStyle(Theme.fgDim(settings))
                }
                .accessibilityLabel("Search in conversation")
                Button { showMembers = true } label: {
                    Text("[who]").font(Theme.monoCapped(12, weight: .bold))
                        .foregroundStyle(Theme.fgDim(settings))
                }
                .accessibilityLabel("Members")
            }
        }
        .sheet(isPresented: $showMembers) {
            GroupMembersView(service: service, groupID: groupID)
        }
        .sheet(isPresented: $showSearch) { searchSheet(g) }
        .sheet(item: $detailsTarget) { msg in
            MessageDetailsView(service: service, message: msg, thread: groupID,
                               peerName: msg.sender.flatMap { g.knownName($0) } ?? "peer")
        }
        .confirmationDialog("RM FOR ALL", isPresented: Binding(
            get: { deleteAllTarget != nil },
            set: { if !$0 { deleteAllTarget = nil } }), titleVisibility: .visible) {
            Button("DELETE FOR ALL", role: .destructive) {
                if let m = deleteAllTarget {
                    Haptics.warn()
                    Task {
                        if await !service.deleteForAll(id: m.id, groupID: groupID) {
                            flashError(service.lastError ?? "ERR: could not retract")
                        }
                    }
                }
                deleteAllTarget = nil
            }
            Button("ABORT", role: .cancel) { deleteAllTarget = nil }
        } message: {
            Text("retracts this message from every member's device. needs the link up; nothing happens if it can't be delivered.")
        }
        .alert("RM MESSAGE", isPresented: Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } })) {
            Button("RM -F", role: .destructive) {
                if let m = deleteTarget { service.deleteMessage(id: m.id, thread: groupID) }
                deleteTarget = nil
            }
            Button("ABORT", role: .cancel) { deleteTarget = nil }
        } message: {
            Text("removes this message from THIS device only. the other members keep their copies.")
        }
        .onAppear {
            service.setActiveThread(groupID)
            if draft.isEmpty { draft = service.draft(for: groupID) }
            #if DEBUG
            if ScreenshotDemo.screen == .groupMembers { showMembers = true }
            #endif
        }
        .onDisappear {
            service.setActiveThread(nil)
            service.flushDeferredWrites()               // §1: leaving the screen
        }
        .onChange(of: composerFocused) { _, focused in
            if !focused { service.flushDeferredWrites() }   // §1: losing focus
        }
        .onReceive(NotificationCenter.default.publisher(
            for: UIApplication.userDidTakeScreenshotNotification)) { _ in
            Task { await service.reportGroupCapture(in: groupID) }
        }
        // --- attachment pickers (same gates as a 1:1 chat) ---
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
                // §B3: staged, with the line, and it goes on [TX]
                pendingPaste = ChatView.PendingPaste(jpeg: norm.jpeg, width: norm.width,
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
            if let err = AttachmentGate.refusal(for: values?.contentType) { flashError(err); return }
            if let err = AttachmentGate.refusal(forFileSize: values?.fileSize ?? 0) { flashError(err); return }
            guard let data = try? Data(contentsOf: url), !data.isEmpty else {
                flashError("ERR: could not read file"); return
            }
            let name = sanitizeFilename(url.lastPathComponent)
            let mime = values?.contentType?.preferredMIMEType ?? "application/octet-stream"
            showAttachBar = false
            // docs/SCRUB-AND-WIPE.md §B: scrub the copy, then stage it
            Task {
                let (clean, report) = await MetadataScrubber.scrub(data, filename: name,
                                                                   mime: mime)
                pendingFile = ChatView.PendingFile(data: clean, name: name, mime: mime,
                                                   report: report)
            }
        }
        // --- viewers / export ---
        .fullScreenCover(item: $viewerMessage) { msg in
            TerminalImageViewer(service: service, message: msg, thread: groupID) {
                exportMessage = msg
            }
        }
        .sheet(item: $previewMessage, onDismiss: cleanupPreview) { _ in
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

    private struct ShareItem: Identifiable {
        let url: URL
        var id: String { url.path }
    }

    // MARK: strips

    @ViewBuilder
    private func statusStrip(_ g: Group) -> some View {
        if let line = GroupMath.typingLine(service.typingNames(in: g)) {
            Text(line)
                .font(Theme.mono(11))
                .foregroundStyle(Theme.fgDim(settings))
                .padding(.horizontal, 12).padding(.bottom, 2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .transition(.opacity)
        }
        if let flash {
            Text(flash)
                .font(Theme.mono(12))
                .foregroundStyle(Theme.alert)
                .padding(.horizontal, 12).padding(.vertical, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }


    /// `#name · N` on its own row under the navigation bar (see ChatView.peerHeader:
    /// a principal toolbar title is dropped by iOS when the trailing glyphs crowd it).
    private func groupHeader(_ g: Group) -> some View {
        Button { showMembers = true } label: {
            HStack(spacing: 6) {
                Text("#\(g.name)").font(Theme.mono(14, weight: .bold))
                    .foregroundStyle(Theme.flame)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text("\u{00B7} \(g.members.count)").font(Theme.mono(12))
                    .foregroundStyle(Theme.fgDim(settings))
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Members")
        .accessibilityIdentifier("groupHeader")
    }

    private var leftStrip: some View {
        Text("ERR: you are no longer in this group")
            .font(Theme.mono(12))
            .foregroundStyle(Theme.alert)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(Theme.panel)
    }

    // MARK: rows

    /// `<you>` in flame; `<alice>` in her join-order colour; a sender who was
    /// never on the roster reads `<name>?`, dim (§2).
    private func nameText(_ msg: ChatMessage, _ g: Group, trailing: String = " ") -> Text {
        if msg.direction == .outgoing {
            return Text("<you>" + trailing).fontWeight(.semibold).foregroundColor(Theme.flame)
        }
        let hash = msg.sender ?? ""
        let name = service.displayName(for: hash, in: g)
        if let rgb = GroupMath.colour(of: hash, in: g) {
            return Text("<\(name)>" + trailing).fontWeight(.semibold).foregroundColor(groupColor(rgb))
        }
        return Text("<\(name)>?" + trailing).fontWeight(.semibold).foregroundColor(Theme.fgDim(settings))
    }

    private func line(_ msg: ChatMessage, _ g: Group) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Text("[\(Self.time.string(from: msg.timestamp))]")
                .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
                .fixedSize()
            SwiftUI.Group {
                switch msg.kind {
                case .system:
                    Text(msg.text).font(Theme.mono(12)).foregroundStyle(Theme.alert)
                case .voice:
                    voiceLine(msg, g)
                case .image:
                    imageLine(msg, g)
                case .file:
                    fileLine(msg, g)
                default:
                    (nameText(msg, g)
                     + (msg.viaWeb == true
                        ? Text("[web] ").font(Theme.mono(10)).foregroundColor(Theme.fgDim(settings))
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
            trailing(msg, g)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contextMenu {
            if msg.kind == .text {
                Button { Pasteboard.copySensitive(msg.text) } label: {
                    Label("copy", systemImage: "doc.on.doc")
                }
            }
            // docs/MESSAGE-DETAILS.md §1: every message, system and burn rows
            // and attachments included
            Button { detailsTarget = msg } label: {
                Label("details", systemImage: "info.circle")
            }
            Button(role: .destructive) { deleteTarget = msg } label: {
                Label("delete for me", systemImage: "trash")
            }
            if msg.direction == .outgoing {
                Button(role: .destructive) { deleteAllTarget = msg } label: {
                    Label("delete for all", systemImage: "trash.slash")
                }
            }
        }
    }

    @ViewBuilder
    private func trailing(_ msg: ChatMessage, _ g: Group) -> some View {
        if msg.direction == .outgoing {
            if let p = service.blobProgress[msg.id] {
                Text("TX \(Int(p * 100))%")
                    .font(Theme.mono(9)).foregroundStyle(Theme.fgDim(settings))
                    .fixedSize()
            } else if msg.status == .failed {
                Button {
                    Task { await service.retryGroupMessage(messageId: msg.id, groupID: groupID) }
                } label: {
                    Text("FAILED [RETRY]")
                        .font(Theme.mono(9, weight: .bold)).foregroundStyle(Theme.alert)
                        .fixedSize()
                }
            } else {
                Text(GroupMath.statusWord(status: msg.status, receipts: msg.receipts ?? [:],
                                          recipients: service.recipients(of: g).count,
                                          showRead: settings.readReceiptsOn))
                    .font(Theme.mono(9))
                    .foregroundStyle(Theme.fgDim(settings))
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
            Text("\u{1F525}\u{2026}")
                .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
                .frame(minWidth: 34, alignment: .trailing)
        }
    }

    private func voiceLine(_ msg: ChatMessage, _ g: Group) -> some View {
        HStack(spacing: 6) {
            nameText(msg, g, trailing: "").font(Theme.mono(13))
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

    private func imageLine(_ msg: ChatMessage, _ g: Group) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            (nameText(msg, g, trailing: "")
             + Text(" [IMG \(msg.imageWidth ?? 0)x\(msg.imageHeight ?? 0)]"))
                .font(Theme.mono(13)).fontWeight(.semibold)
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

    private func fileLine(_ msg: ChatMessage, _ g: Group) -> some View {
        HStack(spacing: 6) {
            Button {
                openPreview(msg)
            } label: {
                (nameText(msg, g, trailing: "")
                 + Text(" [FILE] \(msg.fileName ?? "file") \u{00B7} \(byteLabel(msg.fileBytes))"))
                    .font(Theme.mono(13))
            }
            Button { exportMessage = msg } label: {
                Text("[EXPORT]").font(Theme.mono(10))
                    .foregroundStyle(Theme.fgDim(settings))
            }
        }
    }

    @ViewBuilder
    private var rxProgressLines: some View {
        ForEach(service.rxTransfers.filter { $0.value.thread == groupID }
            .sorted(by: { $0.key < $1.key }), id: \.key) { _, t in
            Text("[..] RX \(t.subtype) \(t.total > 0 ? Int(100 * t.received / t.total) : 0)%")
                .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
        }
    }

    // MARK: search

    private func searchSheet(_ g: Group) -> some View {
        let matches = (service.messages[groupID] ?? []).filter {
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
                Text(searchQuery.isEmpty ? "type to grep this channel" : "grep: no match.")
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
                            let who = m.direction == .outgoing ? "<you>"
                                : "<\(service.displayName(for: m.sender ?? "", in: g))>"
                            Text("[\(Self.time.string(from: m.timestamp))] \(who)")
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

    // MARK: attachment plumbing (mirrors ChatView)

    private func flashError(_ text: String) {
        flash = text
        Task {
            try? await Task.sleep(for: .seconds(4))
            if flash == text { flash = nil }
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
            Button { showPhotoPicker = true } label: {
                Text("[IMG] photos").font(Theme.mono(13))
            }
            Button { showFileImporter = true } label: {
                Text("[FILE] browse").font(Theme.mono(13))
            }
            Spacer()
            Button { showAttachBar = false } label: {
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
        pendingPaste = ChatView.PendingPaste(jpeg: norm.jpeg, width: norm.width,
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
                Button { recorder.cancel() } label: {
                    Text("[X]").font(Theme.mono(13, weight: .bold))
                        .foregroundStyle(Theme.alert)
                }
                Button {
                    if let (data, duration) = recorder.finish() {
                        Task {
                            // in the voice chosen above; refused rather than sent as it was spoken
                            if await !service.sendVoice(data, duration: duration, toGroup: groupID) {
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
                    service.setDraft(text, for: groupID)
                    if !text.isEmpty { service.noteComposerActivity(in: groupID) }
                }
                Button { showAttachBar.toggle() } label: {
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
                    service.setDraft("", for: groupID)
                    Haptics.tap()
                    Task {
                        if let p = paste {
                            await service.sendImage(jpeg: p.jpeg, width: p.width,
                                                    height: p.height, toGroup: groupID,
                                                    removed: p.removed)
                        }
                        if let f = file {
                            await service.sendFile(data: f.data, filename: f.name,
                                                   mime: f.mime, toGroup: groupID,
                                                   scrubbed: f.report)
                        }
                        if !text.isEmpty { await service.sendText(text, toGroup: groupID) }
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
    private var stagedName: String { pendingFile?.name ?? "image.jpg" }
    private var stagedSupported: Bool { pendingFile?.report.supported ?? true }

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

// MARK: - members sheet

struct GroupMembersView: View {
    @ObservedObject var service: ChatService
    let groupID: String
    @Environment(\.dismiss) private var dismiss

    @State private var showAdd = false
    @State private var showRename = false
    @State private var renameText = ""
    @State private var confirmBurn = false
    @State private var confirmLeave = false
    @State private var removeTarget: Member?
    @State private var verifyTarget: Member?
    @State private var error: String?
    @State private var busy = false

    private var settings: AppSettings { service.store.blob.settings }

    var body: some View {
        if let g = service.group(id: groupID) {
            content(g)
        } else {
            Color.clear.onAppear { dismiss() }
        }
    }

    private func content(_ g: Group) -> some View {
        let isCreator = g.isCreator(service.myHash) && !g.isLeft
        let ordered = GroupMath.joinOrder(members: g.members, former: g.former)
            .compactMap { g.member($0) }
        let lastOwn = (service.messages[g.id] ?? []).last { $0.direction == .outgoing && $0.kind != .system }
        return ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("── members ──").font(Theme.mono(13)).foregroundStyle(Theme.fgDim(settings))
                    Spacer()
                    Text("#\(g.name)").font(Theme.mono(13, weight: .bold)).foregroundStyle(Theme.flame)
                    Button { dismiss() } label: {
                        Text("[X]").font(Theme.mono(13, weight: .bold))
                            .foregroundStyle(Theme.fgDim(settings))
                    }
                    .accessibilityLabel("Close")
                }
                ForEach(ordered, id: \.hash) { m in
                    memberRow(m, g, isCreator: isCreator, lastOwn: lastOwn)
                }
                if g.isLeft {
                    Text("ERR: you are no longer in this group")
                        .font(Theme.mono(12)).foregroundStyle(Theme.alert)
                }
                if let error {
                    Text(error).font(Theme.mono(12)).foregroundStyle(Theme.alert)
                }
                if !g.isLeft {
                    // docs/TEXT-SIZE.md §3: once the pair no longer fits on one
                    // line the SECOND label drops to its own line whole, rather
                    // than splitting its bracket across two.
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 16) { addMemberButton; leaveGroupButton }
                        VStack(alignment: .leading, spacing: 10) {
                            addMemberButton; leaveGroupButton
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 4)
                }
                if isCreator {
                    creatorControls(g)
                } else {
                    HStack {
                        Text("screenshot policy").font(Theme.mono(12))
                        Spacer()
                        Text(g.policy.label).font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
                    }
                    .padding(.top, 8)
                    HStack {
                        Text("burn default").font(Theme.mono(12))
                        Spacer()
                        Text(Self.burnLabel(g.burnSeconds)).font(Theme.mono(11))
                            .foregroundStyle(Theme.fgDim(settings))
                    }
                }
                Text("every message in this channel is encrypted separately for each member. the relay sees \(max(g.members.count - 1, 0)) envelopes per line and nothing else.")
                    .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
                    .padding(.top, 8)
            }
            .padding(16)
        }
        .terminalScreen(settings)
        .presentationDetents([.large])
        .sheet(isPresented: $showAdd) {
            AddMemberView(service: service, groupID: groupID)
        }
        .sheet(item: $verifyTarget) { m in
            VerifyView(service: service, contactHash: m.hash, displayName: m.name)
        }
        .alert("RENAME #\(g.name)", isPresented: $showRename) {
            TextField("name", text: $renameText)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("RENAME") {
                run { try await service.renameGroup(groupID, to: renameText) }
            }
            Button("ABORT", role: .cancel) {}
        } message: {
            Text("1–32 characters. every member sees the new name.")
        }
        .alert("BURN #\(g.name)", isPresented: $confirmBurn) {
            Button("[ BURN ]", role: .destructive) {
                Haptics.warn()
                run {
                    try await service.burnGroup(groupID)
                    dismiss()
                }
            }
            Button("[ ABORT ]", role: .cancel) {}
        } message: {
            Text("destroys this channel on every member's phone: every message, every file, the member list. there is no undo.")
        }
        .confirmationDialog("LEAVE #\(g.name)", isPresented: $confirmLeave, titleVisibility: .visible) {
            Button("LEAVE", role: .destructive) {
                run {
                    try await service.leaveGroup(groupID)
                    dismiss()
                }
            }
            Button("ABORT", role: .cancel) {}
        } message: {
            Text("the channel stays on this phone, read-only. the other members are told you left.")
        }
        .alert("RM MEMBER", isPresented: Binding(
            get: { removeTarget != nil },
            set: { if !$0 { removeTarget = nil } })) {
            Button("REMOVE", role: .destructive) {
                if let m = removeTarget {
                    run { try await service.removeMember(m.hash, from: groupID) }
                }
                removeTarget = nil
            }
            Button("ABORT", role: .cancel) { removeTarget = nil }
        } message: {
            Text("removes <\(removeTarget?.name ?? "")> from #\(g.name) on every member's phone. their phone keeps a read-only copy.")
        }
    }

    private var addMemberButton: some View {
        Button { error = nil; showAdd = true } label: {
            Text("[ add member ]").font(Theme.mono(13, weight: .bold)).fixedSize()
        }
        .disabled(busy)
    }

    private var leaveGroupButton: some View {
        Button { confirmLeave = true } label: {
            Text("[ leave group ]").font(Theme.mono(13, weight: .bold))
                .foregroundStyle(Theme.fgDim(settings))
                .fixedSize()
        }
        .disabled(busy)
    }

    private func memberRow(_ m: Member, _ g: Group, isCreator: Bool, lastOwn: ChatMessage?) -> some View {
        let me = m.hash == service.myHash
        let contact = service.contacts.first { $0.hash == m.hash }
        let colour = GroupMath.colour(of: m.hash, in: g).map(groupColor) ?? Theme.fgDim(settings)
        // docs/TEXT-SIZE.md §3 — two lines per member, as on Android. Line 1
        // is who they are and what you can do about them; `joined HH:MM` and
        // the receipt drop to a dim line underneath. That frees the width that
        // was forcing a one-line row to elide `<alice>` down to `<a…`, so the
        // names — the actual content of this sheet — scale as ordinary type
        // with everything else and elide only when a name is genuinely long.
        //
        // Every bracket-or-tag label is `.fixedSize()`: `[remove]` split over
        // two lines, leaving a bare `]` on its own, reads as a rendering bug in
        // a terminal strip. The NAME is the one flexible element.
        let receipt = me ? nil : lastOwn?.receipts?[m.hash]
        return VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 8) {
                Text("<\(me ? "you" : m.name)>")
                    .font(Theme.mono(13, weight: .semibold))
                    .foregroundStyle(me ? Theme.flame : colour)
                    .lineLimit(1)
                    .truncationMode(.tail)
                if g.isCreator(m.hash) {
                    Text("creator").font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
                        .fixedSize()
                }
                if m.isGone {
                    Text("gone").font(Theme.mono(10)).foregroundStyle(Theme.alert)
                        .fixedSize()
                } else if !me {
                    if contact?.verified == true {
                        Text("verified \u{2713}").font(Theme.mono(10)).foregroundStyle(Theme.fg(settings))
                            .fixedSize()
                    } else {
                        Button { verifyTarget = m } label: {
                            Text("[safety #]").font(Theme.mono(10))
                                .foregroundStyle(Theme.fgDim(settings))
                                .fixedSize()
                        }
                        .accessibilityLabel("Safety number of \(m.name)")
                    }
                }
                Spacer(minLength: 8)
                if isCreator && !me {
                    Button { removeTarget = m } label: {
                        Text("[remove]").font(Theme.mono(10, weight: .bold))
                            .foregroundStyle(Theme.alert)
                            .fixedSize()
                    }
                    .disabled(busy)
                    .accessibilityLabel("Remove \(m.name)")
                }
            }
            HStack(spacing: 8) {
                Text("joined \(Self.joined.string(from: m.joinedAt))")
                    .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
                    .fixedSize()
                if let receipt {
                    Text(receipt).font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
                        .fixedSize()
                }
            }
        }
    }

    @ViewBuilder
    private func creatorControls(_ g: Group) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("screenshot policy").font(Theme.mono(12))
                Spacer()
                Picker(selection: Binding(
                    get: { g.policy },
                    set: { p in run { try await service.setGroupPolicy(groupID, policy: p, burnSeconds: g.burnSeconds) } })) {
                    ForEach(GroupPolicy.allCases, id: \.self) { p in
                        Text(p.label).font(Theme.mono(12)).tag(p)
                    }
                } label: { EmptyView() }
                .pickerStyle(.menu)
                .tint(Theme.fg(settings))
            }
            HStack {
                Text("burn default").font(Theme.mono(12))
                Spacer()
                Picker(selection: Binding(
                    get: { Self.burnLabel(g.burnSeconds) },
                    set: { label in
                        let secs = Self.burnOptions.first { $0.0 == label }?.1
                        run { try await service.setGroupPolicy(groupID, policy: g.policy, burnSeconds: secs ?? nil) }
                    })) {
                    ForEach(Self.burnOptions, id: \.0) { label, _ in
                        Text(label).font(Theme.mono(12)).tag(label)
                    }
                } label: { EmptyView() }
                .pickerStyle(.menu)
                .tint(Theme.fg(settings))
            }
            Button { renameText = g.name; showRename = true } label: {
                Text("[ rename ]").font(Theme.mono(13, weight: .bold)).fixedSize()
            }
            .disabled(busy)
            Divider().background(Theme.fgDim(settings))
            Button { Haptics.warn(); confirmBurn = true } label: {
                Text("[ BURN GROUP ]").font(Theme.mono(14, weight: .bold))
                    .foregroundStyle(Theme.alert)
                    .fixedSize()
            }
            .disabled(busy)
            .accessibilityLabel("Burn group")
            Text("burning asks every member's app to delete its copy. a member running a modified app could keep one — exactly as with delete-for-both.")
                .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
        }
        .padding(.top, 8)
    }

    private func run(_ op: @escaping () async throws -> Void) {
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do { try await op() } catch { self.error = "\(error)" }
        }
    }

    static let burnOptions: [(String, TimeInterval?)] = [
        ("global default", nil), ("off", 0), ("5s", 5), ("1m", 60), ("1h", 3600), ("1d", 86400),
    ]

    static func burnLabel(_ secs: TimeInterval?) -> String {
        burnOptions.first { $0.1 == secs }?.0 ?? "\(Int(secs ?? 0))s"
    }

    private static let joined: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "HH:mm"; return f
    }()
}

/// `[ add member ]`: contacts not yet on the roster. Any member may add.
struct AddMemberView: View {
    @ObservedObject var service: ChatService
    let groupID: String
    @Environment(\.dismiss) private var dismiss
    @State private var error: String?
    @State private var busy = false

    private var settings: AppSettings { service.store.blob.settings }

    var body: some View {
        let g = service.group(id: groupID)
        let candidates = service.contacts
            .filter { c in !service.isBlocked(c.hash) && !(g?.isMember(c.hash) ?? false) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        return ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("── add member ──").font(Theme.mono(13)).foregroundStyle(Theme.fgDim(settings))
                    Spacer()
                    Button { dismiss() } label: {
                        Text("[X]").font(Theme.mono(13, weight: .bold))
                            .foregroundStyle(Theme.fgDim(settings))
                    }
                    .accessibilityLabel("Close")
                }
                if let g {
                    Text("#\(g.name) \u{00B7} \(g.members.count)/\(GroupMath.maxMembers)")
                        .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
                }
                if let error {
                    Text(error).font(Theme.mono(12)).foregroundStyle(Theme.alert)
                }
                if candidates.isEmpty {
                    Text("every contact is already in this group.")
                        .font(Theme.mono(12)).foregroundStyle(Theme.fgDim(settings))
                }
                ForEach(candidates) { c in
                    Button {
                        busy = true
                        error = nil
                        Task {
                            defer { busy = false }
                            do {
                                try await service.addMember(c, to: groupID)
                                Haptics.tap()
                                dismiss()
                            } catch {
                                self.error = "\(error)"
                            }
                        }
                    } label: {
                        HStack(spacing: 8) {
                            Text("[+]").font(Theme.mono(13, weight: .bold))
                            Text("<\(c.name)>").font(Theme.mono(13, weight: .semibold))
                                .foregroundStyle(Theme.flame)
                            if c.verified { Text("\u{2713}").font(Theme.mono(12, weight: .bold)) }
                            Spacer()
                        }
                        .contentShape(Rectangle())
                    }
                    .disabled(busy)
                    .accessibilityLabel("Add \(c.name)")
                }
            }
            .padding(16)
        }
        .terminalScreen(settings)
        .presentationDetents([.medium, .large])
    }
}
