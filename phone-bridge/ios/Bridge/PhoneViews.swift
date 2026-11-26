import SwiftUI

// MARK: - Keypad

struct KeypadView: View {
    @EnvironmentObject var store: BridgeStore
    @State private var number = ""
    @State private var longPressed = false

    private let keys: [(String, String)] = [
        ("1", ""), ("2", "ABC"), ("3", "DEF"),
        ("4", "GHI"), ("5", "JKL"), ("6", "MNO"),
        ("7", "PQRS"), ("8", "TUV"), ("9", "WXYZ"),
        ("*", ""), ("0", "+"), ("#", ""),
    ]
    private let size: CGFloat = 78

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 0)
            VStack(spacing: 4) {
                Text(number.isEmpty ? " " : number)
                    .font(.system(size: 38, weight: .regular))
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .frame(height: 48)
                Text(store.name(for: number).flatMap { number.count > 3 ? $0 : nil } ?? " ")
                    .font(.subheadline)
                    .foregroundStyle(.blue)
            }
            .padding(.horizontal, 32)
            .padding(.bottom, 28)

            LazyVGrid(columns: Array(repeating: GridItem(.fixed(size), spacing: 26), count: 3), spacing: 18) {
                ForEach(keys, id: \.0) { key in
                    Button {
                        if longPressed { longPressed = false; return }
                        number.append(key.0)
                    } label: {
                        VStack(spacing: 0) {
                            Text(key.0)
                                .font(.system(size: key.0 == "*" ? 44 : 36, weight: .regular))
                                .offset(y: key.0 == "*" ? 6 : 0)
                            if !key.1.isEmpty {
                                Text(key.1)
                                    .font(.system(size: key.0 == "0" ? 16 : 10, weight: .bold))
                                    .tracking(2)
                            }
                        }
                        .foregroundStyle(.primary)
                        .frame(width: size, height: size)
                        .contentShape(Circle())
                        .modifier(GlassCircle())
                    }
                    .buttonStyle(PressStyle())
                    .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in
                        if key.0 == "0" { longPressed = true; number.append("+") }
                    })
                    .accessibilityLabel(key.0)
                }
            }

            HStack(spacing: 26) {
                Color.clear.frame(width: size, height: size)
                Button {
                    if number.isEmpty {
                        // Like the system keypad: first tap recalls the last number dialled.
                        number = store.calls.first(where: { $0.type == "out" })?.number ?? ""
                    } else {
                        store.dial(number)
                    }
                } label: {
                    Image(systemName: "phone.fill")
                        .font(.system(size: 32))
                        .foregroundStyle(.white)
                        .frame(width: size, height: size)
                        .background(Circle().fill(Color.green))
                }
                .buttonStyle(PressStyle())
                .accessibilityLabel("Call")
                Button { if !number.isEmpty { number.removeLast() } } label: {
                    Image(systemName: "delete.left.fill")
                        .font(.system(size: 26))
                        .foregroundStyle(Color(.systemGray2))
                        .frame(width: size, height: size)
                }
                .opacity(number.isEmpty ? 0 : 1)
                .disabled(number.isEmpty)
                .simultaneousGesture(LongPressGesture(minimumDuration: 0.6).onEnded { _ in number = "" })
                .accessibilityLabel("Delete")
            }
            .padding(.top, 18)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Recents

private struct CallGroup: Identifiable {
    var id: String
    var entry: CallEntry
    var count: Int
}

struct RecentsView: View {
    @EnvironmentObject var store: BridgeStore
    @State private var missedOnly = false
    @State private var path: [String] = []

    private var groups: [CallGroup] {
        var out: [CallGroup] = []
        for c in store.calls where !missedOnly || c.isMissed {
            if let last = out.last, phoneKey(last.entry.number) == phoneKey(c.number), last.entry.type == c.type,
               Calendar.current.isDate(last.entry.date, inSameDayAs: c.date) {
                out[out.count - 1].count += 1
            } else {
                out.append(CallGroup(id: c.id, entry: c, count: 1))
            }
        }
        return out
    }

    var body: some View {
        NavigationStack(path: $path) {
            List(groups) { g in
                let c = g.entry
                HStack(spacing: 12) {
                    Image(systemName: "phone.arrow.up.right.fill")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .opacity(c.type == "out" ? 1 : 0)
                        .frame(width: 14)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(store.displayName(c.number, fallback: c.name) + (g.count > 1 ? " (\(g.count))" : ""))
                            .font(.headline)
                            .foregroundStyle(c.isMissed ? Color.red : Color.primary)
                            .lineLimit(1)
                        Text(store.name(for: c.number) != nil || c.name != nil ? "mobile" : "Unknown")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text(listTime(c.date))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Button { path.append(c.number) } label: {
                        Image(systemName: "info.circle").font(.title3).foregroundStyle(.blue)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Details")
                    .frame(width: 28)
                }
                .contentShape(Rectangle())
                .onTapGesture { store.dial(c.number) }
            }
            .listStyle(.plain)
            .overlay {
                if groups.isEmpty {
                    EmptyState(icon: "clock", title: missedOnly ? "No Missed Calls" : "No Recents")
                }
            }
            .navigationDestination(for: String.self) { number in
                ContactCardView(name: store.name(for: number), numbers: [number])
            }
            .navigationTitle(missedOnly ? "Missed" : "Calls")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Picker("Filter", selection: $missedOnly) {
                            Label("All Calls", systemImage: "phone").tag(false)
                            Label("Missed", systemImage: "phone.down").tag(true)
                        }
                        Divider()
                        PartMenuItems(current: .phone)
                    } label: {
                        Image(systemName: "line.3.horizontal.decrease")
                    }
                    .accessibilityLabel("Filter and More")
                }
            }
            .onAppear { store.markCallsSeen() }
        }
    }
}

// MARK: - Search

/// The separate Search button of iOS 26 Phone: finds contacts, numbers and recent calls.
struct PhoneSearchView: View {
    @EnvironmentObject var store: BridgeStore
    @State private var query = ""
    @State private var path: [String] = []

    private var contacts: [Contact] {
        guard !query.isEmpty else { return [] }
        let digits = query.filter(\.isNumber)
        return store.contacts.filter {
            $0.name.localizedCaseInsensitiveContains(query) ||
                (!digits.isEmpty && $0.numbers.contains { $0.filter(\.isNumber).contains(digits) })
        }
        .sorted { $0.name.localizedCompare($1.name) == .orderedAscending }
    }

    private var recents: [CallEntry] {
        guard !query.isEmpty else { return [] }
        let digits = query.filter(\.isNumber)
        var seen = Set<String>()
        return store.calls.filter { c in
            let hit = store.displayName(c.number, fallback: c.name).localizedCaseInsensitiveContains(query) ||
                (!digits.isEmpty && c.number.filter(\.isNumber).contains(digits))
            return hit && seen.insert(phoneKey(c.number)).inserted
        }
        .prefix(20).map { $0 }
    }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                if !contacts.isEmpty {
                    Section("Contacts") {
                        ForEach(contacts) { c in
                            NavigationLink(value: c.numbers.first ?? c.name) {
                                HStack(spacing: 12) {
                                    AvatarView(name: c.name, size: 40)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(c.name).font(.body)
                                        if let n = c.numbers.first {
                                            Text(formatNumber(n)).font(.subheadline).foregroundStyle(.secondary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if !recents.isEmpty {
                    Section("Recents") {
                        ForEach(recents) { c in
                            Button { store.dial(c.number) } label: {
                                HStack {
                                    Text(store.displayName(c.number, fallback: c.name))
                                        .foregroundStyle(c.isMissed ? Color.red : Color.primary)
                                    Spacer()
                                    Text(listTime(c.date)).font(.subheadline).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .overlay {
                if query.isEmpty {
                    EmptyState(icon: "magnifyingglass", title: "Search Contacts and Calls")
                } else if contacts.isEmpty && recents.isEmpty {
                    EmptyState(icon: "magnifyingglass", title: "No Results")
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search")
            .navigationTitle("Search")
            .navigationDestination(for: String.self) { number in
                let c = store.contacts.first { $0.numbers.contains(number) }
                ContactCardView(name: c?.name ?? store.name(for: number), numbers: c?.numbers ?? [number])
            }
        }
    }
}

// MARK: - Contacts

struct ContactsView: View {
    @EnvironmentObject var store: BridgeStore
    @State private var query = ""

    private var sections: [(String, [Contact])] {
        let list = query.isEmpty ? store.contacts : store.contacts.filter {
            $0.name.localizedCaseInsensitiveContains(query) || $0.numbers.contains { $0.contains(query) }
        }
        let grouped = Dictionary(grouping: list) { c -> String in
            let first = c.name.trimmingCharacters(in: .whitespaces).first.map { String($0).uppercased() } ?? "#"
            return first.rangeOfCharacter(from: .letters) != nil ? first : "#"
        }
        return grouped.keys.sorted().map { ($0, grouped[$0]!.sorted { $0.name.localizedCompare($1.name) == .orderedAscending }) }
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(sections, id: \.0) { letter, list in
                    Section(letter) {
                        ForEach(list) { c in
                            NavigationLink(value: c) {
                                Text(c.name).font(.body)
                            }
                        }
                    }
                }
            }
            .listStyle(.plain)
            .overlay {
                if store.contacts.isEmpty { EmptyState(icon: "person.crop.circle", title: "No Contacts") }
            }
            .searchable(text: $query)
            .navigationTitle("Contacts")
            .navigationDestination(for: Contact.self) { c in
                ContactCardView(name: c.name, numbers: c.numbers)
            }
        }
    }
}

struct ContactCardView: View {
    @EnvironmentObject var store: BridgeStore
    let name: String?
    let numbers: [String]

    var body: some View {
        List {
            Section {
                VStack(spacing: 10) {
                    AvatarView(name: name, size: 96)
                    Text(name ?? formatNumber(numbers.first ?? ""))
                        .font(.system(size: 28, weight: .regular))
                        .multilineTextAlignment(.center)
                    HStack(spacing: 8) {
                        if let n = numbers.first {
                            NavigationLink {
                                ChatView(threadKey: phoneKey(n), peer: n)
                            } label: {
                                ActionTile(icon: "message.fill", title: "message")
                            }
                            .buttonStyle(.plain)
                            Button { store.dial(n) } label: { ActionTile(icon: "phone.fill", title: "call") }
                                .buttonStyle(.plain)
                        }
                    }
                    .padding(.top, 6)
                }
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
            }
            Section {
                ForEach(numbers, id: \.self) { n in
                    Button { store.dial(n) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("mobile").font(.subheadline).foregroundStyle(.primary)
                            Text(formatNumber(n)).foregroundStyle(.blue)
                        }
                    }
                }
            }
            let history = store.calls.filter { c in numbers.contains { phoneKey($0) == phoneKey(c.number) } }.prefix(10)
            if !history.isEmpty {
                Section("Recent Calls") {
                    ForEach(Array(history)) { c in
                        HStack {
                            Text(c.date.formatted(date: .abbreviated, time: .shortened))
                            Spacer()
                            Text(callLabel(c)).foregroundStyle(c.isMissed ? .red : .secondary)
                        }
                        .font(.subheadline)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func callLabel(_ c: CallEntry) -> String {
        switch c.type {
        case "missed": return "Missed Call"
        case "rejected": return "Declined"
        case "out": return c.dur > 0 ? "Outgoing · \(Duration.seconds(c.dur).formatted(.time(pattern: .minuteSecond)))" : "Canceled"
        default: return "Incoming · \(Duration.seconds(c.dur).formatted(.time(pattern: .minuteSecond)))"
        }
    }
}

struct ActionTile: View {
    let icon: String
    let title: String

    var body: some View {
        VStack(spacing: 4) {
            Image(systemName: icon).font(.system(size: 18))
            Text(title).font(.caption2)
        }
        .foregroundStyle(.blue)
        .frame(width: 76, height: 56)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

struct EmptyState: View {
    let icon: String
    let title: String

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: icon).font(.system(size: 44)).foregroundStyle(.tertiary)
            Text(title).font(.title3.weight(.semibold)).foregroundStyle(.secondary)
        }
    }
}

// MARK: - In-call screen

struct CallView: View {
    @EnvironmentObject var store: BridgeStore

    var body: some View {
        let call = store.call
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                Image(systemName: store.audioOn ? "lock.fill" : "candybarphone")
                Text(store.audioOn ? "Encrypted · via Android" + (store.audioSource?.hasPrefix("call line") == true ? " · direct line" : "")
                     + (store.audioRoute == "cable" ? " · cable" : "") : "via Android")
            }
            .font(.footnote.weight(.medium))
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(.white.opacity(0.14), in: Capsule())
            .padding(.top, 12)

            Spacer().frame(height: 70)
            AvatarView(name: call.flatMap { store.name(for: $0.number) ?? $0.name }, size: 104)
            Text(call.map { store.displayName($0.number, fallback: $0.name) } ?? "")
                .font(.system(size: 34, weight: .regular))
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .minimumScaleFactor(0.6)
                .padding(.top, 18)
                .padding(.horizontal, 24)
            status(call)
                .font(.title3)
                .foregroundStyle(.white.opacity(0.7))
                .padding(.top, 6)

            Spacer()

            if let problem = store.audioProblem {
                Label(problem, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(.yellow)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
                    .padding(.bottom, 24)
            } else if !store.internetAudio && store.forwardMode == "off" {
                Text("Call sound is on the Android phone. Turn on call sound over the internet or call forwarding in Settings.")
                    .font(.footnote)
                    .foregroundStyle(.white.opacity(0.6))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)
                    .padding(.bottom, 36)
            }

            if call?.state != "ringing" && store.audioOn {
                HStack(spacing: 44) {
                    toggleButton(icon: store.muted ? "mic.slash.fill" : "mic.slash", label: "mute", on: store.muted) {
                        store.muted.toggle()
                    }
                    toggleButton(icon: "speaker.wave.3.fill", label: "speaker", on: store.speaker) {
                        store.speaker.toggle()
                    }
                }
                .padding(.bottom, 40)
            }

            if call?.state == "ringing" {
                HStack {
                    roundButton(icon: "phone.down.fill", color: .red, label: "Decline") { store.hangup() }
                    Spacer()
                    roundButton(icon: "phone.fill", color: .green, label: "Accept") { store.answer() }
                }
                .padding(.horizontal, 48)
            } else {
                roundButton(icon: "phone.down.fill", color: .red, label: "End") { store.hangup() }
            }
            Spacer().frame(height: 56)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .foregroundStyle(.white)
        .background(
            LinearGradient(colors: [Color(red: 0.16, green: 0.33, blue: 0.27), Color(red: 0.05, green: 0.09, blue: 0.08)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
        )
    }

    @ViewBuilder
    private func status(_ call: CallInfo?) -> some View {
        switch call?.state {
        case "ringing": Text("mobile")
        case "dialing": Text("calling…")
        case "active":
            if let start = call?.started {
                TimelineView(.periodic(from: start, by: 1)) { ctx in
                    Text(Duration.seconds(ctx.date.timeIntervalSince(start)).formatted(.time(pattern: .minuteSecond)))
                        .monospacedDigit()
                }
            } else {
                Text("connected")
            }
        default: Text(" ")
        }
    }

    private func toggleButton(icon: String, label: String, on: Bool, action: @escaping () -> Void) -> some View {
        VStack(spacing: 8) {
            Button(action: action) {
                Image(systemName: icon)
                    .font(.system(size: 28))
                    .foregroundStyle(on ? Color.black : Color.white)
                    .frame(width: 78, height: 78)
                    .background(Circle().fill(on ? Color.white : Color.white.opacity(0.18)))
            }
            .buttonStyle(PressStyle())
            .accessibilityLabel(label)
            .accessibilityAddTraits(on ? .isSelected : [])
            Text(label).font(.footnote)
        }
    }

    private func roundButton(icon: String, color: Color, label: String, action: @escaping () -> Void) -> some View {
        VStack(spacing: 8) {
            Button(action: action) {
                Image(systemName: icon)
                    .font(.system(size: 32))
                    .foregroundStyle(.white)
                    .frame(width: 78, height: 78)
                    .background(Circle().fill(color))
            }
            .buttonStyle(PressStyle())
            Text(label).font(.footnote)
        }
    }
}
