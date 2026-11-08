import SwiftUI
import UIKit

/// Pure, testable scroll/window math.
enum TerminalLogMath {
    static let initialWindow = 60      // rows rendered on open
    static let page = 40               // rows added per history page
    static let trimThreshold = 120     // shrink window back once at bottom
    static let bottomSlack: CGFloat = 40   // pts from bottom that still count as "at bottom"
    static let prefetchViewports: CGFloat = 1.5  // page when the top is within this many viewports

    /// Whether the history trigger — whose bottom edge sits `topEdge` pts
    /// below the viewport's top (negative = above it) — is close enough to
    /// page in the next window. A viewport that has not been measured yet
    /// (0) is treated as a 300 pt one so a first flick still pages.
    static func nearTop(topEdge: CGFloat, viewportHeight: CGFloat) -> Bool {
        topEdge > -prefetchViewports * max(viewportHeight, 300)
    }

    /// Window growth when the user nears the top. Caller only invokes while
    /// current < total, so this never shrinks.
    static func grown(_ current: Int, total: Int) -> Int {
        min(current + page, total)
    }

    /// Window adjustment when messages append: while the reader is up in
    /// history the window must widen so `suffix(windowCount)` keeps the same
    /// start row (otherwise rows fall off the top and the viewport shifts).
    static func appended(_ current: Int, appended: Int, atBottom: Bool) -> Int {
        atBottom ? current : current + appended
    }

    /// When the scrollPosition anchor row was burned, pick the nearest
    /// surviving neighbor (prefer the next-newer row) so maintenance has a
    /// row to hold steady.
    static func nearestSurvivor(of id: String, in ordered: [String],
                                surviving: Set<String>) -> String? {
        guard let i = ordered.firstIndex(of: id) else {
            return ordered.last(where: surviving.contains)
        }
        if i + 1 < ordered.count {
            for j in (i + 1)..<ordered.count where surviving.contains(ordered[j]) {
                return ordered[j]
            }
        }
        for j in stride(from: i - 1, through: 0, by: -1) where surviving.contains(ordered[j]) {
            return ordered[j]
        }
        return nil
    }
}

/// Cheap Equatable snapshot of a rendered row used by onChange. Never
/// compares voiceData/attachment bytes (that would memcmp megabytes per body
/// evaluation).
struct RowStamp: Equatable {
    var id: String
    var status: ChatMessage.Status
    var hasVoice: Bool
    var hasAttachment: Bool
    var burning: Bool
}

struct LogDigest: Equatable {
    var count: Int
    var lastID: String?
    var lastIsOutgoing: Bool
    var tail: [RowStamp]          // stamps for the rendered window only
    var footerCount: Int          // rx-transfer progress lines for this thread
}

/// Distance of the bottom sentinel's top edge from the viewport's top, in the
/// "chatlog" coordinate space. min() so a stray default doesn't win.
private struct BottomEdgeKey: PreferenceKey {
    static var defaultValue: CGFloat = .greatestFiniteMagnitude
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = min(value, nextValue())
    }
}

/// Distance of the history trigger's bottom edge from the viewport's top,
/// in the "chatlog" coordinate space (negative while it sits above the
/// viewport). max() so a stray default doesn't win; the default is "very
/// far above" so an absent trigger (history exhausted) never pages.
private struct TopEdgeKey: PreferenceKey {
    static var defaultValue: CGFloat = -.greatestFiniteMagnitude
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

/// Terminal-grade message log. Owns ALL scroll state so scroll-tick state
/// churn never re-evaluates ChatView's body (composer, toolbar, alerts).
struct TerminalLogView<Row: View, Footer: View>: View {
    @ObservedObject var service: ChatService
    /// Thread key: a contact hash (1:1) or a group id.
    let thread: String
    /// Header line while the thread has no rows yet.
    let emptyHeader: String
    let row: (ChatMessage) -> Row
    /// Rendered after the rows, inside the scroll content (rx-progress lines).
    let footer: () -> Footer
    /// Search: set to a message id to widen the window and scroll there.
    var jumpTo: Binding<String?>? = nil

    /// 1:1 conversation (the original entry point — behaviour unchanged).
    init(service: ChatService, contact: Contact,
         @ViewBuilder row: @escaping (ChatMessage) -> Row,
         @ViewBuilder footer: @escaping () -> Footer,
         jumpTo: Binding<String?>? = nil) {
        self.init(service: service, thread: contact.hash,
                  emptyHeader: "── encrypted channel with <\(contact.name)> ──",
                  row: row, footer: footer, jumpTo: jumpTo)
    }

    /// Any thread key (group channels use the group id).
    init(service: ChatService, thread: String, emptyHeader: String,
         @ViewBuilder row: @escaping (ChatMessage) -> Row,
         @ViewBuilder footer: @escaping () -> Footer,
         jumpTo: Binding<String?>? = nil) {
        _service = ObservedObject(wrappedValue: service)
        self.thread = thread
        self.emptyHeader = emptyHeader
        self.row = row
        self.footer = footer
        self.jumpTo = jumpTo
    }

    // View-local, never persisted.
    @State private var scrollTargetID: String?     // .scrollPosition binding
    @State private var isAtBottom = true
    @State private var unseen = 0
    @State private var windowCount = TerminalLogMath.initialWindow
    @State private var viewportHeight: CGFloat = 0
    @State private var growArmed = true
    @State private var topEdge: CGFloat = -.greatestFiniteMagnitude
    @State private var prependAnchor = PrependAnchor()
    @State private var lastIDs: [String] = []      // window ids from previous digest
    @State private var visibilityEpoch = 0         // bumped on re-foreground
    @Environment(\.scenePhase) private var scenePhase

    private static var bottomID: String { "__bottom__" }

    private var settings: AppSettings { service.store.blob.settings }
    private var log: [ChatMessage] { service.messages[thread] ?? [] }
    private var window: ArraySlice<ChatMessage> { log.suffix(windowCount) }
    private var historyExhausted: Bool { windowCount >= log.count }

    private var digest: LogDigest {
        LogDigest(count: log.count,
                  lastID: log.last?.id,
                  lastIsOutgoing: log.last?.direction == .outgoing,
                  tail: window.map { RowStamp(id: $0.id, status: $0.status,
                                              hasVoice: $0.kind == .voice,
                                              hasAttachment: $0.attachmentId != nil,
                                              burning: $0.burnDeadline != nil) },
                  footerCount: service.rxTransfers.values
                      .filter { $0.thread == thread }.count)
    }

    var body: some View {
        GeometryReader { outer in
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 0) {
                        // A plain VStack, deliberately: the rendered window is
                        // capped at trimThreshold (120) rows, so laziness bought
                        // nothing — and a LazyVStack loses the viewport when a
                        // history page is prepended above it (the anchor row is
                        // re-placed against estimated heights, sometimes onto
                        // rows it has not materialised: the log went black
                        // until the next swipe forced a relayout).
                        VStack(alignment: .leading, spacing: 6) {
                            if historyExhausted { header } else { historyTrigger }
                            ForEach(window) { msg in
                                row(msg)
                                    .id(msg.id)
                                    .modifier(VisibilityReporter(
                                        enabled: service.needsVisibilityReport(msg),
                                        viewportHeight: viewportHeight,
                                        epoch: visibilityEpoch,
                                        onVisible: {
                                            service.noteRowVisible(messageID: msg.id,
                                                                   thread: thread)
                                        }))
                            }
                            footer()
                        }
                        .scrollTargetLayout()
                        .padding(.horizontal, 12)

                        // Bottom sentinel: outside the row stack, measured even
                        // when the user is deep in history.
                        Color.clear
                            .frame(height: 1)
                            .background(GeometryReader { geo in
                                Color.clear.preference(
                                    key: BottomEdgeKey.self,
                                    value: geo.frame(in: .named("chatlog")).minY)
                            })
                            .id(Self.bottomID)
                    }
                    // Terminal semantics: short logs start at the TOP of the
                    // screen; once content overflows, the log pins to bottom.
                    .frame(minHeight: outer.size.height, alignment: .top)
                    .background(ScrollViewProbe(anchor: prependAnchor))
                }
                .coordinateSpace(name: "chatlog")
                // The binding serves burn-sweep survivor picking; it is NOT
                // what holds the viewport across a history prepend — SwiftUI
                // keeps the offset, not the row (see PrependAnchor).
                .scrollPosition(id: $scrollTargetID)
                .defaultScrollAnchor(.bottom)            // flash-free open at bottom
                .scrollDismissesKeyboard(.interactively)
                .onPreferenceChange(BottomEdgeKey.self) { minY in
                    let atBottom = minY <= viewportHeight + TerminalLogMath.bottomSlack
                    guard atBottom != isAtBottom else { return }
                    isAtBottom = atBottom
                    if atBottom {
                        unseen = 0
                        trimWindowIfBloated(proxy)
                    }
                }
                .onPreferenceChange(TopEdgeKey.self) { maxY in
                    topEdge = maxY
                    growIfNearTop()
                }
                .onChange(of: digest) { old, new in
                    handleLogChange(old: old, new: new, proxy: proxy)
                }
                .onChange(of: outer.size.height) { old, new in
                    viewportHeight = new
                    // Viewport shrank (keyboard up, composer grew a line):
                    // keep the last line visible. Growth (keyboard dismissed)
                    // needs nothing — offset clamping reveals the bottom.
                    if new < old && isAtBottom {
                        scrollToBottom(proxy, animated: false)
                    }
                }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { visibilityEpoch += 1 }
                }
                .overlay(alignment: .bottomTrailing) {
                    if unseen > 0 && !isAtBottom { newMailChip(proxy) }
                }
                .onAppear {
                    viewportHeight = outer.size.height
                    lastIDs = window.map(\.id)
                    // defaultScrollAnchor(.bottom) positions on the FIRST layout;
                    // rows whose height settles a beat later (Dynamic Type metrics,
                    // a waveform decoding, the keyboard-driven viewport) used to
                    // leave a long log short of the bottom. Re-pin after layout
                    // settles, as long as the reader hasn't scrolled up meanwhile.
                    for delay in [0.0, 0.25, 0.8] {
                        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                            if isAtBottom { scrollToBottom(proxy, animated: false) }
                        }
                    }
                }
                .onChange(of: jumpTo?.wrappedValue) { _, target in
                    guard let target else { return }
                    // widen the window until the target row is rendered
                    if let idx = log.firstIndex(where: { $0.id == target }) {
                        let needed = log.count - idx
                        if needed > windowCount {
                            var t = Transaction(); t.disablesAnimations = true
                            withTransaction(t) { windowCount = needed + 5 }
                        }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                            withAnimation(.easeOut(duration: 0.2)) {
                                proxy.scrollTo(target, anchor: .center)
                            }
                            jumpTo?.wrappedValue = nil
                        }
                    } else {
                        jumpTo?.wrappedValue = nil
                    }
                }
            }
        }
    }

    // MARK: log-change routing

    private func handleLogChange(old: LogDigest, new: LogDigest, proxy: ScrollViewProxy) {
        defer { lastIDs = window.map(\.id) }

        if new.count == 0 {                                   // whole thread burned
            windowCount = TerminalLogMath.initialWindow
            unseen = 0
            scrollTargetID = nil
            return
        }

        if new.count > old.count && new.lastID != old.lastID { // append
            let n = new.count - old.count
            if new.lastIsOutgoing || isAtBottom {
                // Terminal follow: own input always echoes to the prompt line;
                // tailing readers stay tailing.
                unseen = 0
                scrollToBottom(proxy, animated: false)
            } else {
                windowCount = TerminalLogMath.appended(windowCount, appended: n,
                                                       atBottom: false)
                unseen += n
            }
            return
        }

        if new.count < old.count {                            // burn sweep removal
            let surviving = Set(log.map(\.id))
            if let target = scrollTargetID, !surviving.contains(target) {
                scrollTargetID = TerminalLogMath.nearestSurvivor(
                    of: target, in: lastIDs, surviving: surviving)
            }
            windowCount = min(windowCount, max(new.count, TerminalLogMath.initialWindow))
            if isAtBottom { scrollToBottom(proxy, animated: false) }
            return
        }

        if (new.tail != old.tail || new.footerCount != old.footerCount) && isAtBottom {
            // In-place mutation near the tail (status word, blob assembled,
            // progress line appear/vanish): row height may have changed — re-pin.
            scrollToBottom(proxy, animated: false)
        }
    }

    // MARK: scrolling

    private func scrollToBottom(_ proxy: ScrollViewProxy, animated: Bool) {
        var t = Transaction()
        if animated { t.animation = .easeOut(duration: 0.15) }
        else { t.disablesAnimations = true }
        withTransaction(t) { proxy.scrollTo(Self.bottomID, anchor: .bottom) }
    }

    private func trimWindowIfBloated(_ proxy: ScrollViewProxy) {
        guard windowCount > TerminalLogMath.trimThreshold else { return }
        var t = Transaction(); t.disablesAnimations = true
        withTransaction(t) {
            windowCount = TerminalLogMath.initialWindow
            proxy.scrollTo(Self.bottomID, anchor: .bottom)
        }
    }

    // MARK: subviews

    /// Shown while older pages remain. Measured, never lazy: it reports its
    /// edge through TopEdgeKey and `growIfNearTop` pages once the top of the
    /// window comes within reach of the viewport — so paging keeps working
    /// while the trigger stays on screen (an onAppear trigger fired once and
    /// then never again until it had scrolled away and back).
    private var historyTrigger: some View {
        Color.clear
            .frame(height: 1)
            .background(GeometryReader { geo in
                Color.clear.preference(key: TopEdgeKey.self,
                                       value: geo.frame(in: .named("chatlog")).maxY)
            })
    }

    /// Widen the window by one page when the reader nears the top. The
    /// viewport must not move: `PrependAnchor` shifts the UIKit content
    /// offset by exactly the prepended height in the same layout pass
    /// (SwiftUI's `.scrollPosition(id:)` keeps the offset, not the row —
    /// see `PrependAnchor`).
    private func growIfNearTop() {
        guard growArmed, !historyExhausted,
              TerminalLogMath.nearTop(topEdge: topEdge, viewportHeight: viewportHeight)
        else { return }
        growArmed = false
        let grown = TerminalLogMath.grown(windowCount, total: log.count)
        #if DEBUG
        NSLog("PYRO page: window %d -> %d topEdge=%.0f scrollTargetID=%@",
              windowCount, grown, topEdge, scrollTargetID ?? "nil")
        #endif
        prependAnchor.armForPrepend()
        var t = Transaction(); t.disablesAnimations = true
        withTransaction(t) { windowCount = grown }
        // Re-arm after the prepend settles; a flick that outran one page
        // (sentinel still within reach) pages again from the fresh edge.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
            growArmed = true
            growIfNearTop()
        }
    }

    private var header: some View {
        Text(log.isEmpty ? emptyHeader : "── beginning of channel ──")
            .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
    }

    private func newMailChip(_ proxy: ScrollViewProxy) -> some View {
        Button {
            unseen = 0
            scrollToBottom(proxy, animated: true)
        } label: {
            Text("[\u{25BC} \(unseen) NEW]")
                .font(Theme.mono(11, weight: .bold))
                .foregroundStyle(Theme.fg(settings))
                .padding(.horizontal, 8).padding(.vertical, 5)
                .background(Theme.panel)
                .border(Theme.fgDim(settings), width: 1)
        }
        .padding(.trailing, 12).padding(.bottom, 10)
    }
}

/// Keeps the viewport still while a history page is prepended.
///
/// SwiftUI's ScrollView carries the content OFFSET (points from the top)
/// across a content change, not the row under the reader's finger — so a
/// 40-row page landing above the viewport threw the reader a page into the
/// past (and, with a lazy stack, onto rows it had not drawn: the log went
/// black). `.scrollPosition(id:)` did not hold the row either. UIKit is
/// authoritative here: the hosting UIScrollView's `contentSize` is observed
/// synchronously (KVO fires inside SwiftUI's layout commit, before the
/// frame is drawn) and the offset is moved by exactly the prepended height,
/// so the rows under the finger do not move. Fails open: no scroll view
/// found = SwiftUI's own behaviour.
final class PrependAnchor {
    private weak var scrollView: UIScrollView?
    private var observation: NSKeyValueObservation?
    private var pending: (height: CGFloat, offset: CGFloat)?

    func attach(_ sv: UIScrollView) {
        guard sv !== scrollView else { return }
        scrollView = sv
        observation = sv.observe(\.contentSize, options: [.new]) { [weak self] sv, _ in
            self?.contentSizeChanged(sv)
        }
    }

    /// Call immediately before the window grows at the top.
    func armForPrepend() {
        guard let sv = scrollView else { return }
        pending = (sv.contentSize.height, sv.contentOffset.y)
        // the layout lands on the next runloop pass; never hold a stale arm
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            self?.pending = nil
        }
    }

    private func contentSizeChanged(_ sv: UIScrollView) {
        guard let p = pending else { return }
        let delta = sv.contentSize.height - p.height
        guard delta > 0 else { return }          // a shrink or no-op is not our prepend
        pending = nil
        sv.contentOffset.y = p.offset + delta
        #if DEBUG
        NSLog("PYRO prepend: +%.0f pt, offset %.0f -> %.0f", delta, p.offset, p.offset + delta)
        #endif
    }
}

/// Finds the UIScrollView hosting the SwiftUI ScrollView's content (walks
/// up from a hidden probe placed in the content's background) and hands it
/// to `PrependAnchor`.
struct ScrollViewProbe: UIViewRepresentable {
    let anchor: PrependAnchor

    func makeUIView(context: Context) -> ScrollProbeView {
        let v = ScrollProbeView()
        v.isHidden = true
        v.isUserInteractionEnabled = false
        v.anchor = anchor
        return v
    }

    func updateUIView(_ v: ScrollProbeView, context: Context) {
        v.anchor = anchor
        v.hook()
    }
}

final class ScrollProbeView: UIView {
    var anchor: PrependAnchor?

    override func didMoveToWindow() {
        super.didMoveToWindow()
        hook()
    }

    func hook() {
        var v: UIView? = superview
        while let cur = v, !(cur is UIScrollView) { v = cur.superview }
        if let sv = v as? UIScrollView { anchor?.attach(sv) }
    }
}

/// Fires `onVisible` once after the row has been continuously inside the
/// viewport for `dwell` seconds while the app is active. Attached only to
/// rows that still need a report (`enabled == false` is a pass-through), so
/// the geometry churn is bounded by the unreported-row count.
struct VisibilityReporter: ViewModifier {
    let enabled: Bool
    let viewportHeight: CGFloat
    let epoch: Int
    let onVisible: () -> Void
    static let dwell: TimeInterval = 0.5

    @State private var pending: Task<Void, Never>?

    func body(content: Content) -> some View {
        if enabled {
            content.background(
                GeometryReader { geo in
                    let f = geo.frame(in: .named("chatlog"))
                    let visible = f.maxY > 0 && f.minY < viewportHeight
                    Color.clear
                        .onChange(of: visible, initial: true) { _, nowVisible in
                            arm(nowVisible)
                        }
                        .onChange(of: epoch) { _, _ in       // re-foreground re-check
                            arm(visible)
                        }
                        .onDisappear { pending?.cancel() }
                }
            )
        } else {
            content
        }
    }

    private func arm(_ visible: Bool) {
        pending?.cancel()
        guard visible else { return }
        pending = Task { @MainActor in
            try? await Task.sleep(for: .seconds(Self.dwell))
            guard !Task.isCancelled,
                  UIApplication.shared.applicationState == .active else { return }
            onVisible()
        }
    }
}
