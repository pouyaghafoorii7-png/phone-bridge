import AVFoundation
import CallKit

/// Shows calls from the Android SIM with the iPhone's own call screen (also on the lock screen).
final class CallKitManager: NSObject, CXProviderDelegate {
    private let provider: CXProvider
    private let controller = CXCallController()
    private(set) var current: UUID?

    var onAnswer: (() -> Void)?
    var onEnd: (() -> Void)?
    var onStartOutgoing: ((String) -> Void)?
    var onMute: ((Bool) -> Void)?
    var onAudioSession: ((Bool) -> Void)?

    override init() {
        let config = CXProviderConfiguration()
        config.supportsVideo = false
        config.maximumCallGroups = 1
        config.maximumCallsPerCallGroup = 1
        config.supportedHandleTypes = [.phoneNumber]
        config.includesCallsInRecents = false
        provider = CXProvider(configuration: config)
        super.init()
        provider.setDelegate(self, queue: nil)
    }

    private func update(number: String, name: String?) -> CXCallUpdate {
        let u = CXCallUpdate()
        u.remoteHandle = CXHandle(type: .phoneNumber, value: number)
        u.localizedCallerName = name
        u.hasVideo = false
        u.supportsHolding = false
        u.supportsGrouping = false
        u.supportsUngrouping = false
        u.supportsDTMF = false
        return u
    }

    func reportIncoming(number: String, name: String?, completion: @escaping (Bool) -> Void) {
        let id = UUID()
        current = id
        provider.reportNewIncomingCall(with: id, update: update(number: number, name: name)) { [weak self] error in
            DispatchQueue.main.async {
                if error != nil { self?.current = nil }
                completion(error == nil)
            }
        }
    }

    func startOutgoing(number: String, completion: @escaping (Bool) -> Void) {
        let id = UUID()
        current = id
        let action = CXStartCallAction(call: id, handle: CXHandle(type: .phoneNumber, value: number))
        controller.request(CXTransaction(action: action)) { [weak self] error in
            DispatchQueue.main.async {
                if error != nil { self?.current = nil }
                completion(error == nil)
            }
        }
    }

    func connected() {
        if let id = current { provider.reportOutgoingCall(with: id, connectedAt: nil) }
    }

    func remoteEnded() {
        guard let id = current else { return }
        provider.reportCall(with: id, endedAt: nil, reason: .remoteEnded)
        current = nil
    }

    func answerFromApp() {
        guard let id = current else { return }
        controller.request(CXTransaction(action: CXAnswerCallAction(call: id))) { _ in }
    }

    func endFromApp() {
        guard let id = current else { return }
        controller.request(CXTransaction(action: CXEndCallAction(call: id))) { _ in }
    }

    func setMuted(_ muted: Bool) {
        guard let id = current else { return }
        controller.request(CXTransaction(action: CXSetMutedCallAction(call: id, muted: muted))) { _ in }
    }

    // MARK: CXProviderDelegate (main queue)

    func providerDidReset(_ provider: CXProvider) {
        current = nil
        onAudioSession?(false)
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        CallAudio.configureSession()
        onAnswer?()
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        CallAudio.configureSession()
        onStartOutgoing?(action.handle.value)
        provider.reportOutgoingCall(with: action.callUUID, startedConnectingAt: nil)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        current = nil
        onEnd?()
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        onMute?(action.isMuted)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) { onAudioSession?(true) }
    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) { onAudioSession?(false) }
}
