import SwiftUI
import UIKit
import UserNotifications

@main
struct PhoneBridgeApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var store = BridgeStore()
    @Environment(\.scenePhase) private var phase
    @State private var bgTask: UIBackgroundTaskIdentifier = .invalid

    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store)
        }
        .onChange(of: phase) { _, newPhase in
            store.isActive = newPhase == .active
            if newPhase == .background { store.appMovedToBackground(true) }
            if newPhase == .active { store.appMovedToBackground(false) }
            if newPhase == .active {
                UNUserNotificationCenter.current().setBadgeCount(0)
                store.reconnect()
                endBackgroundTask()
            } else if newPhase == .background {
                // Keep the link a little longer after leaving the app.
                bgTask = UIApplication.shared.beginBackgroundTask { endBackgroundTask() }
            }
        }
    }

    private func endBackgroundTask() {
        if bgTask != .invalid {
            UIApplication.shared.endBackgroundTask(bgTask)
            bgTask = .invalid
        }
    }
}

// MARK: - Two parts, like the two iPhone apps

/// The app has two parts that look like the iPhone's own Phone and Messages apps.
enum AppPart: String { case phone, messages }

enum AppTab: String { case recents, contacts, keypad, search }

extension Notification.Name {
    /// userInfo["part"] = AppPart raw value, userInfo["tab"] = optional AppTab raw value.
    static let openPart = Notification.Name("PhoneBridge.openPart")
}

/// Opens a part from anywhere (quick actions, notifications, menus).
func openPart(_ part: AppPart, tab: AppTab? = nil) {
    var info: [String: String] = ["part": part.rawValue]
    if let tab { info["tab"] = tab.rawValue }
    UserDefaults.standard.set(part.rawValue, forKey: "part")
    if let tab { UserDefaults.standard.set(tab.rawValue, forKey: "tab") }
    NotificationCenter.default.post(name: .openPart, object: nil, userInfo: info)
}

/// Home Screen quick actions (touch and hold the icon) and taps on notifications.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func application(_ application: UIApplication, configurationForConnecting session: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        if let item = options.shortcutItem { QuickAction.handle(item) }
        let config = UISceneConfiguration(name: nil, sessionRole: session.role)
        config.delegateClass = SceneDelegate.self
        return config
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler done: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        if let raw = info["part"] as? String, let part = AppPart(rawValue: raw) {
            if let peer = info["peer"] as? String { UserDefaults.standard.set(peer, forKey: "open") }
            openPart(part, tab: (info["tab"] as? String).flatMap(AppTab.init))
        }
        done()
    }
}

final class SceneDelegate: NSObject, UIWindowSceneDelegate {
    func windowScene(_ windowScene: UIWindowScene, performActionFor item: UIApplicationShortcutItem,
                     completionHandler: @escaping (Bool) -> Void) {
        completionHandler(QuickAction.handle(item))
    }
}

enum QuickAction {
    @discardableResult
    static func handle(_ item: UIApplicationShortcutItem) -> Bool {
        switch item.type {
        case "com.phonebridge.messages": openPart(.messages)
        case "com.phonebridge.keypad": openPart(.phone, tab: .keypad)
        case "com.phonebridge.recents": openPart(.phone, tab: .recents)
        default: return false
        }
        return true
    }
}

private struct SettingsActionKey: EnvironmentKey { static let defaultValue: () -> Void = {} }
extension EnvironmentValues {
    /// Shows Settings as a sheet (the iPhone apps keep settings out of the tab bar).
    var showSettings: () -> Void {
        get { self[SettingsActionKey.self] }
        set { self[SettingsActionKey.self] = newValue }
    }
}

struct RootView: View {
    @EnvironmentObject var store: BridgeStore
    @State private var part: AppPart
    @State private var tab: AppTab
    @State private var settings: Bool

    init() {
        let d = UserDefaults.standard
        let oldTab = d.string(forKey: "tab") ?? "" // also "-tab messages" / "-tab settings" from the tests
        _part = State(initialValue: oldTab == "messages" ? .messages : AppPart(rawValue: d.string(forKey: "part") ?? "") ?? .phone)
        _tab = State(initialValue: AppTab(rawValue: oldTab) ?? .keypad)
        _settings = State(initialValue: oldTab == "settings")
    }

    var body: some View {
        Group {
            if store.pairing == nil {
                PairView()
            } else if part == .messages {
                ConversationsView()
                    .transition(.opacity)
            } else {
                PhoneTabs(tab: $tab)
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: part)
        .environment(\.showSettings, { settings = true })
        .onChange(of: part) { _, p in UserDefaults.standard.set(p.rawValue, forKey: "part") }
        .onChange(of: tab) { _, t in UserDefaults.standard.set(t.rawValue, forKey: "tab") }
        .onReceive(NotificationCenter.default.publisher(for: .openPart)) { n in
            if let raw = n.userInfo?["part"] as? String, let p = AppPart(rawValue: raw) { part = p }
            if let raw = n.userInfo?["tab"] as? String, let t = AppTab(rawValue: raw) { tab = t }
        }
        .sheet(isPresented: $settings) {
            SettingsView().environmentObject(store)
        }
        .fullScreenCover(isPresented: Binding(get: { store.showsOwnCallScreen }, set: { if !$0 { store.call = nil } })) {
            CallView().environmentObject(store)
        }
    }
}

/// The Phone part: Calls, Contacts, Keypad and the separate Search button, like iOS 26 Phone.
struct PhoneTabs: View {
    @EnvironmentObject var store: BridgeStore
    @Binding var tab: AppTab

    var body: some View {
        if #available(iOS 18.0, *) {
            TabView(selection: $tab) {
                Tab("Calls", systemImage: "clock.fill", value: AppTab.recents) { RecentsView() }
                    .badge(store.missedCount)
                Tab("Contacts", systemImage: "person.crop.circle.fill", value: AppTab.contacts) { ContactsView() }
                Tab("Keypad", systemImage: "circle.grid.3x3.fill", value: AppTab.keypad) { KeypadView() }
                Tab(value: AppTab.search, role: .search) { PhoneSearchView() }
            }
        } else {
            TabView(selection: $tab) {
                RecentsView()
                    .tabItem { Label("Calls", systemImage: "clock.fill") }
                    .badge(store.missedCount)
                    .tag(AppTab.recents)
                ContactsView()
                    .tabItem { Label("Contacts", systemImage: "person.crop.circle.fill") }
                    .tag(AppTab.contacts)
                KeypadView()
                    .tabItem { Label("Keypad", systemImage: "circle.grid.3x3.fill") }
                    .tag(AppTab.keypad)
                PhoneSearchView()
                    .tabItem { Label("Search", systemImage: "magnifyingglass") }
                    .tag(AppTab.search)
            }
        }
    }
}

/// The menu that moves between the two parts and opens Settings (top right, like the iPhone apps' menus).
struct PartMenuItems: View {
    @EnvironmentObject var store: BridgeStore
    @Environment(\.showSettings) private var showSettings
    let current: AppPart

    var body: some View {
        if current == .phone {
            Button { openPart(.messages) } label: {
                Label(store.unreadCount > 0 ? "Messages (\(store.unreadCount))" : "Messages", systemImage: "message")
            }
        } else {
            Button { openPart(.phone) } label: {
                Label(store.missedCount > 0 ? "Phone (\(store.missedCount))" : "Phone", systemImage: "phone")
            }
        }
        Button { showSettings() } label: { Label("Settings", systemImage: "gearshape") }
        Button {} label: {
            Label(store.androidOnline ? "Android Connected" : "Android Offline",
                  systemImage: store.androidOnline ? "checkmark.circle" : "exclamationmark.triangle")
        }
        .disabled(true)
    }
}

// MARK: - Shared pieces

/// Contact picture placeholder, like the system apps: initials, or a silhouette when there is no name.
struct AvatarView: View {
    let name: String?
    var size: CGFloat = 52

    var body: some View {
        ZStack {
            Circle().fill(LinearGradient(
                colors: [Color(red: 0.66, green: 0.68, blue: 0.86), Color(red: 0.47, green: 0.50, blue: 0.72)],
                startPoint: .top, endPoint: .bottom))
            if let text = initials(name) {
                Text(text)
                    .font(.system(size: size * 0.40, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
            } else {
                Image(systemName: "person.fill")
                    .font(.system(size: size * 0.52))
                    .foregroundStyle(.white)
                    .offset(y: size * 0.08)
                    .clipShape(Circle())
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }
}

/// Instant press feedback (on touch-down), settles with a critically damped spring.
struct PressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.94 : 1)
            .brightness(configuration.isPressed ? -0.08 : 0)
            .animation(.spring(response: 0.25, dampingFraction: 1), value: configuration.isPressed)
    }
}

/// Liquid Glass on iOS 26, a soft filled circle before that.
struct GlassCircle: ViewModifier {
    func body(content: Content) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            content.glassEffect(.regular.interactive(), in: Circle())
        } else {
            content.background(Circle().fill(Color(.systemGray5)))
        }
        #else
        content.background(Circle().fill(Color(.systemGray5)))
        #endif
    }
}

/// "7:16 PM" today, "Yesterday", weekday this week, date otherwise — like Messages and Phone.
func listTime(_ d: Date) -> String {
    let cal = Calendar.current
    if cal.isDateInToday(d) { return d.formatted(date: .omitted, time: .shortened) }
    if cal.isDateInYesterday(d) { return "Yesterday" }
    if let days = cal.dateComponents([.day], from: cal.startOfDay(for: d), to: cal.startOfDay(for: Date())).day, days < 7 {
        return d.formatted(.dateTime.weekday(.wide))
    }
    return d.formatted(date: .numeric, time: .omitted)
}
