import SwiftUI
import VisionKit

// MARK: - Pairing

struct PairView: View {
    @EnvironmentObject var store: BridgeStore
    @State private var scanning = false
    @State private var pasted = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(spacing: 12) {
                        Image(systemName: "iphone.gen3.radiowaves.left.and.right")
                            .font(.system(size: 54))
                            .foregroundStyle(.green)
                        Text("Connect your Android phone")
                            .font(.title2.weight(.semibold))
                        Text("On the Android phone, open Ghafoorii and tap “Create new pairing”. Then scan the QR code.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .listRowBackground(Color.clear)
                }
                Section {
                    Button {
                        scanning = true
                    } label: {
                        Label("Scan QR Code", systemImage: "qrcode.viewfinder")
                    }
                    .disabled(!(DataScannerViewController.isSupported && DataScannerViewController.isAvailable))
                }
                Section("Or paste the pairing text") {
                    TextField("{\"u\":…,\"k\":…}", text: $pasted, axis: .vertical)
                        .font(.footnote.monospaced())
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("Pair") { tryPair(pasted) }
                }
                if let error {
                    Section { Text(error).foregroundStyle(.red) }
                }
            }
            .navigationTitle("Ghafoorii")
            .sheet(isPresented: $scanning) {
                QRScanner { text in
                    scanning = false
                    tryPair(text)
                }
                .ignoresSafeArea()
            }
        }
    }

    private func tryPair(_ text: String) {
        if let p = Pairing.parse(text) {
            store.pair(with: p)
        } else {
            error = "This is not a valid pairing code."
        }
    }
}

struct QRScanner: UIViewControllerRepresentable {
    let onFound: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let vc = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])],
                                           qualityLevel: .balanced, isHighlightingEnabled: true)
        vc.delegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ vc: DataScannerViewController, context: Context) {
        if !vc.isScanning { try? vc.startScanning() }
    }

    func makeCoordinator() -> Coordinator { Coordinator(onFound: onFound) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onFound: (String) -> Void
        private var done = false
        init(onFound: @escaping (String) -> Void) { self.onFound = onFound }

        func dataScanner(_ scanner: DataScannerViewController, didAdd items: [RecognizedItem], allItems: [RecognizedItem]) {
            for item in items {
                if case .barcode(let code) = item, let text = code.payloadStringValue, !done {
                    done = true
                    scanner.stopScanning()
                    onFound(text)
                }
            }
        }
    }
}

// MARK: - Settings

struct SettingsView: View {
    @EnvironmentObject var store: BridgeStore
    @Environment(\.dismiss) private var dismiss
    @State private var confirmUnpair = false
    @State private var mode = "off"

    private let modes: [(String, String)] = [
        ("off", "Off"),
        ("all", "Always"),
        ("conditional", "When Busy, Unanswered or Unreachable"),
        ("noanswer", "When Unanswered"),
        ("unreachable", "When Unreachable"),
    ]

    var body: some View {
        NavigationStack {
            Form {
                Section("Connection") {
                    if !(store.pairing?.u.isEmpty ?? true) { statusRow("Relay Server", store.relayConnected) }
                    statusRow("Android Phone", store.androidOnline)
                    HStack {
                        Text("Bluetooth")
                        Spacer()
                        Image(systemName: store.bleUp ? "dot.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
                            .foregroundStyle(store.bleUp ? Color.blue : Color.secondary)
                        Text(store.bleUp ? "Direct, no server" : "Not near").foregroundStyle(.secondary)
                    }
                    HStack {
                        Text("Encryption")
                        Spacer()
                        Image(systemName: store.secure ? "lock.fill" : "lock.open")
                            .foregroundStyle(store.secure ? Color.green : Color.secondary)
                        Text(store.secure ? "Forward secret" : "Waiting").foregroundStyle(.secondary)
                    }
                    if let p = store.pairing?.p {
                        HStack {
                            Text("Server Certificate")
                            Spacer()
                            Text(String(p.prefix(16)) + "…").font(.footnote.monospaced()).foregroundStyle(.secondary)
                        }
                    }
                }

                Section {
                    Toggle("Call Sound over the Internet", isOn: $store.internetAudio)
                    Toggle("Stay Connected in Background", isOn: $store.stayConnected)
                } header: {
                    Text("Calls")
                } footer: {
                    Text("Talk on this iPhone with only an internet connection. The Android phone puts the call on its speaker, so keep it in a quiet place and not deep in a bag. Staying connected in the background lets calls ring on the lock screen; it uses some battery.")
                }

                Section {
                    TextField("This iPhone's number", text: $store.myNumber)
                        .keyboardType(.phonePad)
                    Picker("Forward Calls", selection: $mode) {
                        ForEach(modes, id: \.0) { Text($0.1).tag($0.0) }
                    }
                    Button("Apply") { store.setForwarding(mode) }
                        .disabled(mode != "off" && store.myNumber.filter(\.isNumber).count < 7 || !store.androidOnline)
                    if let s = store.forwardStatus {
                        Text(s).font(.footnote).foregroundStyle(.secondary)
                    }
                } header: {
                    Text("Call Forwarding (needs a SIM in this iPhone)")
                } footer: {
                    Text("Instead of the internet, your carrier forwards calls for the Android SIM to this iPhone's own number. Best sound; your carrier may charge for forwarded minutes.")
                }

                Section {
                    Label("On the Android phone tap “Share internet” and turn on Wi‑Fi hotspot, Bluetooth tethering or USB tethering.", systemImage: "personalhotspot")
                    Label("Then on this iPhone join it in Settings › Wi‑Fi, or pair it in Settings › Bluetooth.", systemImage: "wifi")
                } header: {
                    Text("Use the Android Internet")
                } footer: {
                    Text("Messages and calls keep working on any connection: Wi‑Fi, hotspot or mobile data.")
                }

                Section("Privacy") {
                    Label("End-to-end encrypted (AES‑256‑GCM). The relay server only sees scrambled data.", systemImage: "lock.fill")
                    Label("A new key for every connection (X25519): a stolen key cannot open earlier messages or calls.", systemImage: "arrow.triangle.2.circlepath")
                    Label("The server's certificate is pinned: no one in between can pretend to be your server.", systemImage: "checkmark.shield.fill")
                    Label("The key is stored only in this iPhone's Keychain.", systemImage: "key.fill")
                }
                .font(.subheadline)

                Section {
                    Button("Unpair and Delete Data", role: .destructive) { confirmUnpair = true }
                }
            }
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .onAppear { mode = store.forwardMode }
            .confirmationDialog("Unpair this iPhone?", isPresented: $confirmUnpair, titleVisibility: .visible) {
                Button("Unpair", role: .destructive) { store.unpair() }
            } message: {
                Text("Messages, calls and contacts are deleted from this iPhone.")
            }
        }
    }

    private func statusRow(_ title: String, _ ok: Bool) -> some View {
        HStack {
            Text(title)
            Spacer()
            Circle().fill(ok ? Color.green : Color.red).frame(width: 8, height: 8)
            Text(ok ? "Online" : "Offline").foregroundStyle(.secondary)
        }
    }
}
