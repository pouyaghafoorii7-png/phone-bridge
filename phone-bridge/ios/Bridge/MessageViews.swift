import SwiftUI

// MARK: - Conversation list

/// Looks like iOS 26 Messages: Edit and the filter menu at the top, search and compose at the bottom.
struct ConversationsView: View {
    @EnvironmentObject var store: BridgeStore
    @State private var query = ""
    @State private var path: [String]
    @State private var composing = false
    @State private var newNumber = ""
    @State private var unreadOnly = false
    @State private var editMode: EditMode = .inactive
    @State private var selection = Set<String>()

    init() {
        let open = UserDefaults.standard.string(forKey: "open")
        _path = State(initialValue: open.map { [$0] } ?? [])
        UserDefaults.standard.removeObject(forKey: "open") // opened once (from a notification)
    }

    private var list: [Conversation] {
        var all = store.conversations
        if unreadOnly { all = all.filter(\.unread) }
        guard !query.isEmpty else { return all }
        return all.filter {
            $0.title.localizedCaseInsensitiveContains(query) || $0.peer.contains(query) ||
            $0.last.body.localizedCaseInsensitiveContains(query)
        }
    }

    var body: some View {
        NavigationStack(path: $path) {
            List(selection: $selection) {
                if !store.androidOnline {
                    Text("Android phone is offline. New messages arrive when it connects.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                        .multilineTextAlignment(.center)
                        .listRowSeparator(.hidden)
                        .selectionDisabled()
                }
                ForEach(list) { c in
                    ZStack {
                        // The link without its own chevron: the row draws the chevron next to the time.
                        NavigationLink(value: c.id) { EmptyView() }.opacity(0)
                        ConversationRow(c: c)
                    }
                    .tag(c.id)
                    .listRowInsets(EdgeInsets(top: 10, leading: 4, bottom: 10, trailing: 16))
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) { store.deleteThread(c.id) } label: {
                            Label("Delete", systemImage: "trash.fill")
                        }
                    }
                    .swipeActions(edge: .leading) {
                        if c.unread {
                            Button { store.markRead(c.id) } label: { Label("Read", systemImage: "message.fill") }
                                .tint(.blue)
                        }
                    }
                }
            }
            .listStyle(.plain)
            .environment(\.editMode, $editMode)
            .overlay {
                if list.isEmpty && query.isEmpty {
                    EmptyState(icon: "message", title: unreadOnly ? "No Unread Messages" : "No Messages")
                }
            }
            .searchable(text: $query)
            .navigationTitle(unreadOnly ? "Unread" : "Messages")
            .navigationDestination(for: String.self) { key in
                ChatView(threadKey: key, peer: store.conversations.first { $0.id == key }?.peer ?? pendingPeer ?? key)
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(editMode.isEditing ? "Done" : "Edit") {
                        withAnimation {
                            editMode = editMode.isEditing ? .inactive : .active
                            selection = []
                        }
                    }
                    .fontWeight(editMode.isEditing ? .semibold : .regular)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Picker("Filter", selection: $unreadOnly) {
                            Label("All Messages", systemImage: "message").tag(false)
                            Label("Unread", systemImage: "message.badge").tag(true)
                        }
                        Divider()
                        PartMenuItems(current: .messages)
                    } label: {
                        Image(systemName: "line.3.horizontal.decrease")
                    }
                    .accessibilityLabel("Filter and More")
                }
            }
            .modifier(MessagesBottomBar(editing: editMode.isEditing, selected: selection.count,
                                        compose: { composing = true },
                                        delete: {
                                            selection.forEach { store.deleteThread($0) }
                                            selection = []
                                            withAnimation { editMode = .inactive }
                                        },
                                        readAll: {
                                            store.conversations.filter(\.unread).forEach { store.markRead($0.id) }
                                            withAnimation { editMode = .inactive }
                                        }))
            .alert("New Message", isPresented: $composing) {
                TextField("Phone number", text: $newNumber).keyboardType(.phonePad)
                Button("Cancel", role: .cancel) { newNumber = "" }
                Button("Next") {
                    let n = newNumber.trimmingCharacters(in: .whitespaces)
                    newNumber = ""
                    if !n.isEmpty { pendingPeer = n; path.append(phoneKey(n)) }
                }
            }
        }
        .environment(\.pendingPeer, pendingPeer)
    }

    @State private var pendingPeer: String?
}

/// Bottom of the Messages list. iOS 26: the search field and the compose button float at the bottom,
/// like the system Messages app. Before iOS 26: compose at the top right, search under the title.
private struct MessagesBottomBar: ViewModifier {
    let editing: Bool
    let selected: Int
    let compose: () -> Void
    let delete: () -> Void
    let readAll: () -> Void

    func body(content: Content) -> some View {
        if editing {
            content.toolbar {
                ToolbarItem(placement: .bottomBar) { Button("Read All", action: readAll) }
                ToolbarItem(placement: .bottomBar) { Spacer() }
                ToolbarItem(placement: .bottomBar) {
                    Button("Delete", role: .destructive, action: delete).disabled(selected == 0)
                }
            }
        } else {
            #if compiler(>=6.2)
            if #available(iOS 26.0, *) {
                content.toolbar {
                    DefaultToolbarItem(kind: .search, placement: .bottomBar)
                    ToolbarSpacer(.fixed, placement: .bottomBar)
                    ToolbarItem(placement: .bottomBar) {
                        Button(action: compose) { Image(systemName: "square.and.pencil") }
                            .accessibilityLabel("New Message")
                    }
                }
            } else {
                legacy(content)
            }
            #else
            legacy(content)
            #endif
        }
    }

    private func legacy(_ content: Content) -> some View {
        content.toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(action: compose) { Image(systemName: "square.and.pencil") }
                    .accessibilityLabel("New Message")
            }
        }
    }
}

private struct PendingPeerKey: EnvironmentKey { static let defaultValue: String? = nil }
extension EnvironmentValues {
    /// Number typed in "New Message" for a thread that has no messages yet.
    var pendingPeer: String? {
        get { self[PendingPeerKey.self] }
        set { self[PendingPeerKey.self] = newValue }
    }
}

struct ConversationRow: View {
    let c: Conversation

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Circle()
                .fill(Color.blue)
                .frame(width: 10, height: 10)
                .opacity(c.unread ? 1 : 0)
                .padding(.top, 21)
            AvatarView(name: c.title, size: 52)
            VStack(alignment: .leading, spacing: 3) {
                HStack(alignment: .firstTextBaseline) {
                    Text(c.title)
                        .font(.headline)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    HStack(spacing: 6) {
                        Text(listTime(c.last.date))
                        Image(systemName: "chevron.right")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .layoutPriority(1)
                }
                Text(c.last.body)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .multilineTextAlignment(isRTL(c.last.body) ? .trailing : .leading)
                    .frame(maxWidth: .infinity, alignment: isRTL(c.last.body) ? .trailing : .leading)
            }
        }
    }
}

struct ConnectionBadge: View {
    @EnvironmentObject var store: BridgeStore

    var body: some View {
        let ok = store.androidOnline
        Image(systemName: ok ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
            .foregroundStyle(ok ? Color.green : (store.relayConnected ? Color.orange : Color.red))
            .accessibilityLabel(ok ? "Android connected" : "Android offline")
    }
}

// MARK: - Chat

struct ChatView: View {
    @EnvironmentObject var store: BridgeStore
    @Environment(\.pendingPeer) private var pendingPeer
    let threadKey: String
    let peer: String
    @State private var draft = ""
    @FocusState private var focused: Bool
    /// A line picked by hand for this chat.
    @State private var lineChoice: Int?

    /// With two SIMs: the line you picked, else the one this chat already uses.
    private var line: Int? { store.hasLines ? (lineChoice ?? store.lineForSMS(to: number)) : nil }

    private var placeholder: String {
        guard let name = store.lineName(line) else { return "Text Message · SMS" }
        return "Text Message · " + name
    }

    /// Choose which SIM sends: the little SIM button left of the message field.
    private var lineMenu: some View {
        Menu {
            ForEach(store.lines) { l in
                Button { lineChoice = l.id } label: {
                    Label(store.lineName(l.id) ?? l.name, systemImage: l.id == line ? "checkmark" : "simcard")
                }
            }
        } label: {
            Image(systemName: "simcard")
                .font(.system(size: 19))
                .foregroundStyle(.secondary)
                .frame(width: 32, height: 38)
        }
        .accessibilityLabel("Send from")
    }

    private var number: String {
        if let p = pendingPeer, phoneKey(p) == threadKey, store.thread(threadKey).isEmpty { return p }
        return peer
    }

    var body: some View {
        let items = store.thread(threadKey)
        let title = store.displayName(number, fallback: items.compactMap(\.name).first)
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(Array(items.enumerated()), id: \.element.id) { i, m in
                        let prev = i > 0 ? items[i - 1] : nil
                        if prev == nil || m.ts - prev!.ts > 3600 {
                            Text(headerTime(m.date))
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.secondary)
                                .padding(.top, 14)
                                .padding(.bottom, 6)
                        }
                        Bubble(m: m, tail: i == items.count - 1 || items[i + 1].out != m.out)
                            .padding(.top, prev == nil || prev!.out != m.out ? 8 : 2)
                            .id(m.id)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 8)
            }
            .scrollDismissesKeyboard(.interactively)
            .onAppear {
                proxy.scrollTo(items.last?.id, anchor: .bottom)
                store.markRead(threadKey)
            }
            .onChange(of: items.count) {
                withAnimation(.spring(response: 0.3, dampingFraction: 1)) { proxy.scrollTo(items.last?.id, anchor: .bottom) }
                store.markRead(threadKey)
            }
        }
        .safeAreaInset(edge: .bottom) { inputBar }
        .toolbar(.hidden, for: .tabBar)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 2) {
                    AvatarView(name: store.name(for: number) ?? items.compactMap(\.name).first, size: 36)
                    HStack(spacing: 2) {
                        Text(title).font(.caption.weight(.medium)).lineLimit(1)
                        Image(systemName: "chevron.right").font(.system(size: 8, weight: .bold)).foregroundStyle(.secondary)
                    }
                }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { store.dial(number) } label: { Image(systemName: "phone") }
                    .accessibilityLabel("Call")
            }
        }
    }

    private var inputBar: some View {
        let canSend = !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && store.linkUp
        return HStack(alignment: .bottom, spacing: 8) {
            if store.hasLines { lineMenu }
            HStack(alignment: .bottom) {
                TextField(placeholder, text: $draft, axis: .vertical)
                    .lineLimit(1...6)
                    .focused($focused)
                    .padding(.vertical, 8)
                    .padding(.leading, 14)
                if canSend {
                    Button {
                        store.sendSMS(to: number, body: draft, line: line)
                        draft = ""
                    } label: {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: 30))
                            .foregroundStyle(.white, Color.green)
                    }
                    .padding(3)
                    .transition(.scale.combined(with: .opacity))
                    .accessibilityLabel("Send")
                }
            }
            .frame(minHeight: 38)
            .background(Color(.systemBackground).opacity(0.6), in: RoundedRectangle(cornerRadius: 19, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 19, style: .continuous).stroke(Color(.systemGray4), lineWidth: 1))
            .animation(.spring(response: 0.25, dampingFraction: 1), value: canSend)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
    }

    private func headerTime(_ d: Date) -> String {
        let cal = Calendar.current
        let time = d.formatted(date: .omitted, time: .shortened)
        if cal.isDateInToday(d) { return "Today \(time)" }
        if cal.isDateInYesterday(d) { return "Yesterday \(time)" }
        return d.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day()) + " at \(time)"
    }
}

struct Bubble: View {
    let m: SMS
    let tail: Bool

    var body: some View {
        HStack(alignment: .bottom, spacing: 6) {
            if m.out { Spacer(minLength: 56) }
            Text(m.body)
                .font(.body)
                .multilineTextAlignment(isRTL(m.body) ? .trailing : .leading)
                .foregroundStyle(m.out ? Color.white : Color.primary)
                .padding(.horizontal, 13)
                .padding(.vertical, 8)
                .background(m.out ? Color.green : Color(.systemGray5),
                            in: BubbleShape(out: m.out, tail: tail))
                .textSelection(.enabled)
            if m.failed == true {
                Image(systemName: "exclamationmark.circle.fill").foregroundStyle(.red)
            }
            if !m.out { Spacer(minLength: 56) }
        }
        .frame(maxWidth: .infinity)
    }
}

/// Rounded bubble; the last bubble of a run gets a squarer bottom corner on the sender's side.
struct BubbleShape: Shape {
    let out: Bool
    let tail: Bool

    func path(in rect: CGRect) -> Path {
        let r: CGFloat = 18
        let small: CGFloat = tail ? 5 : r
        let radii = RectangleCornerRadii(
            topLeading: r,
            bottomLeading: out ? r : small,
            bottomTrailing: out ? small : r,
            topTrailing: r)
        return UnevenRoundedRectangle(cornerRadii: radii, style: .continuous).path(in: rect)
    }
}

/// True when the text starts with a right-to-left letter (Persian, Arabic, Hebrew).
func isRTL(_ text: String) -> Bool {
    for scalar in text.unicodeScalars where CharacterSet.letters.contains(scalar) {
        return (0x0590...0x08FF).contains(scalar.value) || (0xFB1D...0xFEFF).contains(scalar.value)
    }
    return false
}
