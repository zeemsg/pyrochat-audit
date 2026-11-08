import Foundation
import LibSignalClient

/// My sender certificate for this login, and how long it may still be used.
struct SealedCertificate {
    let material: Sealed.Own
    /// Sealing stops here, a little before the certificate ends: the recipient judges it by
    /// the relay's clock when the message arrives there. On a clock that keeps counting while
    /// the phone sleeps.
    let until: ContinuousClock.Instant
}

/// One anonymous connection (docs/SEALED-SENDER.md §5.5): a socket that never logs in.
/// Sealed messages and prekey fetches go out on it, so the relay is not told they are mine.
@MainActor
final class AnonLink {
    let client: RelayClient
    var events: Task<Void, Never>?
    /// Waiting for the relay's `anon_ok`.
    var opening: CheckedContinuation<Bool, Never>?
    /// deliver id -> the message it carries, until the relay's verdict (or 15 s).
    var attempts: [String: ChatService.Outbound] = [:]
    /// deliver id -> a caller that takes the verdict itself (nil = sent, else the error code).
    var verdictWaiters: [String: CheckedContinuation<String?, Never>] = [:]
    /// Peers whose prekey bundle was asked for here and has not come back.
    var fetches: Set<String> = []
    /// Attachments still writing their chunks here.
    var uploads = 0
    /// Takes nothing new; closes once what is in flight has its verdict and what was asked
    /// has its answer (§5.2).
    var retiring = false
    var ended = false

    init(client: RelayClient) { self.client = client }
}

/// An attachment's chunks were going out on an anonymous connection, and it ended.
struct AnonLinkLost: Error, CustomStringConvertible {
    var description: String { "the private connection closed" }
}

/// Sealed sender (docs/SEALED-SENDER.md): the certificate, the anonymous connection, which
/// path a message takes, and what happens when the relay refuses one. State lives in
/// `ChatService`; the rules themselves are in `Sealed`.
extension ChatService {

    /// A `deliver` got no verdict: 15 s passed, or its connection ended first.
    static let noVerdict = "no_verdict"
    static let maxAnonReopens = 2
    static let verdictDeadline: Duration = .seconds(15)

    // MARK: login

    /// What `register` and `auth` offer the relay: the hash of my delivery key. nil when this
    /// build trusts no root for this relay, and then nothing about sealed sender is ever sent.
    func sealedDeliveryOffer() -> String? {
        guard !sealedRoots.isEmpty else { return nil }
        if store.blob.deliveryKey == nil {
            let key = Sealed.newDeliveryKey()
            store.update { $0.deliveryKey = key }
        }
        return store.blob.deliveryKey.map { Sealed.deliveryHash($0).base64EncodedString() }
    }

    /// `ready` arrived: take the certificate it carries, or note that this login has none.
    func sealedLoggedIn(_ ready: Wire.In) {
        anonFailed = false
        anonReopens = 0
        if ready.sealed == true, !sealedRoots.isEmpty {
            takeCertificate(ready)
        } else {
            dropCertificate()
        }
    }

    /// A certificate and binding from `ready` or `cert`. They are checked the way a recipient
    /// will check them; one that fails leaves sealed sender off rather than sending messages
    /// nobody could verify.
    func takeCertificate(_ f: Wire.In) {
        certRenewal?.cancel()
        certRenewal = nil
        guard !sealedRoots.isEmpty, let cert = f.cert, let bind = f.bind,
              let hash = Sealed.hashBytes(myHash), let identity = try? crypto.myIdentityPublic(),
              let own = try? Sealed.takeOwnCertificate(
                cert: cert, bind: bind, hash: hash, identityKey: identity,
                roots: sealedRoots, minKeyId: SealedTrust.minServerKeyId) else {
            dropCertificate()
            return
        }
        let life = Double(max(1, f.cert_ttl ?? 0))
        // nothing is sealed in the last tenth of its life, a minute at most
        let usable = life - min(60, life / 10)
        sealedOwn = SealedCertificate(material: own, until: ContinuousClock.now + .seconds(usable))
        // renewed at half its life, and asked for again every eighth of it until the relay answers
        certRenewal = Task { [weak self] in
            try? await Task.sleep(for: .seconds(life / 2))
            while !Task.isCancelled {
                guard let self, self.connState == .online else { return }
                try? await self.relay?.send(Wire.Out(t: "cert"))
                try? await Task.sleep(for: .seconds(life / 8))
            }
        }
        refreshSealedStatus()
    }

    private func dropCertificate() {
        certRenewal?.cancel()
        certRenewal = nil
        sealedOwn = nil
        refreshSealedStatus()
    }

    /// The main link is gone, or is being closed: an anonymous connection never outlives it.
    func sealedLinkDown() {
        dropCertificate()      // first: a connection closed below must not be opened again
        closeAnonLinks()
    }

    func refreshSealedStatus() {
        let now: SealedStatus = sealedOwn == nil ? .notOffered : (anonFailed ? .noPrivateConnection : .on)
        if now != sealedStatus { sealedStatus = now }
    }

    /// True while a message may be sealed: the relay offered it at this login, my certificate
    /// passed its own check and has not run out, and the anonymous connection can be had.
    var sealedUsable: Bool {
        guard let own = sealedOwn, !anonFailed, connState == .online else { return false }
        return ContinuousClock.now < own.until
    }

    // MARK: what I know about each peer (§5.1)

    func peerKey(_ peer: String) -> Sealed.PeerKey? { store.blob.peerKeys?[peer] }

    /// Store an entry; one that says nothing is not kept.
    func setPeerKey(_ entry: Sealed.PeerKey?, for peer: String) {
        let next = entry?.isEmpty == false ? entry : nil
        guard next != store.blob.peerKeys?[peer] else { return }
        store.update {
            var all = $0.peerKeys ?? [:]
            all[peer] = next
            $0.peerKeys = all.isEmpty ? nil : all
        }
    }

    /// Every payload received from a peer, on any path, and every sealed message that passed
    /// its checks.
    func learnKeys(from peer: String, payload: Wire.Payload, sealed: Bool) {
        guard !legacyWireOnly else { return }
        let before = peerKey(peer)
        var entry = before
        if sealed { entry = Sealed.sealedReceived(entry) }
        entry = Sealed.payloadReceived(entry, dk: payload.dk, dkh: payload.dkh)
        setPeerKey(entry, for: peer)
        // Their key just arrived: what could not go without it can go now. An attachment sent
        // before the first text was delivered (§5.2) sends itself, nobody has to tap [RETRY].
        if before?.dk == nil, entry?.dk != nil, connState == .online { retryRecentFailures() }
    }

    /// The delivery key a roster may carry for a member: my own for me, the one I hold for
    /// anyone else. Only while this login has sealed sender.
    func rosterDeliveryKey(for hash: String) -> String? {
        guard sealedOwn != nil else { return nil }
        if hash == myHash { return store.blob.deliveryKey?.base64EncodedString() }
        return peerKey(hash)?.dk?.base64EncodedString()
    }

    /// A delivery key that arrived in a group roster (§5.1: taken only when none is held).
    func learnRosterKey(_ dk: String?, for hash: String) {
        guard !legacyWireOnly, hash != myHash, dk != nil else { return }
        setPeerKey(Sealed.rosterKey(peerKey(hash), dk: dk), for: hash)
    }

    /// The path for a message to a peer I have a session with (§5.2).
    func sealedLane(for peer: String) -> Sealed.Lane {
        let lane = Sealed.route(peerKey(peer), offered: sealedUsable, hasSession: true)
        return lane == .fetch ? .legacy : lane
    }

    func peerName(_ hash: String) -> String {
        contacts.first { $0.hash == hash }?.name ?? "anon-" + String(hash.prefix(8))
    }

    // MARK: the anonymous connection (§5.5)

    /// The connection new work goes out on, opened on first need. Throws when there is none
    /// to be had: the main link is down, this login has no certificate, or two attempts to
    /// open one failed (then the legacy path is used until the next login).
    func ensureAnon(forLostWork: Bool = false) async throws -> AnonLink {
        if let link = anonLink, !link.ended { return link }
        guard !anonFailed, sealedOwn != nil, connState == .online else {
            throw ServiceError.relay("no_private_connection")
        }
        if let opening = anonOpening {
            let own = anonOpeningForLostWork
            do {
                return try await opening.value
            } catch {
                // The attempt this caller joined was the engine's own and it failed. A caller
                // with a message in hand makes the attempt that counts; anyone else takes the answer.
                if forLostWork || !own { throw error }
            }
            return try await ensureAnon(forLostWork: false)
        }
        if forLostWork {
            // counted here, where a connection is opened: one that died is counted once, however
            // many pieces of work noticed it
            guard anonReopens < Self.maxAnonReopens else { throw ServiceError.relay("no_private_connection") }
            anonReopens += 1
        }
        anonOpeningForLostWork = forLostWork
        let task = Task { [weak self] () throws -> AnonLink in
            guard let self else { throw ServiceError.relay("no_private_connection") }
            defer { self.anonOpening = nil }
            for _ in 0..<2 {
                guard self.connState == .online, self.sealedOwn != nil else { break }
                guard let link = await self.openAnonOnce() else { continue }
                // the main link dropped while this one was opening
                guard self.connState == .online, self.sealedOwn != nil else {
                    self.anonEnd(link)
                    break
                }
                self.anonLink = link
                if self.anonLostWork {
                    // what was in flight when the last one ended goes again now (§5.5)
                    self.anonLostWork = false
                    self.retryRecentFailures()
                }
                return link
            }
            // An attempt made only to send lost work again decides nothing: the next message
            // tries once more, and that one settles it.
            if !forLostWork, self.connState == .online, self.sealedOwn != nil {
                self.anonFailed = true
                self.refreshSealedStatus()
                if self.anonLostWork {
                    // no private connection until the next login: what was lost goes the old way
                    self.anonLostWork = false
                    self.retryRecentFailures()
                }
            }
            throw ServiceError.relay("no_private_connection")
        }
        anonOpening = task
        return try await task.value
    }

    /// One attempt: connect, send `anon`, wait up to 10 s for `anon_ok`. Its own client, so
    /// no TLS session or connection is shared with the logged-in socket.
    private func openAnonOnce() async -> AnonLink? {
        guard let url = anonRelayURLForTest ?? URL(string: store.blob.settings.relayURL) else { return nil }
        let client = RelayClient(url: url, socksProxy: store.blob.settings.socksProxy)
        let link = AnonLink(client: client)
        anonLinks.append(link)
        link.events = Task { [weak self, weak link] in
            for await event in client.events {
                guard let self, let link, !link.ended else { return }
                switch event {
                case .frame(let f):
                    // counted as it is reached: everything that arrived before it has been routed
                    if f.t == "pong" { client.pongRouted() }
                    if let waiting = link.opening {
                        link.opening = nil
                        waiting.resume(returning: f.t == "anon_ok")
                    } else {
                        await self.routeAnon(f, on: link)
                    }
                case .disconnected:
                    self.anonEnd(link)
                    return
                case .connected, .binary:
                    break
                }
            }
        }
        client.connect()
        let accepted: Bool = await withCheckedContinuation { cont in
            link.opening = cont
            Task { try? await client.send(Wire.Out(t: "anon")) }
            Task { [weak link] in
                try? await Task.sleep(for: .seconds(10))
                if let waiting = link?.opening {
                    link?.opening = nil
                    waiting.resume(returning: false)
                }
            }
        }
        guard accepted, !link.ended else {
            anonEnd(link)
            return nil
        }
        return link
    }

    /// The connection is gone or going: nothing still on it will be answered. What was in
    /// flight fails as a send does when the link drops, and is retried when a connection is back.
    func anonEnd(_ link: AnonLink) {
        guard !link.ended else { return }
        link.ended = true
        if anonLink === link { anonLink = nil }
        anonLinks.removeAll { $0 === link }
        link.events?.cancel()
        link.client.disconnect()
        if let waiting = link.opening {
            link.opening = nil
            waiting.resume(returning: false)
        }
        let lost = link.attempts
        link.attempts = [:]
        for (id, out) in lost {
            if let waiter = link.verdictWaiters.removeValue(forKey: id) {
                waiter.resume(returning: Self.noVerdict)
            } else {
                relayRefused(code: Self.noVerdict, ref: out.callerId)
            }
        }
        let lostNow = !lost.isEmpty || !link.fetches.isEmpty
        // a bundle asked for here will never come: say so now, not when the fetch times out
        for peer in link.fetches { failBundleFetch(for: peer, with: URLError(.networkConnectionLost)) }
        link.fetches = []
        if lostNow { lostWithConnection() }
    }

    /// Work was lost with an anonymous connection (the rows it belongs to are FAILED by now)
    /// while the main link is up: open another and send it again, so nobody has to do anything
    /// (§5.5). `ensureAnon` opens two in a row at most for this with no verdict in between: a
    /// path that keeps killing the connection must not become a loop, and then the work waits
    /// for the next message or the next login.
    func lostWithConnection() {
        anonLostWork = true
        guard !anonFailed, sealedOwn != nil, connState == .online else { return }
        Task { [weak self] in
            guard let self, (try? await self.ensureAnon(forLostWork: true)) != nil else { return }
            if self.anonLostWork {
                self.anonLostWork = false
                self.retryRecentFailures()
            }
        }
    }

    func closeAnonLinks() {
        anonOpening?.cancel()
        for link in anonLinks { anonEnd(link) }
    }

    /// After a fallback to the legacy path (§5.2) new work goes to a fresh connection, so the
    /// refused attempt and the legacy send that follows are not on record against the socket
    /// used for other people. What is in flight on this one still gets its verdict.
    func anonRetire(_ link: AnonLink) {
        if anonLink === link { anonLink = nil }
        link.retiring = true
        anonSettle(link)
    }

    func anonSettle(_ link: AnonLink) {
        if link.retiring, link.attempts.isEmpty, link.uploads == 0, link.fetches.isEmpty { anonEnd(link) }
    }

    /// Kept alive with the main link's keepalive: the relay closes a silent socket after 90 s.
    func pingAnonLinks() {
        for link in anonLinks where !link.ended && link.opening == nil {
            let client = link.client
            Task { try? await client.send(Wire.Out(t: "ping")) }
        }
    }

    private func armVerdictDeadline(_ id: String, on link: AnonLink) {
        Task { [weak self, weak link] in
            try? await Task.sleep(for: Self.verdictDeadline)
            guard let self, let link, !link.ended, link.attempts[id] != nil else { return }
            // no verdict: this connection is not answering. Everything on it fails, and the
            // retry opens another.
            self.anonEnd(link)
        }
    }

    /// Tests only: hand a frame to the anonymous receive path as if the relay had sent it,
    /// and let what it started finish.
    func routeAnonForTest(_ f: Wire.In, on link: AnonLink) async {
        await routeAnon(f, on: link)
        for _ in 0..<20 { await Task.yield() }
    }

    private func routeAnon(_ f: Wire.In, on link: AnonLink) async {
        switch f.t {
        case "sent":
            guard let id = f.id, let out = link.attempts.removeValue(forKey: id) else { break }
            anonReopens = 0     // this path works
            if let waiter = link.verdictWaiters.removeValue(forKey: id) {
                waiter.resume(returning: nil)
            } else {
                relaySent(out.callerId)
            }
        case "prekey_bundle":
            guard let user = f.user else { break }
            // every bundle reply here says whether that account can receive sealed messages (§5.1)
            if !legacyWireOnly {
                setPeerKey(Sealed.bundleSeen(peerKey(user), sealed: f.sealed == true), for: user)
            }
            link.fetches.remove(user)
            answerBundleFetch(f, for: user)
        case "error":
            if let ref = f.ref, let out = link.attempts.removeValue(forKey: ref) {
                if let waiter = link.verdictWaiters.removeValue(forKey: ref) {
                    waiter.resume(returning: f.code ?? "refused")
                } else {
                    // DEADLOCK GUARD, as for healSession: a refusal may wait for a prekey
                    // bundle, and that bundle arrives through this very loop. Handle it in
                    // its own task, never inline.
                    let code = f.code ?? "refused"
                    Task { [weak self] in await self?.sealedRefused(out, code: code, on: link) }
                }
            } else if let ref = f.ref, uploading.contains(ref) {
                uploadRefused[ref] = f.code ?? "refused"     // a chunk was refused: the upload ends
            } else if let user = f.user {
                link.fetches.remove(user)
                answerBundleFetch(f, for: user)      // a refused prekey fetch names the account asked for
            }
        default:
            break
        }
        anonSettle(link)
    }

    // MARK: sending (§5.2, §5.3)

    /// Write a sealed message to the anonymous connection. With no such connection to be had
    /// the message is encrypted again for the legacy path; the frame actually written is returned.
    func dispatchSealed(_ out: Outbound, legacyVia client: RelayClient?) async throws -> Outbound {
        let link: AnonLink
        do {
            link = try await ensureAnon()
        } catch {
            let again = try prepare(out.payload, to: out.peer, kind: out.kind, ttl: out.ttl,
                                    id: out.callerId, lane: .legacy, attempt: out.attempt + 1)
            recapture(again)
            try await (client ?? relay)?.send(again.frame)
            return again
        }
        guard let id = out.frame.id else { return out }
        link.attempts[id] = out
        armVerdictDeadline(id, on: link)
        do {
            try await link.client.send(out.frame)
        } catch {
            link.attempts[id] = nil       // the caller hears of this one itself
            anonEnd(link)
            throw error
        }
        return out
    }

    enum Verdict {
        /// Taken by the relay, on this connection, under this id.
        case sent(AnonLink, String)
        case refused(String, AnonLink?)
    }

    /// The same write, for a caller that needs the verdict before it goes on: an attachment's
    /// manifest, whose chunks follow only once the relay has taken it (§5.6).
    func dispatchForVerdict(_ out: Outbound) async -> Verdict {
        guard let id = out.frame.id, let link = try? await ensureAnon() else {
            return .refused("no_private_connection", nil)
        }
        link.attempts[id] = out
        armVerdictDeadline(id, on: link)
        let code: String? = await withCheckedContinuation { cont in
            link.verdictWaiters[id] = cont
            Task { [weak self, weak link] in
                do {
                    try await link?.client.send(out.frame)
                } catch {
                    if let link { self?.anonEnd(link) }      // answers the waiter with noVerdict
                }
            }
        }
        if let code { return .refused(code, link) }
        return .sent(link, id)
    }

    /// The relay refused a sealed message: one step down, never back up (§5.2).
    private func sealedRefused(_ out: Outbound, code: String, on link: AnonLink) async {
        switch code {
        case "denied":
            await keyDenied(out)
            await reroute(out, lane: nil)
        case "unsupported":                                   // an old build has the account
            setPeerKey(Sealed.unsupported(peerKey(out.peer)), for: out.peer)
            anonRetire(link)
            await reroute(out, lane: nil)
        case "intro_full":
            anonRetire(link)
            await reroute(out, lane: .legacy)
        case "intro_limit":
            lastError = out.kind == "blob"
                ? Sealed.firstContactRefusal(name: peerName(out.peer))
                : Sealed.firstContactTooLong(name: peerName(out.peer))
            relayRefused(code: code, ref: out.callerId)
        default:
            lastError = code
            relayRefused(code: code, ref: out.callerId)
        }
    }

    /// `denied`: the key that attempt carried is no longer theirs. Forget it and look the
    /// peer up again; a changed identity key starts a new session, as it does today.
    func keyDenied(_ out: Outbound) async {
        if let dropped = Sealed.denied(peerKey(out.peer), access: out.access) {
            setPeerKey(dropped, for: out.peer)
            await refreshFromBundle(out.peer)
        } else {
            await bundleRefresh[out.peer]?.value      // another refusal is already looking them up
        }
    }

    private func reroute(_ out: Outbound, lane: Sealed.Lane?) async {
        guard out.attempt < Sealed.maxAttempts else {
            relayRefused(code: Self.noVerdict, ref: out.callerId)
            return
        }
        do {
            if !crypto.hasSession(with: out.peer) {
                try await fetchAndProcessBundle(for: out.peer)
            }
            let next = try prepare(out.payload, to: out.peer, kind: out.kind, ttl: out.ttl,
                                   id: out.callerId, lane: lane, attempt: out.attempt + 1)
            recapture(next)
            _ = try await dispatch(next)
        } catch {
            relayRefused(code: Self.noVerdict, ref: out.callerId)
        }
    }

    /// One lookup per peer at a time: several messages refused together share it, so one
    /// bundle never starts two sessions.
    private func refreshFromBundle(_ peer: String) async {
        if let running = bundleRefresh[peer] {
            await running.value
            return
        }
        let task = Task { [weak self] in
            guard let self else { return }
            defer { self.bundleRefresh[peer] = nil }
            guard let bundle = try? await self.fetchBundle(for: peer), bundle.t == "prekey_bundle",
                  let key = bundle.identity_key.flatMap({ Data(base64Encoded: $0) }) else { return }
            if self.crypto.hasSession(with: peer), self.crypto.knownIdentity(of: peer) == key { return }
            try? self.crypto.processBundle(bundle, for: peer)
        }
        bundleRefresh[peer] = task
        await task.value
    }

    /// A frame written in place of an earlier one for the same message replaces it on the
    /// details sheet (frames kept there never include `access`).
    func recapture(_ out: Outbound) {
        guard let row = capturedRows[out.callerId], let frame = CapturedFrame(sending: out.frame) else { return }
        frameCapture.record(frame, for: row)
    }
}
