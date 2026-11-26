import CryptoKit
import Foundation

/// WebSocket link to the relay, with automatic reconnect. All state changes happen on the main queue.
final class RelayConnection {
    private let url: URL
    private let room: String
    private let pinDelegate: PinDelegate
    private let session: URLSession
    private var task: URLSessionWebSocketTask?
    private var pingTimer: Timer?
    private var stopped = false
    private var retry = 0

    var onText: ((String) -> Void)?
    var onState: ((Bool) -> Void)?

    /// [pin]: SHA-256 of the relay's TLS certificate. With wss:// only that certificate is accepted.
    init(url: URL, room: String, pin: String?) {
        self.url = url
        self.room = room
        pinDelegate = PinDelegate(pin: pin)
        session = URLSession(configuration: .default, delegate: pinDelegate, delegateQueue: nil)
    }

    func start() {
        stopped = false
        if task == nil { connect() }
    }

    func stop() {
        stopped = true
        pingTimer?.invalidate()
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        onState?(false)
    }

    func send(_ text: String) {
        task?.send(.string(text)) { _ in }
    }

    private func connect() {
        let t = session.webSocketTask(with: url)
        t.maximumMessageSize = 8 * 1024 * 1024 // contacts + SMS batches
        task = t
        t.resume()
        t.send(.string("{\"join\":\"\(room)\",\"role\":\"i\"}")) { [weak self] error in
            DispatchQueue.main.async {
                guard let self, t === self.task else { return }
                if error != nil { self.fail(t) } else { self.retry = 0; self.onState?(true) }
            }
        }
        receive(t)
        pingTimer?.invalidate()
        pingTimer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
            t.sendPing { error in
                if error != nil { DispatchQueue.main.async { self?.fail(t) } }
            }
        }
    }

    private func receive(_ t: URLSessionWebSocketTask) {
        t.receive { [weak self] result in
            DispatchQueue.main.async {
                guard let self, t === self.task else { return }
                switch result {
                case .success(.string(let text)):
                    self.onText?(text)
                    self.receive(t)
                case .success:
                    self.receive(t)
                case .failure:
                    self.fail(t)
                }
            }
        }
    }

    private func fail(_ t: URLSessionWebSocketTask) {
        guard t === task else { return }
        t.cancel()
        task = nil
        pingTimer?.invalidate()
        onState?(false)
        guard !stopped else { return }
        let delay = min(30.0, pow(2.0, Double(min(retry, 5))))
        retry += 1
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, !self.stopped, self.task == nil else { return }
            self.connect()
        }
    }
}

/// Accepts the relay's self-signed certificate only if its fingerprint matches the pin from the QR code.
final class PinDelegate: NSObject, URLSessionDelegate {
    private let pin: String?
    init(pin: String?) { self.pin = pin?.lowercased() }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        guard let pin else {
            completionHandler(.performDefaultHandling, nil) // no pin: normal certificate checks
            return
        }
        let chain = (SecTrustCopyCertificateChain(trust) as? [SecCertificate]) ?? []
        guard let leaf = chain.first else { completionHandler(.cancelAuthenticationChallenge, nil); return }
        let der = SecCertificateCopyData(leaf) as Data
        let fp = SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
        if fp == pin {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}
