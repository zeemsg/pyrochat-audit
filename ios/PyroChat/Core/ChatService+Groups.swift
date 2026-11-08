import Foundation

/// Group channels (docs/GROUPS.md). The relay is never involved: a group is
/// shared state inside every member's encrypted store, and every group
/// message or control is an ordinary pairwise send fanned out to each member
/// (§4). Authority is always the pairwise session a control arrived on (§2).
extension ChatService {

    struct GroupSend { let messageId: String; let groupID: String; let member: String }

    // MARK: lookup

    func group(id: String) -> Group? { groups.first { $0.id == id } }
    func isGroupThread(_ thread: String) -> Bool { group(id: thread) != nil }

    /// Roster name → former-member name → contact name → `anon-…`.
    func displayName(for hash: String, in group: Group) -> String {
        if hash == myHash { return myUsername }
        if let n = group.knownName(hash) { return n }
        if let c = contacts.first(where: { $0.hash == hash }) { return c.name }
        return "anon-" + String(hash.prefix(8))
    }

    /// Members a fan-out reaches: everyone but me, minus gone and blocked.
    func recipients(of g: Group) -> [Member] {
        g.members.filter { $0.hash != myHash && !$0.isGone && !blockedHashes.contains($0.hash) }
    }

    /// Per-group default burn (0 = explicit off), else the global setting —
    /// the per-contact semantics (§1).
    func burnDefault(forGroup g: Group) -> TimeInterval? {
        if let s = g.burnSeconds { return s == 0 ? nil : s }
        return store.blob.settings.defaultBurnSeconds
    }

    /// Members typing right now, in roster order, as display names.
    func typingNames(in g: Group) -> [String] {
        let live = groupTyping[g.id] ?? [:]
        return GroupMath.joinOrder(members: g.members)
            .filter { live[$0] != nil && $0 != myHash }
            .map { displayName(for: $0, in: g) }
    }

    /// Safety number by hash (group members need not be contacts).
    func safetyNumber(withHash hash: String) -> SafetyNumber? {
        guard let fp = try? crypto.fingerprint(with: hash) else { return nil }
        return SafetyNumber(digits: fp.displayable.formatted, scannable: fp.scannable.encoding)
    }

    static func isHash(_ s: String) -> Bool {
        s.count == 64 && s.allSatisfy(\.isHexDigit)
    }

    func persistGroups() {
        let snap = groups
        store.update { $0.groups = snap.isEmpty ? nil : snap }
    }

    private func setGroup(_ g: Group) {
        if let i = groups.firstIndex(where: { $0.id == g.id }) { groups[i] = g } else { groups.append(g) }
        persistGroups()
    }

    private func ensureThread(_ id: String) {
        guard messages[id] == nil else { return }
        messages[id] = []
        let snap = messages
        store.update { $0.messages = snap }
    }

    private func requireOnline() throws {
        guard connState == .online, relay != nil else {
            throw ServiceError.relay("ERR: link down — group changes need the link up")
        }
    }

    private func ref(_ g: Group) -> Wire.GroupRef { Wire.GroupRef(id: g.id, epoch: g.epoch) }

    /// `group_create` / `group_invite`: the full current roster + epoch.
    private func rosterPayload(_ ctl: String, _ g: Group) -> Wire.Payload {
        var p = Wire.Payload(kind: "control", control: ctl)
        p.group = ref(g)
        p.name = g.name
        p.creator = g.creator
        // with each member's delivery key where this phone holds it (SEALED-SENDER.md §5.7):
        // members who never spoke directly then start without the relay being told who writes
        p.members = g.members.map {
            Wire.MemberJSON(hash: $0.hash, name: $0.name, joinedAt: $0.joinedAt,
                            dk: rosterDeliveryKey(for: $0.hash))
        }
        p.policy = g.policy.rawValue
        p.burnSeconds = g.burnSeconds
        p.createdAt = g.createdAt
        return p
    }

    /// Rows + sidecars + roster, gone (§6b; also the local `rm` of a left group).
    func destroyGroup(_ id: String) {
        purgeAttachments(of: messages[id] ?? [])
        messages[id] = nil
        groups.removeAll { $0.id == id }
        persistGroups()
        store.update {
            $0.messages[id] = nil
            $0.drafts?[id] = nil
        }
        groupTyping[id] = nil
        rosterStamps[id] = nil
        lastGroupTypingSent[id] = nil
    }

    // MARK: roster operations (this phone is the writer)

    /// New group: me + up to 19 contacts, `group_create` to each of them.
    @discardableResult
    func createGroup(name: String, members picked: [Contact],
                     policy: GroupPolicy = .burnRemove,
                     burnSeconds: TimeInterval? = nil) async throws -> Group {
        guard let clean = GroupMath.normalizeName(name) else {
            throw ServiceError.relay("ERR: name must be 1–32 characters")
        }
        var seen: Set<String> = [myHash]
        let others = picked.filter { seen.insert($0.hash).inserted }
        guard others.count < GroupMath.maxMembers else {
            throw ServiceError.relay("ERR: group is full (20)")
        }
        try requireOnline()
        let now = Date()
        let roster = [Member(hash: myHash, name: myUsername, joinedAt: now)]
            + others.map { Member(hash: $0.hash, name: $0.name, joinedAt: now) }
        let g = Group(id: UUID().uuidString.lowercased(), name: clean, creator: myHash,
                      createdAt: now, epoch: 0, policy: policy, burnSeconds: burnSeconds,
                      members: roster)
        setGroup(g)
        ensureThread(g.id)
        let payload = rosterPayload("group_create", g)
        for m in recipients(of: g) { await sendGroupControl(payload, to: m.hash, in: g.id) }
        return g
    }

    /// Any member may add: `group_add` to the existing members, `group_invite`
    /// (full roster) to the newcomer.
    func addMember(_ contact: Contact, to groupID: String) async throws {
        guard var g = group(id: groupID) else { return }
        guard !g.isLeft, g.isMember(myHash) else {
            throw ServiceError.relay("ERR: you are no longer in this group")
        }
        if let i = g.members.firstIndex(where: { $0.hash == contact.hash }) {
            guard g.members[i].isGone else { return }          // already on the roster
            GroupMath.retire(g.members[i], from: &g)             // re-add a gone account
        }
        guard g.members.count < GroupMath.maxMembers else {
            throw ServiceError.relay("ERR: group is full (20)")
        }
        try requireOnline()
        let audience = recipients(of: g)
        let m = Member(hash: contact.hash, name: contact.name, joinedAt: Date())
        g.members.append(m)
        g.epoch += 1
        rosterStamps[g.id, default: [:]][m.hash] = GroupMath.Stamp(epoch: g.epoch, writer: myHash)
        setGroup(g)
        appendSystem("── <\(m.name)> joined ──", thread: g.id)
        var add = Wire.Payload(kind: "control", control: "group_add")
        add.group = ref(g)
        add.member = Wire.MemberJSON(hash: m.hash, name: m.name, joinedAt: m.joinedAt,
                                     dk: rosterDeliveryKey(for: m.hash))
        for a in audience { await sendGroupControl(add, to: a.hash, in: g.id) }
        await sendGroupControl(rosterPayload("group_invite", g), to: m.hash, in: g.id)
    }

    /// Creator only. `group_remove` to everyone including the removed member.
    func removeMember(_ hash: String, from groupID: String) async throws {
        guard var g = group(id: groupID) else { return }
        guard !g.isLeft, g.isMember(myHash) else {
            throw ServiceError.relay("ERR: you are no longer in this group")
        }
        guard g.isCreator(myHash) else {
            throw ServiceError.relay("ERR: only the creator can remove members")
        }
        guard hash != myHash, let m = g.member(hash) else { return }
        try requireOnline()
        let audience = recipients(of: g)
        GroupMath.retire(m, from: &g)
        g.epoch += 1
        rosterStamps[g.id, default: [:]][hash] = GroupMath.Stamp(epoch: g.epoch, writer: myHash)
        setGroup(g)
        appendSystem("── <\(m.name)> was removed ──", thread: g.id)
        var p = Wire.Payload(kind: "control", control: "group_remove")
        p.group = ref(g)
        p.member = Wire.MemberJSON(hash: hash)
        for a in audience { await sendGroupControl(p, to: a.hash, in: g.id) }
    }

    /// Anyone, any time. The thread stays, read-only (`left`).
    func leaveGroup(_ groupID: String) async throws {
        guard var g = group(id: groupID), !g.isLeft, let me = g.member(myHash) else { return }
        try requireOnline()
        let audience = recipients(of: g)
        g.epoch += 1
        var p = Wire.Payload(kind: "control", control: "group_leave")
        p.group = ref(g)
        for a in audience { await sendGroupControl(p, to: a.hash, in: g.id) }
        GroupMath.retire(me, from: &g)
        g.left = true
        setGroup(g)
    }

    func renameGroup(_ groupID: String, to raw: String) async throws {
        guard var g = group(id: groupID) else { return }
        guard !g.isLeft, g.isMember(myHash) else {
            throw ServiceError.relay("ERR: you are no longer in this group")
        }
        guard g.isCreator(myHash) else {
            throw ServiceError.relay("ERR: only the creator can rename the group")
        }
        guard let name = GroupMath.normalizeName(raw) else {
            throw ServiceError.relay("ERR: name must be 1–32 characters")
        }
        try requireOnline()
        g.name = name
        g.epoch += 1
        setGroup(g)
        appendSystem("── renamed to #\(name) ──", thread: g.id)
        var p = Wire.Payload(kind: "control", control: "group_rename")
        p.group = ref(g)
        p.name = name
        for a in recipients(of: g) { await sendGroupControl(p, to: a.hash, in: g.id) }
    }

    func setGroupPolicy(_ groupID: String, policy: GroupPolicy, burnSeconds: TimeInterval?) async throws {
        guard var g = group(id: groupID) else { return }
        guard !g.isLeft, g.isMember(myHash) else {
            throw ServiceError.relay("ERR: you are no longer in this group")
        }
        guard g.isCreator(myHash) else {
            throw ServiceError.relay("ERR: only the creator can change the policy")
        }
        try requireOnline()
        g.policy = policy
        g.burnSeconds = burnSeconds
        g.epoch += 1
        setGroup(g)
        appendSystem("── screenshot policy: \(policy.rawValue) ──", thread: g.id)
        var p = Wire.Payload(kind: "control", control: "group_policy")
        p.group = ref(g)
        p.policy = policy.rawValue
        p.burnSeconds = burnSeconds
        for a in recipients(of: g) { await sendGroupControl(p, to: a.hash, in: g.id) }
    }

    /// §6b: `group_burn` to every member, retried until each `sent` lands
    /// (three attempts, 8 s verdict deadline each — never unbounded), then
    /// this phone's copy goes too.
    func burnGroup(_ groupID: String) async throws {
        guard let g = group(id: groupID), !g.isLeft, g.isMember(myHash) else { return }
        guard g.isCreator(myHash) else {
            throw ServiceError.relay("ERR: only the creator can burn the group")
        }
        try requireOnline()
        var p = Wire.Payload(kind: "control", control: "group_burn")
        p.group = ref(g)
        for a in recipients(of: g) {
            for attempt in 0..<3 {
                if await sendGroupControl(p, to: a.hash, in: g.id, awaitVerdict: true) { break }
                if attempt < 2 { try? await Task.sleep(for: .seconds(2)) }
            }
        }
        destroyGroup(g.id)
    }

    /// One control to one member over the pairwise session (fetching a bundle
    /// when there is no session yet). `awaitVerdict` waits ≤ 8 s for the
    /// relay's sent/error; otherwise true just means the frame was written.
    /// Never throws: an unreachable member is skipped, a deregistered one is
    /// marked gone (§4).
    @discardableResult
    func sendGroupControl(_ payload: Wire.Payload, to hash: String, in groupID: String,
                          awaitVerdict: Bool = false, ttl: Int = 172_800) async -> Bool {
        do {
            if !crypto.hasSession(with: hash) {
                try await fetchAndProcessBundle(for: hash)
            }
            let id = UUID().uuidString.lowercased()
            let out = try prepare(payload, to: hash, kind: "control", ttl: ttl, id: id)
            guard let relay else { return false }
            if !awaitVerdict {
                try await dispatch(out, via: relay)
                return true
            }
            return await withCheckedContinuation { cont in
                pendingControlAcks[id] = cont
                Task { [weak self] in
                    do {
                        try await self?.dispatch(out, via: relay)
                    } catch {
                        self?.pendingControlAcks.removeValue(forKey: id)?.resume(returning: false)
                        return
                    }
                    try? await Task.sleep(for: .seconds(8))
                    self?.pendingControlAcks.removeValue(forKey: id)?.resume(returning: false)
                }
            }
        } catch {
            if "\(error)".contains("unknown_user") { noteMemberGone(hash, in: groupID) }
            return false
        }
    }

    /// The relay forgot this member's keys (§4): skipped by every fan-out
    /// from now on; the creator may re-add later.
    func noteMemberGone(_ hash: String, in groupID: String) {
        guard var g = group(id: groupID),
              let i = g.members.firstIndex(where: { $0.hash == hash }),
              !g.members[i].isGone else { return }
        g.members[i].gone = true
        setGroup(g)
        appendSystem("── <\(g.members[i].name)> deleted their account ──", thread: g.id)
    }

    // MARK: fan-out (§4)

    /// `viaWeb`: composed on a linked desktop browser (MIRROR-GROUPS.md §3)
    /// — the row and every member's copy carry the dim `[web]` tag, as in 1:1.
    func sendText(_ text: String, toGroup groupID: String, viaWeb: Bool = false) async {
        guard let g = group(id: groupID) else { return }
        guard !g.isLeft else { lastError = "ERR: you are no longer in this group"; return }
        let id = UUID().uuidString.lowercased()
        let burn = burnDefault(forGroup: g)
        var msg = ChatMessage(id: id, direction: .outgoing, kind: .text, text: text,
                              timestamp: Date(), status: .pending,
                              burnAfter: burn, burnStartedAt: nil)
        msg.viaWeb = viaWeb ? true : nil
        msg.group = g.id
        msg.receipts = [:]
        appendMessage(msg, thread: g.id)
        SoundPlayer.play(.tx, settings: store.blob.settings)
        var payload = Wire.Payload(kind: "text", text: text, senderName: myUsername, burnAfter: burn)
        payload.via = viaWeb ? "web" : nil
        payload.group = ref(g)
        payload.refId = id
        await fanOut(messageId: id, group: g, payload: payload, to: recipients(of: g).map(\.hash))
    }

    /// Encrypt the SAME payload once per recipient, fresh relay id each.
    private func fanOut(messageId: String, group g: Group, payload: Wire.Payload, to targets: [String]) async {
        var states = groupOutbound[messageId] ?? [:]
        for t in targets { states[t] = .pending }
        groupOutbound[messageId] = states
        refreshGroupStatus(messageId, thread: g.id)
        for t in targets {
            do {
                if !crypto.hasSession(with: t) {
                    try await fetchAndProcessBundle(for: t)
                }
                let relayId = UUID().uuidString.lowercased()
                let out = try prepare(payload, to: t, kind: "msg", ttl: 172_800, id: relayId)
                guard let relay else { throw URLError(.notConnectedToInternet) }
                groupSends[relayId] = GroupSend(messageId: messageId, groupID: g.id, member: t)
                // MESSAGE-DETAILS.md §3: one row, N envelopes — the sheet
                // shows one of them, and they are all different ciphertext
                captureFrame(try await dispatch(out, via: relay), for: messageId)
            } catch {
                if "\(error)".contains("unknown_user") {
                    noteMemberGone(t, in: g.id)
                    groupOutbound[messageId]?[t] = nil
                } else {
                    groupOutbound[messageId]?[t] = .failed
                    lastError = (error as? ServiceError)?.description ?? error.localizedDescription
                }
            }
        }
        refreshGroupStatus(messageId, thread: g.id)
    }

    /// Relay verdicts for one recipient's envelope.
    func noteGroupSent(_ gs: GroupSend) {
        groupOutbound[gs.messageId]?[gs.member] = .sent
        refreshGroupStatus(gs.messageId, thread: gs.groupID)
    }

    func noteGroupSendError(_ gs: GroupSend, code: String?) {
        if code == "unknown_user" {
            noteMemberGone(gs.member, in: gs.groupID)
            groupOutbound[gs.messageId]?[gs.member] = nil
        } else {
            groupOutbound[gs.messageId]?[gs.member] = .failed
            if code == "rate_limited", !rateLimitRetried.contains(gs.messageId) {
                // one silent retry of the failed recipients after the limiter window
                rateLimitRetried.insert(gs.messageId)
                Task { [weak self] in
                    try? await Task.sleep(for: .seconds(5))
                    await self?.retryGroupMessage(messageId: gs.messageId, groupID: gs.groupID)
                }
            }
        }
        refreshGroupStatus(gs.messageId, thread: gs.groupID)
    }

    /// Aggregated row status from the live send states + the receipts map.
    func refreshGroupStatus(_ messageId: String, thread: String) {
        guard var log = messages[thread],
              let i = log.firstIndex(where: { $0.id == messageId }),
              log[i].direction == .outgoing else { return }
        let states = Array((groupOutbound[messageId] ?? [:]).values)
        let n = group(id: thread).map { recipients(of: $0).count } ?? 0
        let status = GroupMath.rowStatus(sendStates: states, receipts: log[i].receipts ?? [:],
                                         recipients: n, previous: log[i].status)
        if !states.isEmpty, !states.contains(.pending), !states.contains(.failed) {
            groupOutbound[messageId] = nil          // everyone reached: bookkeeping done
        }
        guard status != log[i].status else { return }
        log[i].status = status
        messages[thread] = log
        let snapshot = messages
        store.update { $0.messages = snapshot }
    }

    /// FAILED [RETRY] on a group row: re-sends only to the recipients that
    /// errored (or, after a relaunch, to everyone who never receipted).
    func retryGroupMessage(messageId: String, groupID: String) async {
        guard let g = group(id: groupID), !g.isLeft,
              let msg = messages[groupID]?.first(where: { $0.id == messageId }),
              msg.direction == .outgoing, msg.status == .failed else { return }
        let targets = GroupMath.retryTargets(states: groupOutbound[messageId] ?? [:],
                                             recipients: recipients(of: g).map(\.hash),
                                             receipts: msg.receipts ?? [:])
        guard !targets.isEmpty else {
            groupOutbound[messageId] = nil
            setStatus(id: messageId, to: .sent) { $0 == .failed }
            refreshGroupStatus(messageId, thread: groupID)
            return
        }
        switch msg.kind {
        case .text:
            var payload = Wire.Payload(kind: "text", text: msg.text, senderName: myUsername,
                                       burnAfter: msg.burnAfter)
            payload.via = msg.viaWeb == true ? "web" : nil
            payload.group = ref(g)
            payload.refId = messageId
            await fanOut(messageId: messageId, group: g, payload: payload, to: targets)
        case .voice:
            guard let data = voiceData(for: msg) else { return }
            await fanOutBlob(messageId: messageId, group: g, content: data, subtype: .voice,
                             duration: msg.voiceDuration, to: targets)
        case .image:
            guard let data = attachmentData(for: msg) else { return }
            await fanOutBlob(messageId: messageId, group: g, content: data, subtype: .image,
                             mime: msg.fileMime, width: msg.imageWidth, height: msg.imageHeight,
                             to: targets)
        case .file:
            guard let data = attachmentData(for: msg) else { return }
            await fanOutBlob(messageId: messageId, group: g, content: data, subtype: .file,
                             filename: msg.fileName, mime: msg.fileMime, to: targets)
        case .system:
            break
        }
    }

    // MARK: media fan-out (the blob pipeline once per recipient, same plaintext)

    func sendImage(jpeg: Data, width: Int, height: Int, toGroup groupID: String,
                   removed: [ScrubField]? = nil) async {
        guard let g = group(id: groupID) else { return }
        guard !g.isLeft else { lastError = "ERR: you are no longer in this group"; return }
        let id = UUID().uuidString.lowercased()
        let attId = UUID().uuidString.lowercased()
        guard (try? store.writeAttachment(id: attId, data: jpeg)) != nil else {
            lastError = "store_io"; return
        }
        var msg = ChatMessage(id: id, direction: .outgoing, kind: .image, text: "[IMG]",
                              timestamp: Date(), status: .pending,
                              burnAfter: burnDefault(forGroup: g), burnStartedAt: nil,
                              attachmentId: attId, fileMime: "image/jpeg",
                              fileBytes: jpeg.count, imageWidth: width, imageHeight: height)
        msg.removedMetadata = (removed?.isEmpty == false) ? removed : nil
        msg.scrubbable = true
        msg.group = g.id
        msg.receipts = [:]
        appendMessage(msg, thread: g.id)
        SoundPlayer.play(.tx, settings: store.blob.settings)
        await fanOutBlob(messageId: id, group: g, content: jpeg, subtype: .image,
                         mime: "image/jpeg", width: width, height: height,
                         to: recipients(of: g).map(\.hash))
    }

    /// docs/SCRUB-AND-WIPE.md §B — the same scrub as the 1:1 path, on the one
    /// plaintext every member's copy is encrypted from.
    func sendFile(data: Data, filename: String, mime: String, toGroup groupID: String,
                  scrubbed: MetadataScrubber.Report? = nil) async {
        guard let g = group(id: groupID) else { return }
        guard !g.isLeft else { lastError = "ERR: you are no longer in this group"; return }
        guard !data.isEmpty else { lastError = "empty_file"; return }
        guard data.count <= Self.maxFilePlaintextBytes else { lastError = "file_too_large"; return }
        let name = sanitizeFilename(filename)
        var payload = data
        var report = scrubbed
        if report == nil {
            let (clean, made) = await MetadataScrubber.scrub(data, filename: name, mime: mime)
            payload = clean
            report = made
        }
        guard payload.count <= Self.maxFilePlaintextBytes else {
            lastError = "file_too_large"; return
        }
        let id = UUID().uuidString.lowercased()
        let attId = UUID().uuidString.lowercased()
        guard (try? store.writeAttachment(id: attId, data: payload)) != nil else {
            lastError = "store_io"; return
        }
        var msg = ChatMessage(id: id, direction: .outgoing, kind: .file,
                              text: "[FILE] \(name)", timestamp: Date(), status: .pending,
                              burnAfter: burnDefault(forGroup: g), burnStartedAt: nil,
                              attachmentId: attId, fileName: name, fileMime: mime,
                              fileBytes: payload.count)
        msg.removedMetadata = report?.anything == true ? report?.removed : nil
        msg.scrubbable = report?.supported
        msg.group = g.id
        msg.receipts = [:]
        appendMessage(msg, thread: g.id)
        SoundPlayer.play(.tx, settings: store.blob.settings)
        await fanOutBlob(messageId: id, group: g, content: payload, subtype: .file,
                         filename: name, mime: mime, to: recipients(of: g).map(\.hash))
    }

    /// False only when the voice could not be changed (docs/VOICE-CHANGER.md): nothing was
    /// sent, and no row was made.
    @discardableResult
    func sendVoice(_ spoken: Data, duration: TimeInterval, toGroup groupID: String) async -> Bool {
        guard let g = group(id: groupID) else { return true }
        guard !g.isLeft else { lastError = "ERR: you are no longer in this group"; return true }
        guard let audio = await inChosenVoice(spoken, in: g.id) else { return false }
        let id = UUID().uuidString.lowercased()
        var msg = ChatMessage(id: id, direction: .outgoing, kind: .voice,
                              text: "[voice \(Int(duration))s]", timestamp: Date(),
                              status: .pending, burnAfter: burnDefault(forGroup: g),
                              burnStartedAt: nil, voiceDuration: duration)
        applyVoiceSidecar(&msg, audio: audio)       // §1a
        msg.group = g.id
        msg.receipts = [:]
        appendMessage(msg, thread: g.id)
        SoundPlayer.play(.tx, settings: store.blob.settings)
        await fanOutBlob(messageId: id, group: g, content: audio, subtype: .voice,
                         duration: duration, to: recipients(of: g).map(\.hash))
        return true
    }

    private func fanOutBlob(messageId: String, group g: Group, content: Data, subtype: BlobSubtype,
                            duration: TimeInterval? = nil, filename: String? = nil,
                            mime: String? = nil, width: Int? = nil, height: Int? = nil,
                            to targets: [String]) async {
        var states = groupOutbound[messageId] ?? [:]
        for t in targets { states[t] = .pending }
        groupOutbound[messageId] = states
        refreshGroupStatus(messageId, thread: g.id)
        let burn = messages[g.id]?.first { $0.id == messageId }?.burnAfter
        let total = Double(max(targets.count, 1))
        if !targets.isEmpty { blobProgress[messageId] = 0 }
        var lost = false
        for (n, t) in targets.enumerated() {
            let relayId = UUID().uuidString.lowercased()
            groupSends[relayId] = GroupSend(messageId: messageId, groupID: g.id, member: t)
            do {
                try await uploadBlob(relayId: relayId, content: content, subtype: subtype,
                                     duration: duration, filename: filename, mime: mime,
                                     width: width, height: height, burn: burn, to: t,
                                     refId: messageId, group: ref(g)) { frac in
                    blobProgress[messageId] = (Double(n) + frac) / total
                }
            } catch {
                groupSends[relayId] = nil
                if "\(error)".contains("unknown_user") {
                    noteMemberGone(t, in: g.id)
                    groupOutbound[messageId]?[t] = nil
                } else {
                    groupOutbound[messageId]?[t] = .failed
                    lastError = (error as? ServiceError)?.description ?? (error as? AnonLinkLost)?.description
                        ?? error.localizedDescription
                    if error is AnonLinkLost { lost = true }
                }
            }
        }
        blobProgress[messageId] = nil
        refreshGroupStatus(messageId, thread: g.id)
        if lost { lostWithConnection() }      // after the row has its status: the retry looks for FAILED rows
    }

    // MARK: delete for all (author only — fans out the existing `delete_msg`)

    /// Like deleteForBoth: needs the link; the local row goes only once at
    /// least one member's relay verdict was `sent`.
    @discardableResult
    func deleteForAll(id: String, groupID: String) async -> Bool {
        guard let g = group(id: groupID),
              let msg = messages[groupID]?.first(where: { $0.id == id }),
              msg.direction == .outgoing else { return false }
        guard connState == .online, relay != nil else {
            lastError = "offline — delete for all needs the link up"
            return false
        }
        var p = Wire.Payload(kind: "control", control: "delete_msg")
        p.refIds = [id]
        p.group = ref(g)
        let audience = recipients(of: g)
        var accepted = audience.isEmpty
        for a in audience {
            if await sendGroupControl(p, to: a.hash, in: g.id, awaitVerdict: true) { accepted = true }
        }
        guard accepted else {
            lastError = "delete for all refused by relay — try again"
            return false
        }
        deleteMessage(id: id, thread: groupID)
        return true
    }

    // MARK: typing (per member, E2E)

    func noteGroupComposerActivity(_ g: Group) {
        guard store.blob.settings.typingOn, connState == .online, !g.isLeft else { return }
        let now = Date()
        if let last = lastGroupTypingSent[g.id], now.timeIntervalSince(last) < 4 { return }
        lastGroupTypingSent[g.id] = now
        var p = Wire.Payload(kind: "control", control: "typing")
        p.group = ref(g)
        for m in recipients(of: g) where crypto.hasSession(with: m.hash) {
            guard let out = try? prepare(p, to: m.hash, kind: "control", ttl: 60,
                                         id: UUID().uuidString.lowercased()) else { continue }
            Task { try? await dispatch(out) }
        }
    }

    // MARK: screenshot policy — this phone is the offender (§6)

    /// Tell every member, then apply MY side of the policy: `burn` wipes the
    /// local thread, `burn_remove` also marks the group left.
    func reportGroupCapture(in groupID: String) async {
        guard let g0 = group(id: groupID), !g0.isLeft else { return }
        var p = Wire.Payload(kind: "control", control: "screenshot")
        p.group = ref(g0)
        for m in recipients(of: g0) { await sendGroupControl(p, to: m.hash, in: g0.id, ttl: 86_400) }
        guard var g = group(id: groupID), !g.isLeft else { return }   // re-read: a burn may have landed
        switch g.policy {
        case .alert:
            break
        case .burn, .burnRemove:
            purgeAttachments(of: messages[g.id] ?? [])
            let note = ChatMessage(id: UUID().uuidString, direction: .incoming, kind: .system,
                                   text: "── this channel burned: you took a screenshot ──",
                                   timestamp: Date(), status: .delivered,
                                   burnAfter: nil, burnStartedAt: nil)
            messages[g.id] = [note]
            store.update { $0.messages[g.id] = [note] }
            if g.policy == .burnRemove {
                if let me = g.member(myHash) { GroupMath.retire(me, from: &g) }
                g.left = true
                g.epoch += 1
                setGroup(g)
            }
        }
    }

    // MARK: inbound (already decrypted; `from` = the pairwise session)

    func receiveGroupText(_ payload: Wire.Payload, groupID: String, from: String, relayId: String) {
        guard let g = group(id: groupID), !g.isLeft else { return }   // unknown / left: dropped
        let rowId = payload.refId ?? relayId
        guard messages[g.id]?.contains(where: { $0.id == rowId }) != true else { return }
        groupTyping[g.id]?[from] = nil
        var msg = ChatMessage(id: rowId, direction: .incoming, kind: .text,
                              text: payload.text ?? "", timestamp: Date(),
                              status: .delivered, burnAfter: payload.burnAfter,
                              burnStartedAt: nil)
        msg.viaWeb = payload.via == "web" ? true : nil
        msg.group = g.id
        msg.sender = from
        appendMessage(msg, thread: g.id)
        enqueueReceipt(.delivered, ids: [rowId], to: from)
        SoundPlayer.play(.rx, settings: store.blob.settings)
    }

    func handleGroupControl(_ payload: Wire.Payload, groupID: String, epoch: Int,
                            from: String, relayTs: Int64?) {
        let current = group(id: groupID)
        let change: GroupChange
        switch payload.control {
        case "group_create", "group_invite":
            guard let g = parseGroup(payload, id: groupID, epoch: epoch) else { return }
            change = .create(g, invite: payload.control == "group_invite")
        case "group_add":
            guard let m = payload.member, let h = m.hash, Self.isHash(h) else { return }
            change = .add(Member(hash: h,
                                 name: Self.sanitizeDisplayName(m.name) ?? "anon-" + String(h.prefix(8)),
                                 joinedAt: m.joinedAt ?? Date()))
        case "group_remove":
            guard let h = payload.member?.hash, Self.isHash(h) else { return }
            change = .remove(hash: h)
        case "group_leave":
            change = .leave
        case "group_rename":
            guard let n = payload.name else { return }
            change = .rename(n)
        case "group_policy":
            change = .policy(GroupPolicy(lenient: payload.policy), burnSeconds: payload.burnSeconds)
        case "group_burn":
            change = .burn
        case "screenshot":
            change = .screenshot
        case "receipt":
            guard let k = payload.receipt, let kind = ReceiptKind(rawValue: k), kind != .burnStart,
                  let ids = payload.refIds else { return }
            applyGroupReceipt(kind, refIds: ids, from: from, groupID: groupID)
            return
        case "burn_start":
            guard let ids = payload.refIds else { return }
            applyGroupBurnStart(refIds: ids, from: from, groupID: groupID)
            return
        case "typing":
            guard let g = current, g.isMember(from) else { return }
            if let ts = relayTs, Date().timeIntervalSince1970 * 1000 - Double(ts) > 10_000 { return }
            groupTyping[groupID, default: [:]][from] = Date()
            return
        case "web_link":
            // MIRROR-GROUPS.md §5: a member linked / unlinked a desktop
            // browser while looking at THIS channel — the row lands in the
            // group thread and names them; a stranger's control is nothing
            guard let g = current, !g.isLeft, g.isMember(from) else { return }
            let browser = payload.browser.flatMap(MirrorEngine.sanitizeBrowserName)
            let name = displayName(for: from, in: g)
            appendSystem(payload.linkUp == true
                ? "── <\(name)> linked a desktop browser" + (browser.map { " (\($0))" } ?? "")
                    + " — this channel may be viewed there ──"
                : "── <\(name)> unlinked the desktop browser ──", thread: g.id)
            return
        case "delete_msg":
            // AUTH BOUNDARY: only rows the sender authored in this group
            guard let ids = payload.refIds, ids.count <= 50, var log = messages[groupID] else { return }
            let victims = log.filter { ids.contains($0.id) && $0.direction == .incoming && $0.sender == from }
            guard !victims.isEmpty else { return }
            purgeAttachments(of: victims)
            let gone = Set(victims.map(\.id))
            log.removeAll { gone.contains($0.id) }
            messages[groupID] = log
            let snapshot = messages
            store.update { $0.messages = snapshot }
            return
        default:
            return
        }
        let outcome = GroupMath.apply(change, epoch: epoch, from: from, myHash: myHash,
                                      to: current, stamps: rosterStamps[groupID] ?? [:])
        applyOutcome(outcome, groupID: groupID)
        if outcome.applied {
            // a roster that was accepted may carry members' delivery keys; one is taken
            // only for a member this phone holds no key for (SEALED-SENDER.md §5.1)
            for m in payload.members ?? [] {
                if let h = m.hash, Self.isHash(h) { learnRosterKey(m.dk, for: h) }
            }
            if payload.control == "group_add", let m = payload.member, let h = m.hash, Self.isHash(h) {
                learnRosterKey(m.dk, for: h)
            }
        }
        if case .screenshot = change, outcome.applied {
            SoundPlayer.play(.rx, settings: store.blob.settings)
        }
    }

    private func parseGroup(_ p: Wire.Payload, id: String, epoch: Int) -> Group? {
        guard let rawName = p.name, let name = GroupMath.normalizeName(rawName),
              let creator = p.creator, Self.isHash(creator),
              let list = p.members else { return nil }
        let createdAt = p.createdAt ?? Date()
        var members: [Member] = []
        for m in list {
            guard let h = m.hash, Self.isHash(h), !members.contains(where: { $0.hash == h }) else { continue }
            members.append(Member(hash: h,
                                  name: Self.sanitizeDisplayName(m.name) ?? "anon-" + String(h.prefix(8)),
                                  joinedAt: m.joinedAt ?? createdAt))
        }
        guard !members.isEmpty else { return nil }
        return Group(id: id, name: name, creator: creator, createdAt: createdAt, epoch: epoch,
                     policy: GroupPolicy(lenient: p.policy), burnSeconds: p.burnSeconds,
                     members: members)
    }

    private func applyOutcome(_ o: GroupMath.Outcome, groupID: String) {
        #if DEBUG
        if group(id: groupID) != nil {
            for r in o.debugRows { appendSystem(r, thread: groupID) }
        }
        #endif
        guard o.applied else { return }
        for (h, s) in o.stamps { rosterStamps[groupID, default: [:]][h] = s }
        if o.wipeThread {
            destroyGroup(groupID)
        } else if let g = o.group {
            setGroup(g)
            ensureThread(g.id)
            for r in o.rows { appendSystem(r, thread: g.id) }
        }
        if let f = o.flash {
            homeFlash = f
            SoundPlayer.play(.rx, settings: store.blob.settings)
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(6))
                if self?.homeFlash == f { self?.homeFlash = nil }
            }
        }
    }

    /// A member's delivered/read for my rows: per-member map + aggregate.
    /// `groupID` nil = a receipt without `group` — every group thread is scanned.
    func applyGroupReceipt(_ kind: ReceiptKind, refIds: [String], from peer: String, groupID: String?) {
        let ids = Set(refIds)
        let incoming = kind == .read ? "read" : "delivered"
        for gid in groupID.map({ [$0] }) ?? groups.map(\.id) {
            guard var log = messages[gid] else { continue }
            var changed: [String] = []
            for i in log.indices
            where ids.contains(log[i].id) && log[i].direction == .outgoing && log[i].kind != .system {
                var r = log[i].receipts ?? [:]
                let merged = GroupMath.mergedReceipt(existing: r[peer], incoming: incoming)
                guard r[peer] != merged else { continue }
                r[peer] = merged
                log[i].receipts = r
                changed.append(log[i].id)
            }
            guard !changed.isEmpty else { continue }
            messages[gid] = log
            let snapshot = messages
            store.update { $0.messages = snapshot }
            for id in changed {
                blobProgress[id] = nil
                refreshGroupStatus(id, thread: gid)
            }
        }
    }

    /// SYNCED BURN in a group: the first member to read starts my copy's
    /// countdown (each reader's own timer started on their read-dwell).
    func applyGroupBurnStart(refIds: [String], from peer: String, groupID: String?) {
        let ids = Set(refIds)
        for gid in groupID.map({ [$0] }) ?? groups.map(\.id) {
            guard var log = messages[gid] else { continue }
            var changed = false
            for i in log.indices
            where ids.contains(log[i].id) && log[i].direction == .outgoing
                && log[i].burnAfter != nil && log[i].burnStartedAt == nil {
                log[i].burnStartedAt = Date()
                changed = true
            }
            guard changed else { continue }
            messages[gid] = log
            let snapshot = messages
            store.update { $0.messages = snapshot }
        }
    }
}
