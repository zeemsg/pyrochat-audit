import SwiftUI

/// Encrypted local notes ("buffers"), rendered like a file listing.
/// Product decision: no share, no export, no printing, no cloud — notes
/// never leave the SecureStore. The only way out is reading the screen.
struct NotesListView: View {
    @ObservedObject var service: ChatService

    /// Push target: which note the editor opens, and whether [+] just made it.
    struct OpenNote: Identifiable, Hashable {
        let id: String
        let isNew: Bool
    }

    @State private var query = ""
    @State private var openedNote: OpenNote?
    @State private var pendingDelete: Note?

    private var settings: AppSettings { service.store.blob.settings }

    private var filtered: [Note] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return service.sortedNotes }
        return service.sortedNotes.filter { $0.text.localizedCaseInsensitiveContains(q) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            grepBar
            Divider().background(Theme.fgDim(settings))
            if filtered.isEmpty { emptyState } else { list }
        }
        .terminalScreen(settings)
        .navigationTitle("[txt]")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Theme.bg, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    openedNote = OpenNote(id: service.createNote().id, isNew: true)
                } label: {
                    Text("[+]").font(Theme.monoCapped(15, weight: .bold))
                        .foregroundStyle(Theme.fg(settings))
                }
            }
        }
        .navigationDestination(item: $openedNote) { open in
            NoteEditorView(service: service, noteId: open.id, isNew: open.isNew)
        }
        .alert("RM NOTE", isPresented: Binding(
            get: { pendingDelete != nil },
            set: { if !$0 { pendingDelete = nil } })) {
            Button("RM -F", role: .destructive) {
                if let n = pendingDelete { service.deleteNote(id: n.id) }
                pendingDelete = nil
            }
            Button("ABORT", role: .cancel) { pendingDelete = nil }
        } message: {
            Text("shreds this buffer from the encrypted store. no undo.")
        }
        .tint(Theme.fg(settings))
    }

    private var grepBar: some View {
        HStack(spacing: 8) {
            Text("grep:").font(Theme.mono(13)).foregroundStyle(Theme.fgDim(settings))
            TextField("", text: $query)
                .font(Theme.mono(13))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        }
        .padding(8)
        .background(Theme.panel)
        .padding(.horizontal, 12).padding(.vertical, 8)
    }

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(query.isEmpty ? "no entries. [+] to open a new buffer."
                               : "grep: no match.")
                .font(Theme.mono(13)).foregroundStyle(Theme.fgDim(settings))
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var list: some View {
        List {
            let pinned = filtered.filter(\.isPinned)
            let rest = filtered.filter { !$0.isPinned }
            if !pinned.isEmpty {
                Section { ForEach(pinned) { noteRow($0) } }
                    header: { sectionHeader("PINNED") }
            }
            if !rest.isEmpty {
                Section { ForEach(rest) { noteRow($0) } }
                    header: { sectionHeader("ENTRIES") }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    private func sectionHeader(_ t: String) -> some View {
        Text("── \(t) ──").font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
    }

    private func noteRow(_ note: Note) -> some View {
        Button {
            openedNote = OpenNote(id: note.id, isNew: false)
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    if note.isPinned {
                        Text("*").font(Theme.mono(14, weight: .bold))
                    }
                    Text(note.title).font(Theme.mono(14, weight: .semibold)).lineLimit(1)
                    Spacer()
                    Text(Self.stamp.string(from: note.modifiedAt))
                        .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
                }
                Text(note.preview.isEmpty ? "~" : note.preview)
                    .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings)).lineLimit(1)
            }
            .padding(.vertical, 4)
        }
        .listRowBackground(Theme.bg)
        .listRowSeparatorTint(Theme.fgDim(settings).opacity(0.3))
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button { pendingDelete = note } label: {
                Text("[rm]").font(Theme.mono(12, weight: .bold))
            }
            .tint(Theme.alert)
        }
        .swipeActions(edge: .leading, allowsFullSwipe: false) {
            Button { service.togglePin(id: note.id) } label: {
                Text(note.isPinned ? "[unpin]" : "[pin]").font(Theme.mono(12))
            }
            .tint(Theme.fg(settings).opacity(0.6))
        }
    }

    private static let stamp: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd HH:mm"; return f
    }()
}

/// Full-screen monospace buffer editor. Autosaves (debounced) into the
/// encrypted store; share/export deliberately omitted — notes never leave
/// the SecureStore (product decision, not an oversight).
struct NoteEditorView: View {
    @ObservedObject var service: ChatService
    let noteId: String
    let isNew: Bool

    @State private var text: String = ""
    @State private var loaded = false
    @State private var saveTask: Task<Void, Never>?
    @FocusState private var focused: Bool

    private var settings: AppSettings { service.store.blob.settings }
    private var note: Note? { service.notes.first { $0.id == noteId } }

    var body: some View {
        VStack(spacing: 0) {
            TextEditor(text: $text)
                .font(Theme.mono(14))
                .foregroundStyle(Theme.fg(settings))
                .scrollContentBackground(.hidden)   // show terminal bg
                .background(Theme.bg)
                .tint(Theme.fg(settings))            // phosphor caret
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focused)
                .scrollDismissesKeyboard(.interactively)
                .padding(.horizontal, 8)
        }
        .safeAreaInset(edge: .bottom) { statusBar }
        .terminalScreen(settings)
        .navigationTitle(note?.title ?? "untitled")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Theme.bg, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { service.togglePin(id: noteId) } label: {
                    Text(note?.isPinned == true ? "[unpin]" : "[pin]")
                        .font(Theme.monoCapped(12, weight: .bold))
                        .foregroundStyle(Theme.fg(settings))
                }
            }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            text = note?.text ?? ""
            if isNew { focused = true }
        }
        .onChange(of: text) { scheduleSave() }
        .onDisappear {
            saveTask?.cancel()
            service.updateNoteText(id: noteId, text: text)   // final flush of draft
            service.finalizeNote(id: noteId, isNew: isNew)   // empty-new-buffer discard
        }
    }

    private func scheduleSave() {
        saveTask?.cancel()
        let snapshot = text
        saveTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            service.updateNoteText(id: noteId, text: snapshot)
        }
    }

    private var statusBar: some View {
        HStack(spacing: 10) {
            BlockCursor(color: Theme.fg(settings))
            Text("L\(lineCount) C\(text.count)")
                .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
            Spacer()
            if note?.isPinned == true {
                Text("[pinned]").font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
            }
            Text("enc: aes-256-gcm")
                .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings).opacity(0.7))
        }
        .padding(10)
        .background(Theme.panel)
    }

    private var lineCount: Int {
        text.isEmpty ? 1 : text.components(separatedBy: "\n").count
    }
}
