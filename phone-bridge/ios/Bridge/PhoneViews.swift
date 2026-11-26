import SwiftUI

// MARK: - Keypad

struct KeypadView: View {
    @EnvironmentObject var store: BridgeStore
    @State private var number = ""
    @State private var longPressed = false
    /// A line picked by hand for this number; empty again when the number is cleared.
    @State private var lineOverride: Int?

    private var currentLine: Int? { store.hasLines ? (lineOverride ?? store.lineForCall(to: number)) : nil }

    /// "Irancell ▾": which line the call goes out on. Tap to switch, like the iPhone's keypad.
    private var linePill: some View {
        Menu {
            ForEach(store.lines) { l in
                Button { lineOverride = l.id } label: {
                    Label(store.lineName(l.id) ?? l.name, systemImage: l.id == currentLine ? "checkmark" : "simcard")
                }
            }
        } label: {
            HStack(spacing: 5) {
                Image(systemName: "simcard.fill").font(.system(size: 11))
                Text(store.lineName(currentLine) ?? " ").font(.footnote.weight(.medium))
                Image(systemName: "chevron.down").font(.system(size: 9, weight: .bold))
            }
            .foregroundStyle(.secondary)
            .padding(.horizontal, 12).padding(.vertical, 5)
            .background(Color(.secondarySystemFill), in: Capsule())
        }
        .accessibilityLabel("Calling from")
    }

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
                if store.hasLines { linePill.padding(.bottom, 6) }
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
                        store.dial(number, line: currentLine)
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
        .onChange(of: number) { if number.isEmpty { lineOverride = nil } }
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

    /// "mobile", plus the line when there are two SIMs: "mobile · Irancell".
    private func subtitle(_ c: CallEntry) -> String {
        let base = store.name(for: c.number) != nil || c.name != nil ? "mobile" : "Unknown"
        guard store.hasLines, let line = store.lineName(c.sim) else { return base }
        return base + " · " + line
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
                        Text(subtitle(c))
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
                .onTapGesture { store.dial(c.number, line: c.sim) }
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
                            callTile(n)
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
                            Text(callLabel(c) + lineSuffix(c)).foregroundStyle(c.isMissed ? .red : .secondary)
                        }
                        .font(.subheadline)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationBarTitleDisplayMode(.inline)
    }

    /// Tap calls on the usual line; touch and hold chooses a line (with two SIMs), like the iPhone.
    @ViewBuilder
    private func callTile(_ n: String) -> some View {
        if store.hasLines {
            Menu {
                ForEach(store.lines) { l in
                    Button { store.dial(n, line: l.id) } label: {
                        Label(store.lineName(l.id) ?? l.name, systemImage: l.id == store.lineForCall(to: n) ? "checkmark" : "simcard")
                    }
                }
            } label: {
                ActionTile(icon: "phone.fill", title: "call")
            } primaryAction: {
                store.dial(n)
            }
            .buttonStyle(.plain)
        } else {
            Button { store.dial(n) } label: { ActionTile(icon: "phone.fill", title: "call") }
                .buttonStyle(.plain)
        }
    }

    private func lineSuffix(_ c: CallEntry) -> String {
        guard store.hasLines, let line = store.lineName(c.sim) else { return "" }
        return " · " + line
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

/// The call screen, in the style of the iOS 26 Phone app: a dark backdrop tinted from the caller's
/// avatar, the name high up in light weight, a grid of Liquid Glass round buttons, End below.
struct CallView: View {
    @EnvironmentObject var store: BridgeStore
    @State private var showKeypad = false

    private var call: CallInfo? { store.call }
    private var ringing: Bool { call?.state == "ringing" }
    private var name: String { call.map { store.displayName($0.number, fallback: $0.name) } ?? "" }

    var body: some View {
        ZStack {
            CallBackdrop(seed: name)

            VStack(spacing: 0) {
                statusPill
                    .padding(.top, 14)

                Spacer().frame(height: 48)

                AvatarView(name: call.flatMap { store.name(for: $0.number) ?? $0.name }, size: 96)
                    .shadow(color: .black.opacity(0.25), radius: 18, y: 8)

                Text(name)
                    .font(.system(size: 36, weight: .light))
                    .tracking(0.2)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .padding(.top, 16)
                    .padding(.horizontal, 24)

                status
                    .font(.system(size: 19, weight: .regular))
                    .foregroundStyle(.white.opacity(0.75))
                    .padding(.top, 4)

                Spacer()

                hint

                if !ringing && store.audioOn {
                    controlGrid
                        .padding(.horizontal, 36)
                        .padding(.bottom, 28)
                }

                bottomRow
                    .padding(.horizontal, ringing ? 52 : 36)
                    .padding(.bottom, 44)
            }
        }
        .foregroundStyle(.white)
        .sheet(isPresented: $showKeypad) { InCallKeypad(name: name).environmentObject(store) }
    }

    private var statusPill: some View {
        HStack(spacing: 6) {
            Image(systemName: store.audioOn ? "lock.fill" : "candybarphone")
                .font(.system(size: 11, weight: .bold))
            Text(statusText).lineLimit(1).minimumScaleFactor(0.8)
        }
        .font(.footnote.weight(.medium))
        .padding(.horizontal, 12).padding(.vertical, 6)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(Capsule().strokeBorder(.white.opacity(0.12)))
    }

    private var statusText: String {
        var parts: [String] = []
        if store.hasLines, let line = store.lineName(call?.sim) { parts.append(line) } // which SIM
        if store.audioOn {
            parts.append("Encrypted")
            if store.audioSource?.hasPrefix("call line") == true { parts.append("direct line") }
            else if store.audioRoute == "cable" { parts.append("cable") }
        } else {
            parts.append("via Android")
        }
        return parts.joined(separator: " · ")
    }

    @ViewBuilder private var status: some View {
        switch call?.state {
        case "ringing": Text("mobile")
        case "dialing": Text("calling…")
        case "active":
            if let start = call?.started {
                TimelineView(.periodic(from: start, by: 1)) { ctx in
                    Text(Duration.seconds(ctx.date.timeIntervalSince(start)).formatted(.time(pattern: .minuteSecond)))
                        .monospacedDigit()
                }
            } else { Text("connected") }
        default: Text(" ")
        }
    }

    @ViewBuilder private var hint: some View {
        if let problem = store.audioProblem {
            Label(problem, systemImage: "exclamationmark.triangle.fill")
                .font(.footnote).foregroundStyle(.yellow)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32).padding(.bottom, 22)
        } else if !store.internetAudio && store.forwardMode == "off" {
            Text("Call sound is on the Android phone. Turn on call sound over the internet or call forwarding in Settings.")
                .font(.footnote).foregroundStyle(.white.opacity(0.6))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40).padding(.bottom, 28)
        }
    }

    /// The iOS 2×3 control grid. Mute, Keypad and Audio are wired; the rest match the look.
    private var controlGrid: some View {
        Grid(horizontalSpacing: 28, verticalSpacing: 20) {
            GridRow {
                CallButton(icon: store.muted ? "mic.slash.fill" : "mic.slash", label: "mute", on: store.muted) { store.muted.toggle() }
                CallButton(icon: "circle.grid.3x3.fill", label: "keypad") { showKeypad = true }
                CallButton(icon: store.speaker ? "speaker.wave.3.fill" : "speaker.wave.2.fill",
                           label: store.audioRoute == "cable" ? "cable" : "audio", on: store.speaker) { store.speaker.toggle() }
            }
            GridRow {
                CallButton(icon: "plus", label: "add", enabled: false) {}
                CallButton(icon: "video.fill", label: "FaceTime", enabled: false) {}
                CallButton(icon: "person.crop.circle", label: "contact", enabled: store.name(for: call?.number ?? "") != nil) {
                    openPart(.phone, tab: .contacts)
                    store.call = nil
                }
            }
        }
    }

    @ViewBuilder private var bottomRow: some View {
        if ringing {
            HStack {
                CallEndButton(icon: "phone.down.fill", color: .red, label: "Decline") { store.hangup() }
                Spacer()
                CallEndButton(icon: "phone.fill", color: Color(red: 0.2, green: 0.78, blue: 0.35), label: "Accept") { store.answer() }
            }
        } else {
            HStack { Spacer(); CallEndButton(icon: "phone.down.fill", color: .red, label: "") { store.hangup() }; Spacer() }
        }
    }
}

/// A dark call backdrop tinted from the caller's name, blurred like the iOS contact-photo background.
private struct CallBackdrop: View {
    let seed: String

    var body: some View {
        let h = Double(abs(seed.hashValue) % 360) / 360
        let tint = Color(hue: h, saturation: 0.5, brightness: 0.5)
        ZStack {
            Color.black
            RadialGradient(colors: [tint.opacity(0.55), .clear], center: .init(x: 0.5, y: 0.32),
                           startRadius: 0, endRadius: 520)
            LinearGradient(colors: [.white.opacity(0.05), .clear, .black.opacity(0.45)],
                           startPoint: .top, endPoint: .bottom)
        }
        .ignoresSafeArea()
    }
}

/// A round Liquid Glass control button (iOS 26 look), with a label underneath.
private struct CallButton: View {
    let icon: String
    let label: String
    var on: Bool = false
    var enabled: Bool = true
    let action: () -> Void

    var body: some View {
        VStack(spacing: 9) {
            Button(action: action) {
                Image(systemName: icon)
                    .font(.system(size: 27, weight: .regular))
                    .foregroundStyle(on ? .black : .white)
                    .frame(width: 74, height: 74)
                    .modifier(CallGlass(on: on))
            }
            .buttonStyle(PressStyle())
            .disabled(!enabled)
            .opacity(enabled ? 1 : 0.35)
            Text(label)
                .font(.system(size: 13))
                .foregroundStyle(.white.opacity(0.9))
                .frame(height: 16)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(label)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// The big round End / Accept / Decline button.
private struct CallEndButton: View {
    let icon: String
    let color: Color
    let label: String
    let action: () -> Void

    var body: some View {
        VStack(spacing: 9) {
            Button(action: action) {
                Image(systemName: icon)
                    .font(.system(size: 31, weight: .medium))
                    .foregroundStyle(.white)
                    .frame(width: 74, height: 74)
                    .background(Circle().fill(color))
                    .shadow(color: color.opacity(0.5), radius: 12, y: 4)
            }
            .buttonStyle(PressStyle())
            if !label.isEmpty { Text(label).font(.system(size: 13)).foregroundStyle(.white.opacity(0.9)) }
        }
        .accessibilityLabel(label.isEmpty ? "End call" : label)
    }
}

/// Liquid Glass on iOS 26, a soft translucent circle before that; white when the control is on.
private struct CallGlass: ViewModifier {
    let on: Bool
    func body(content: Content) -> some View {
        if on {
            content.background(Circle().fill(.white))
        } else {
            #if compiler(>=6.2)
            if #available(iOS 26.0, *) {
                content.glassEffect(.regular.interactive(), in: Circle())
            } else {
                content.background(Circle().fill(.ultraThinMaterial))
                    .overlay(Circle().strokeBorder(.white.opacity(0.14)))
            }
            #else
            content.background(Circle().fill(.ultraThinMaterial))
                .overlay(Circle().strokeBorder(.white.opacity(0.14)))
            #endif
        }
    }
}

/// An in-call number pad, shown when Keypad is tapped. Sends DTMF tones when the phone supports it.
private struct InCallKeypad: View {
    @EnvironmentObject var store: BridgeStore
    @Environment(\.dismiss) private var dismiss
    let name: String
    @State private var typed = ""

    private let keys = [["1","2","3"],["4","5","6"],["7","8","9"],["*","0","#"]]
    private let letters = ["2":"ABC","3":"DEF","4":"GHI","5":"JKL","6":"MNO","7":"PQRS","8":"TUV","9":"WXYZ","0":"+"]

    var body: some View {
        ZStack {
            CallBackdrop(seed: name).opacity(0.9)
            VStack(spacing: 0) {
                Text(typed.isEmpty ? name : typed)
                    .font(.system(size: 32, weight: .light)).lineLimit(1).minimumScaleFactor(0.5)
                    .padding(.top, 40).padding(.horizontal, 24)
                Spacer()
                VStack(spacing: 18) {
                    ForEach(keys, id: \.self) { row in
                        HStack(spacing: 28) {
                            ForEach(row, id: \.self) { k in
                                Button { tap(k) } label: {
                                    VStack(spacing: 1) {
                                        Text(k).font(.system(size: 34, weight: .regular))
                                        Text(letters[k] ?? " ").font(.system(size: 10, weight: .semibold)).tracking(1)
                                            .frame(height: 10)
                                    }
                                    .foregroundStyle(.white)
                                    .frame(width: 74, height: 74)
                                    .modifier(CallGlass(on: false))
                                }
                                .buttonStyle(PressStyle())
                            }
                        }
                    }
                }
                Spacer()
                HStack {
                    Spacer()
                    CallEndButton(icon: "phone.down.fill", color: .red, label: "") { store.hangup(); dismiss() }
                    Spacer()
                }
                .overlay(alignment: .trailing) {
                    Button("Hide") { dismiss() }.foregroundStyle(.white).padding(.trailing, 36)
                }
                .padding(.bottom, 44)
            }
            .foregroundStyle(.white)
        }
    }

    private func tap(_ k: String) {
        typed.append(k)
        store.sendDTMF(k)
    }
}
