import CoreBluetooth
import Foundation

/// Direct Bluetooth link to the Android phone (no server, no internet).
/// Finds the phone by its daily service UUID, reads the L2CAP channel number, then exchanges
/// length-prefixed frames over the channel. Frames are encrypted exactly like on the relay.
final class BleLink: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, StreamDelegate {
    static let psmChar = CBUUID(string: "0B1D6E7A-5C3E-4F2A-9D11-7A6C2F9E4B01")
    private static let maxFrame = 8 << 20

    var onOpen: (() -> Void)?
    var onFrame: ((String) -> Void)?
    var onClosed: (() -> Void)?

    private let uuidFor: (String) -> UUID
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var channel: CBL2CAPChannel?
    private var inBuf = Data()
    private var outBuf = Data()
    private var stopped = false
    private(set) var isOpen = false

    init(uuidFor: @escaping (String) -> UUID) {
        self.uuidFor = uuidFor
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    func stop() {
        stopped = true
        central.stopScan()
        close()
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        peripheral = nil
    }

    private var serviceUUIDs: [CBUUID] {
        // Today and yesterday (UTC), so the link survives midnight.
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = TimeZone(identifier: "UTC")
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        let now = Date()
        return [now, now.addingTimeInterval(-86400)].map { CBUUID(nsuuid: uuidFor(f.string(from: $0))) }
    }

    private func scan() {
        guard !stopped, central.state == .poweredOn, peripheral == nil else { return }
        central.scanForPeripherals(withServices: serviceUUIDs, options: nil)
    }

    // MARK: central

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn { scan() } else { close() }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        central.stopScan()
        self.peripheral = peripheral
        peripheral.delegate = self
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices(serviceUUIDs)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        retry()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        retry()
    }

    private func retry() {
        close()
        peripheral = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.scan() }
    }

    // MARK: peripheral

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first else { central.cancelPeripheralConnection(peripheral); return }
        peripheral.discoverCharacteristics([Self.psmChar], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let c = service.characteristics?.first(where: { $0.uuid == Self.psmChar }) else {
            central.cancelPeripheralConnection(peripheral); return
        }
        peripheral.readValue(for: c)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let v = characteristic.value, v.count >= 2 else { central.cancelPeripheralConnection(peripheral); return }
        let psm = CBL2CAPPSM(UInt16(v[v.startIndex]) | UInt16(v[v.startIndex + 1]) << 8)
        peripheral.openL2CAPChannel(psm)
    }

    func peripheral(_ peripheral: CBPeripheral, didOpen channel: CBL2CAPChannel?, error: Error?) {
        guard let channel, error == nil else { central.cancelPeripheralConnection(peripheral); return }
        self.channel = channel
        for s in [channel.inputStream as Stream, channel.outputStream as Stream] {
            s.delegate = self
            s.schedule(in: .main, forMode: .default)
            s.open()
        }
        isOpen = true
        onOpen?()
    }

    // MARK: frames

    func send(_ frame: String) {
        guard isOpen else { return }
        let body = Data(frame.utf8)
        var len = UInt32(body.count).bigEndian
        outBuf.append(Data(bytes: &len, count: 4))
        outBuf.append(body)
        pump()
    }

    private func pump() {
        guard let out = channel?.outputStream, out.hasSpaceAvailable, !outBuf.isEmpty else { return }
        let n = outBuf.withUnsafeBytes { out.write($0.bindMemory(to: UInt8.self).baseAddress!, maxLength: outBuf.count) }
        if n > 0 { outBuf.removeFirst(n) }
    }

    func stream(_ aStream: Stream, handle eventCode: Stream.Event) {
        switch eventCode {
        case .hasBytesAvailable:
            guard let input = aStream as? InputStream else { return }
            var buf = [UInt8](repeating: 0, count: 4096)
            while input.hasBytesAvailable {
                let n = input.read(&buf, maxLength: buf.count)
                if n <= 0 { break }
                inBuf.append(buf, count: n)
            }
            drain()
        case .hasSpaceAvailable:
            pump()
        case .errorOccurred, .endEncountered:
            if let p = peripheral { central.cancelPeripheralConnection(p) } else { close() }
        default:
            break
        }
    }

    private func drain() {
        while inBuf.count >= 4 {
            let s = inBuf.startIndex
            let len = Int(inBuf[s]) << 24 | Int(inBuf[s + 1]) << 16 | Int(inBuf[s + 2]) << 8 | Int(inBuf[s + 3])
            guard len > 0, len <= Self.maxFrame else { if let p = peripheral { central.cancelPeripheralConnection(p) }; return }
            guard inBuf.count >= 4 + len else { return }
            let body = inBuf.subdata(in: (s + 4)..<(s + 4 + len))
            inBuf.removeFirst(4 + len)
            if let text = String(data: body, encoding: .utf8) { onFrame?(text) }
        }
    }

    private func close() {
        let wasOpen = isOpen
        isOpen = false
        channel?.inputStream.close()
        channel?.outputStream.close()
        channel = nil
        inBuf.removeAll()
        outBuf.removeAll()
        if wasOpen { onClosed?() }
    }
}
