import SwiftUI
import UIKit

/// Session list rendered like a process table.
struct HomeView: View {
    /// Non-Contact destinations reachable through the NavigationPath.
    enum Route: Hashable { case settings, notices, newGroup, addByQR }

    /// One row of the session list: a 1:1 contact or a group channel.
    enum Channel: Identifiable, Hashable {
        case contact(Contact)
        case group(Group)
        var id: String {
            switch self {
            case .contact(let c): return c.hash
            case .group(let g): return g.id
            }
        }
    }

    @ObservedObject var service: ChatService
    @State private var showAdd = false
    @State private var newContact = ""
    @State private var addError: String?
    @State private var path: NavigationPath
    @State private var query = ""
    @State private var renameTarget: Contact?
    @State private var renameText = ""
    @State private var deleteTarget: Contact?
    @State private var deleteGroupTarget: Group?

    private var settings: AppSettings { service.store.blob.settings }

    init(service: ChatService) {
        _service = ObservedObject(wrappedValue: service)
        var initial = NavigationPath()
        #if DEBUG
        // screenshot demo: `--screenshot-demo=chat|settings|notices` lands on
        // that screen directly (no UI automation needed for the captures)
        switch ScreenshotDemo.screen {
        case .chat?:
            if let bot = service.contacts.first(where: { $0.hash == ScreenshotDemo.pyrobotHash }) {
                initial.append(bot)
            }
        case .settings?:
            initial.append(Route.settings)
        case .notices?:
            initial.append(Route.notices)
        case .group?, .groupMembers?:
            if let g = service.groups.first { initial.append(g) }
        default:
            break
        }
        #endif
        #if ACCESS_KEY_UI
        // docs/FIRST-RUN.md §3: a key held while the store was locked is
        // applied right after unlock — land on [cfg] so the ACCESS KEY
        // dialog opens pre-filled at once
        if service.pendingAccessKey != nil { initial = NavigationPath([Route.settings]) }
        #endif
        _path = State(initialValue: initial)
    }

    var body: some View {
        NavigationStack(path: $path) {
            VStack(alignment: .leading, spacing: 0) {
                header
                Divider().background(Theme.fgDim(settings))
                updateRow
                if let flash = service.homeFlash {
                    // §6b: a burned group leaves no thread to hold a system row
                    Text(flash).font(Theme.mono(12)).foregroundStyle(Theme.alert)
                        .padding(.horizontal, 16).padding(.vertical, 6)
                        .transition(.opacity)
                }

                if FirstRun.showsEmptyState(contacts: service.contacts.count,
                                            groups: service.groups.count) {
                    // docs/FIRST-RUN.md §1: three lines, verbatim on both platforms
                    // (hint lines at 12: at 13 the second one breaks at "60-")
                    VStack(alignment: .leading, spacing: 6) {
                        Text(FirstRun.emptyStateLines[0]).font(Theme.mono(13))
                        Text(FirstRun.emptyStateLines[1])
                            .font(Theme.mono(12)).foregroundStyle(Theme.fgDim(settings))
                        Text(FirstRun.emptyStateLines[2])
                            .font(Theme.mono(12)).foregroundStyle(Theme.fgDim(settings))
                    }
                    .padding(.horizontal, 16).padding(.vertical, 20)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("emptyState")
                }

                if !service.contacts.isEmpty || !service.groups.isEmpty { grepBar }
                sessionList
            }
            .terminalScreen(settings)
            .navigationDestination(for: Contact.self) { contact in
                ChatView(service: service, contact: contact)
            }
            .navigationDestination(for: Group.self) { group in
                GroupChatView(service: service, groupID: group.id)
            }
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .settings: SettingsView(service: service)
                case .notices: NoticesView(service: service)
                case .addByQR: AddByQRView(service: service)
                case .newGroup:
                    NewGroupView(service: service) { group in
                        // straight into the new channel (replace the form)
                        if !path.isEmpty { path.removeLast() }
                        path.append(group)
                    }
                }
            }
            #if DEBUG
            // demo autopilot: `--demo-open` pushes the first chat once it exists
            .onChange(of: service.contacts) { _, contacts in
                if ProcessInfo.processInfo.arguments.contains("--demo-open"),
                   path.isEmpty, let first = contacts.first {
                    path.append(first)
                }
            }
            #endif
            #if ACCESS_KEY_UI
            // docs/FIRST-RUN.md §3: a key arrived (scanner, paste field, deep
            // link) — land on [cfg], whose ACCESS KEY dialog opens pre-filled
            .onChange(of: service.pendingAccessKey) { _, key in
                if key != nil { path = NavigationPath([Route.settings]) }
            }
            #endif
            #if DEBUG
            .task {
                // screenshot demo `--demo-share`: open the share sheet unattended
                guard ScreenshotDemo.shareOnLaunch else { return }
                try? await Task.sleep(for: .seconds(1.5))
                share()
            }
            #endif
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    // docs/FIRST-RUN.md §2: [share] right before [qr]
                    Button { share() } label: {
                        Text("[share]").font(Theme.monoCapped(12, weight: .bold))
                            .foregroundStyle(Theme.fgDim(settings))
                    }
                    .accessibilityLabel("Share my username")
                    NavigationLink(value: Route.addByQR) {
                        Text("[qr]").font(Theme.monoCapped(12, weight: .bold))
                            .foregroundStyle(Theme.fgDim(settings))
                    }
                    .accessibilityLabel("Add contact by QR")
                    Button { showAdd = true } label: {
                        Text("[+]").font(Theme.monoCapped(14, weight: .bold))
                            .foregroundStyle(Theme.fg(settings))
                    }
                    .accessibilityLabel("Open new channel")
                }
                ToolbarItemGroup(placement: .topBarLeading) {
                    NavigationLink(value: Route.settings) {
                        Text("[cfg]").font(Theme.monoCapped(12))
                            .foregroundStyle(Theme.fgDim(settings))
                    }
                    .accessibilityLabel("Settings")
                    NavigationLink {
                        NotesListView(service: service)
                    } label: {
                        Text("[txt]").font(Theme.monoCapped(12))
                            .foregroundStyle(Theme.fgDim(settings))
                    }
                    .accessibilityLabel("Notes")
                }
            }
            .alert("OPEN CHANNEL", isPresented: $showAdd) {
                TextField("username", text: $newContact)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("CONNECT") {
                    let name = newContact.trimmingCharacters(in: .whitespaces)
                    newContact = ""
                    guard !name.isEmpty else { return }
                    Task {
                        do {
                            try await service.addContact(username: name)
                            addError = nil
                        } catch {
                            // re-present with the failure — a silently
                            // dismissed alert reads as success
                            addError = "could not open <\(name)>: \(error)"
                            showAdd = true
                        }
                    }
                }
                Button("[ NEW GROUP ]") {
                    newContact = ""
                    addError = nil
                    path.append(Route.newGroup)
                }
                Button("ABORT", role: .cancel) { newContact = ""; addError = nil }
            } message: {
                Text(addError ?? "target must be a registered pyrochat user")
            }
            .alert("RM GROUP", isPresented: Binding(
                get: { deleteGroupTarget != nil },
                set: { if !$0 { deleteGroupTarget = nil } })) {
                Button("RM -RF", role: .destructive) {
                    if let g = deleteGroupTarget {
                        Haptics.warn()
                        Task {
                            if !g.isLeft { try? await service.leaveGroup(g.id) }
                            service.destroyGroup(g.id)
                        }
                    }
                    deleteGroupTarget = nil
                }
                Button("ABORT", role: .cancel) { deleteGroupTarget = nil }
            } message: {
                Text(deleteGroupTarget?.isLeft == true
                     ? "removes this channel and every local message + attachment. no undo."
                     : "leaves #\(deleteGroupTarget?.name ?? "") and removes every local message + attachment. the other members keep theirs. no undo.")
            }
            .alert("MV NAME", isPresented: Binding(
                get: { renameTarget != nil },
                set: { if !$0 { renameTarget = nil } })) {
                TextField("name", text: $renameText)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("MV") {
                    if let c = renameTarget { service.renameContact(c.hash, to: renameText) }
                    renameTarget = nil
                }
                Button("ABORT", role: .cancel) { renameTarget = nil }
            } message: {
                Text("local display name only — routing is unaffected.")
            }
            .alert("RM CHANNEL", isPresented: Binding(
                get: { deleteTarget != nil },
                set: { if !$0 { deleteTarget = nil } })) {
                Button("RM -RF", role: .destructive) {
                    if let c = deleteTarget {
                        Haptics.warn()
                        service.deleteContact(c.hash)
                    }
                    deleteTarget = nil
                }
                Button("ABORT", role: .cancel) { deleteTarget = nil }
            } message: {
                Text("removes the contact and every local message + attachment. their copies survive. no undo.")
            }
        }
        .tint(Theme.fg(settings))
    }

    /// docs/FIRST-RUN.md §2: the system share sheet with my username and my
    /// `pyro-add` code — three lines, verbatim on both platforms.
    @MainActor
    private func share() {
        guard let code = service.myAddQRPayload() else { return }
        ShareSheet.present(text: FirstRun.shareText(username: service.myUsername, addCode: code))
    }

    private var sessionList: some View {
        List {
            // the relay's own channel, pinned above every session:
            // no swipe actions — there is nothing to rename or rm
            if showsNoticeChannel {
                NavigationLink(value: Route.notices) {
                    noticeRow
                }
                .listRowBackground(Theme.bg)
                .listRowSeparatorTint(Theme.fgDim(settings).opacity(0.3))
                .accessibilityLabel("System notices")
            }
            ForEach(visibleChannels) { channel in
                channelRow(channel)
                    .listRowBackground(Theme.bg)
                    .listRowSeparatorTint(Theme.fgDim(settings).opacity(0.3))
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    @ViewBuilder
    private func channelRow(_ channel: Channel) -> some View {
        switch channel {
        case .contact(let contact):
            NavigationLink(value: contact) {
                row(contact)
            }
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button { deleteTarget = contact } label: {
                    Text("[rm]").font(Theme.mono(12, weight: .bold))
                }
                .tint(Theme.alert)
                .accessibilityLabel("Delete conversation")
            }
            .swipeActions(edge: .leading, allowsFullSwipe: false) {
                Button {
                    renameText = contact.name
                    renameTarget = contact
                } label: {
                    Text("[ren]").font(Theme.mono(12))
                }
                .tint(Theme.fg(settings).opacity(0.6))
                .accessibilityLabel("Rename contact")
            }
        case .group(let group):
            NavigationLink(value: group) {
                groupRow(group)
            }
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button { deleteGroupTarget = group } label: {
                    Text("[rm]").font(Theme.mono(12, weight: .bold))
                }
                .tint(Theme.alert)
                .accessibilityLabel("Remove group")
            }
        }
    }

    /// Newest activity first: the latest message in the thread, or the moment
    /// the contact was added when nothing has been exchanged yet.
    static func orderedByActivity(_ contacts: [Contact], messages: [String: [ChatMessage]]) -> [Contact] {
        func activity(_ c: Contact) -> Date {
            messages[c.hash]?.map(\.timestamp).max() ?? c.addedAt
        }
        return contacts.sorted { activity($0) > activity($1) }
    }

    /// Contacts and groups in one activity-sorted list (a group's activity
    /// falls back to its creation time).
    static func orderedChannels(contacts: [Contact], groups: [Group],
                                messages: [String: [ChatMessage]]) -> [Channel] {
        func activity(_ ch: Channel) -> Date {
            switch ch {
            case .contact(let c): return messages[c.hash]?.map(\.timestamp).max() ?? c.addedAt
            case .group(let g): return messages[g.id]?.map(\.timestamp).max() ?? g.createdAt
            }
        }
        return (contacts.map(Channel.contact) + groups.map(Channel.group))
            .sorted { activity($0) > activity($1) }
    }

    private var visibleChannels: [Channel] {
        let base = Self.orderedChannels(contacts: service.contacts.filter { !service.isBlocked($0.hash) },
                                        groups: service.groups, messages: service.messages)
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return base }
        return base.filter { ch in
            switch ch {
            case .contact(let c):
                return c.name.localizedCaseInsensitiveContains(q)
                    || (service.messages[c.hash] ?? []).contains {
                        $0.text.localizedCaseInsensitiveContains(q)
                    }
            case .group(let g):
                return g.name.localizedCaseInsensitiveContains(q)
                    || ("#" + g.name).localizedCaseInsensitiveContains(q)
                    || (service.messages[g.id] ?? []).contains {
                        $0.text.localizedCaseInsensitiveContains(q)
                    }
            }
        }
    }

    /// `<pyrochat>` shows while notices are active and [cfg] hasn't hidden
    /// it; the grep bar filters it like any other row.
    private var showsNoticeChannel: Bool {
        guard !service.notices.isEmpty, settings.noticesShown else { return false }
        let q = query.trimmingCharacters(in: .whitespaces)
        return q.isEmpty
            || "<pyrochat>".localizedCaseInsensitiveContains(q)
            || service.notices.contains { $0.text.localizedCaseInsensitiveContains(q) }
    }

    /// Same layout as a session row: name, unread chip, time, one-line preview.
    private var noticeRow: some View {
        let newest = service.notices.last
        let unseen = service.unseenNoticeCount
        return VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text("<pyrochat>").font(Theme.mono(14, weight: .semibold))
                    .foregroundStyle(Theme.flame)
                if unseen > 0 {
                    Text("[\(unseen)]")
                        .font(Theme.mono(11, weight: .bold))
                        .foregroundStyle(Theme.bg)
                        .padding(.horizontal, 4)
                        .background(Theme.fg(settings))
                }
                Spacer()
                if let newest {
                    Text(Self.time.string(from: newest.ts))
                        .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
                }
            }
            Text(newest?.preview ?? "")
                .font(Theme.mono(11))
                .foregroundStyle(Theme.fgDim(settings))
                .lineLimit(1)
        }
        .padding(.vertical, 4)
    }

    private var grepBar: some View {
        HStack(spacing: 8) {
            Text("grep:").font(Theme.mono(12)).foregroundStyle(Theme.fgDim(settings))
            TextField("", text: $query)
                .font(Theme.mono(12))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .accessibilityLabel("Search conversations")
        }
        .padding(6)
        .background(Theme.panel)
        .padding(.horizontal, 16).padding(.vertical, 4)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text("PYROCHAT").font(Theme.mono(16, weight: .bold))
                    .foregroundStyle(Theme.flame)
                Spacer()
                Button {
                    guard service.connState == .offline else { return }
                    Task { try? await service.goOnline() }
                } label: {
                    Text(service.connState == .offline ? statusLabel + " [retry]" : statusLabel)
                        .font(Theme.mono(11))
                        .foregroundStyle(service.connState == .online
                                         ? Theme.fg(settings) : Theme.alert)
                }
                .disabled(service.connState != .offline)
                .accessibilityLabel(service.connState == .offline ? "Reconnect" : "Connection status")
            }
            Text("uid: \(service.myUsername)  [\(String(service.myHash.prefix(12)))…]")
                .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
    }

    /// PROTOCOL.md "Latest versions": one full-width flame row directly
    /// under the link line while the relay advertises a build newer than
    /// this one. Shown regardless of the system-notices toggle, not
    /// dismissible, never a notice row; tappable only when a url came along.
    @ViewBuilder
    private var updateRow: some View {
        if let v = service.updateAvailable {
            let label = "\u{25B2} update available: \(v.name) (\(v.build))"
            SwiftUI.Group {                       // `Group` alone is the channel model
                if let url = v.url.flatMap({ URL(string: $0) }) {
                    Button {
                        UIApplication.shared.open(url)
                    } label: {
                        updateText(label + " \u{00B7} tap to get it")
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint("Opens the download page")
                } else {
                    updateText(label)
                }
            }
            .accessibilityLabel("Update available: version \(v.name), build \(v.build)")
            .accessibilityIdentifier("updateBanner")
            Divider().background(Theme.fgDim(settings))
        }
    }

    private func updateText(_ s: String) -> some View {
        Text(s)
            .font(Theme.mono(12)).foregroundStyle(Theme.flame)
            .lineLimit(1).minimumScaleFactor(0.8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16).padding(.vertical, 7)
    }

    private var statusLabel: String {
        switch service.connState {
        case .online: return "● LINK UP"
        case .offline: return "○ LINK DOWN"
        case .connecting, .authenticating: return "◌ SYNCING"
        }
    }

    private func row(_ contact: Contact) -> some View {
        let log = service.messages[contact.hash] ?? []
        let last = log.last
        let unread = service.unreadCount(for: contact.hash)
        return VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text("<\(contact.name)>").font(Theme.mono(14, weight: .semibold))
                    .foregroundStyle(Theme.flame)
                if contact.verified {
                    Text("\u{2713}").font(Theme.mono(12, weight: .bold))
                        .foregroundStyle(Theme.fg(settings))
                }
                if unread > 0 {
                    Text("[\(unread)]")
                        .font(Theme.mono(11, weight: .bold))
                        .foregroundStyle(Theme.bg)
                        .padding(.horizontal, 4)
                        .background(Theme.fg(settings))
                }
                Spacer()
                if let last {
                    Text(Self.time.string(from: last.timestamp))
                        .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
                }
            }
            // docs/FIRST-RUN.md §1: an empty pyrobot thread invites the tour
            Text(FirstRun.subtitle(
                forContactNamed: contact.name,
                lastPreview: last.map { ($0.direction == .outgoing ? "you: " : "") + preview($0) }))
                .font(Theme.mono(11))
                .foregroundStyle(Theme.fgDim(settings))
                .lineLimit(1)
        }
        .padding(.vertical, 4)
    }

    /// `#name` (flame, bold) · `N` members dim, subtitle `<alice> text`.
    private func groupRow(_ g: Group) -> some View {
        let log = service.messages[g.id] ?? []
        let last = log.last
        let unread = service.unreadCount(for: g.id)
        return VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text("#\(g.name)").font(Theme.mono(14, weight: .bold))
                    .foregroundStyle(Theme.flame)
                Text("\u{00B7} \(g.members.count)").font(Theme.mono(12))
                    .foregroundStyle(Theme.fgDim(settings))
                if g.isLeft {
                    Text("left").font(Theme.mono(10)).foregroundStyle(Theme.alert)
                }
                if unread > 0 {
                    Text("[\(unread)]")
                        .font(Theme.mono(11, weight: .bold))
                        .foregroundStyle(Theme.bg)
                        .padding(.horizontal, 4)
                        .background(Theme.fg(settings))
                }
                Spacer()
                if let last {
                    Text(Self.time.string(from: last.timestamp))
                        .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
                }
            }
            Text(last.map { groupPreview($0, g) } ?? "channel open — no traffic")
                .font(Theme.mono(11))
                .foregroundStyle(Theme.fgDim(settings))
                .lineLimit(1)
        }
        .padding(.vertical, 4)
    }

    private func groupPreview(_ m: ChatMessage, _ g: Group) -> String {
        if m.kind == .system { return m.text }
        let who = m.direction == .outgoing ? "you" : service.displayName(for: m.sender ?? "", in: g)
        return "<\(who)> " + preview(m)
    }

    private func preview(_ m: ChatMessage) -> String {
        switch m.kind {
        case .text: return m.text
        case .voice: return "[voice note]"
        case .image: return "[image]"
        case .file: return "[file] \(m.fileName ?? "")"
        case .system: return m.text
        }
    }

    private static let time: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "HH:mm"; return f
    }()
}
