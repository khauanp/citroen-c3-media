import Combine
import Foundation
import Network
import UIKit

final class C3LinkTransport: ObservableObject {
    static let protocolVersion = 2
    static let tabletHost = NWEndpoint.Host("192.168.43.1")
    static let tabletPort = NWEndpoint.Port(rawValue: 30_303)!

    @Published private(set) var isReady = false
    @Published private(set) var statusText = "Entre na rede Citroen-C3"
    var onReady: (() -> Void)?
    var onTileRequest: (([MapTileKey]) -> Void)?
    var onRouteAcknowledged: ((String?) -> Void)?
    var onRouteNeeded: ((String?) -> Void)?
    var onRouteRejected: ((String?) -> Void)?

    let deviceId: String
    private let queue = DispatchQueue(label: "com.c3media.c3link.udp", qos: .userInitiated)
    private var connection: NWConnection?
    private var helloTimer: DispatchSourceTimer?
    private var reconnectScheduled = false
    private let readinessLock = NSLock()
    private var protocolReadyStorage = false
    private var supportsRoutePartsStorage = false

    private var protocolReady: Bool {
        get {
            readinessLock.lock()
            defer { readinessLock.unlock() }
            return protocolReadyStorage
        }
        set {
            readinessLock.lock()
            protocolReadyStorage = newValue
            readinessLock.unlock()
        }
    }

    private var supportsRouteParts: Bool {
        get {
            readinessLock.lock()
            defer { readinessLock.unlock() }
            return supportsRoutePartsStorage
        }
        set {
            readinessLock.lock()
            supportsRoutePartsStorage = newValue
            readinessLock.unlock()
        }
    }

    init() {
        let defaults = UserDefaults.standard
        if let saved = defaults.string(forKey: "c3link.device-id") {
            deviceId = saved
        } else {
            let generated = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
            defaults.set(generated, forKey: "c3link.device-id")
            deviceId = generated
        }
        connect()
    }

    deinit {
        send(["type": "goodbye"])
        helloTimer?.cancel()
        connection?.cancel()
    }

    func connect() {
        connection?.cancel()
        reconnectScheduled = false
        protocolReady = false
        supportsRouteParts = false
        let parameters = NWParameters.udp
        parameters.allowLocalEndpointReuse = true
        let newConnection = NWConnection(host: Self.tabletHost, port: Self.tabletPort, using: parameters)
        connection = newConnection
        newConnection.stateUpdateHandler = { [weak self, weak newConnection] state in
            guard let self, let activeConnection = newConnection, self.connection === activeConnection else { return }
            DispatchQueue.main.async {
                switch state {
                case .ready:
                    self.statusText = "Solicitando autorização no tablet…"
                    self.sendHello()
                case .waiting:
                    self.protocolReady = false
                    self.isReady = false
                    self.statusText = "Conecte-se à rede Citroen-C3"
                case .failed:
                    self.protocolReady = false
                    self.isReady = false
                    self.statusText = "Sem resposta do tablet"
                    self.scheduleReconnect()
                case .cancelled:
                    self.protocolReady = false
                    self.isReady = false
                default:
                    break
                }
            }
        }
        newConnection.start(queue: queue)
        receiveNext(on: newConnection)
        startHelloTimer()
    }

    func sendRoute(
        routeId: String,
        destination: String,
        polyline: String,
        distanceMeters: Double,
        durationSeconds: Double
    ) {
        guard supportsRouteParts else {
            sendLegacyRoute(
                routeId: routeId,
                destination: destination,
                polyline: polyline,
                distanceMeters: distanceMeters,
                durationSeconds: durationSeconds
            )
            return
        }

        let bytes = Array(polyline.utf8)
        guard !bytes.isEmpty else { return }
        let partCount = Int(ceil(Double(bytes.count) / Double(Self.routePartBytes)))
        guard partCount > 0, partCount <= Self.maximumRouteParts else { return }
        let safeDestination = String(destination.prefix(100))
        for part in 0..<partCount {
            let lower = part * Self.routePartBytes
            let upper = min(bytes.count, lower + Self.routePartBytes)
            let value = String(decoding: bytes[lower..<upper], as: UTF8.self)
            let payload: [String: Any] = [
                "type": "route-part",
                "routeId": routeId,
                "destination": safeDestination,
                "part": part,
                "parts": partCount,
                "data": value,
                "precision": 5,
                "distanceMeters": distanceMeters,
                "durationSeconds": durationSeconds,
            ]
            // Pace the burst just enough to avoid overflowing the old tablet's
            // UDP receive queue. Route packets retain priority over tile data.
            queue.asyncAfter(deadline: .now() + Double(part) * 0.004) { [weak self] in
                self?.send(payload)
            }
        }
    }

    private func sendLegacyRoute(
        routeId: String,
        destination: String,
        polyline: String,
        distanceMeters: Double,
        durationSeconds: Double
    ) {
        send([
            "type": "route",
            "routeId": routeId,
            "destination": destination,
            "polyline": polyline,
            "precision": 5,
            "distanceMeters": distanceMeters,
            "durationSeconds": durationSeconds,
        ])
    }

    func sendPosition(
        routeId: String,
        latitude: Double,
        longitude: Double,
        speedMps: Double,
        course: Double,
        instruction: String,
        maneuver: String,
        stepDistanceMeters: Double,
        remainingDistanceMeters: Double,
        remainingSeconds: Double
    ) {
        send([
            "type": "position",
            "routeId": routeId,
            "latitude": latitude,
            "longitude": longitude,
            "speedMps": max(0, speedMps),
            "course": course,
            "instruction": instruction,
            "maneuver": maneuver,
            "stepDistanceMeters": stepDistanceMeters,
            "remainingDistanceMeters": remainingDistanceMeters,
            "remainingSeconds": remainingSeconds,
        ])
    }

    func sendTileChunk(
        key: MapTileKey,
        transferId: String,
        part: Int,
        partCount: Int,
        data: Data,
        expiresAtEpochSeconds: Int64
    ) {
        send([
            "type": "tile-chunk",
            "z": key.zoom,
            "x": key.x,
            "y": key.y,
            "transferId": transferId,
            "part": part,
            "parts": partCount,
            "data": data.base64EncodedString(),
            "expiresAt": expiresAtEpochSeconds,
        ])
    }

    func sendTileError(_ key: MapTileKey) {
        send(["type": "tile-error", "z": key.zoom, "x": key.x, "y": key.y])
    }

    func sendStop() {
        send(["type": "stop"])
        // UDP is intentionally lightweight, but the stop command must feel
        // immediate even while map-tile chunks are in flight.
        queue.asyncAfter(deadline: .now() + 0.12) { [weak self] in
            self?.send(["type": "stop"])
        }
        queue.asyncAfter(deadline: .now() + 0.35) { [weak self] in
            self?.send(["type": "stop"])
        }
    }

    private func sendHello() {
        send([
            "type": "hello",
            "name": UIDevice.current.name,
            "model": UIDevice.current.model,
        ])
    }

    private func send(_ payload: [String: Any]) {
        guard let connection else { return }
        var message = payload
        message["version"] = Self.protocolVersion
        message["deviceId"] = deviceId
        guard JSONSerialization.isValidJSONObject(message),
              let data = try? JSONSerialization.data(withJSONObject: message),
              data.count <= 60_000 else { return }
        connection.send(content: data, completion: .contentProcessed { _ in })
    }

    private func startHelloTimer() {
        helloTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 1, repeating: 3)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            if self.protocolReady {
                self.send(["type": "ping"])
            } else {
                self.sendHello()
            }
        }
        timer.resume()
        helloTimer = timer
    }

    private func receiveNext(on activeConnection: NWConnection) {
        activeConnection.receiveMessage { [weak self, weak activeConnection] data, _, _, _ in
            guard let self, let activeConnection else { return }
            if let data,
               let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               (object["version"] as? Int) == Self.protocolVersion {
                self.handle(object)
            }
            if self.connection === activeConnection { self.receiveNext(on: activeConnection) }
        }
    }

    private func handle(_ object: [String: Any]) {
        if object["type"] as? String == "tile-request", let rawTiles = object["tiles"] as? [[String: Any]] {
            let tiles = rawTiles.compactMap { item -> MapTileKey? in
                guard let zoom = item["z"] as? Int,
                      let x = item["x"] as? Int,
                      let y = item["y"] as? Int else { return nil }
                let key = MapTileKey(zoom: zoom, x: x, y: y)
                return key.isValid ? key : nil
            }
            if !tiles.isEmpty { onTileRequest?(tiles) }
            return
        }
        guard let status = object["status"] as? String else { return }
        if status == "ready", let capabilities = object["capabilities"] as? [String] {
            supportsRouteParts = capabilities.contains("route-parts")
        }
        switch status {
        case "ready", "route-ok", "route-missing", "route-invalid", "pong", "stopped": protocolReady = true
        case "denied", "busy", "pair-first": protocolReady = false
        default: break
        }
        let acknowledgedRouteId = object["routeId"] as? String
        DispatchQueue.main.async {
            switch status {
            case "ready", "route-ok", "pong", "stopped":
                let becameReady = !self.isReady
                self.isReady = true
                self.statusText = "Ligação direta ativa"
                if becameReady { self.onReady?() }
            case "denied":
                self.isReady = false
                self.statusText = "Conexão negada no tablet"
            case "busy":
                self.isReady = false
                self.statusText = "Outro celular já está usando o C3 Link"
            case "pair-first":
                self.isReady = false
                self.statusText = "Autorize o iPhone no tablet"
            case "route-invalid":
                self.statusText = "A rota recebida foi recusada"
                self.onRouteRejected?(acknowledgedRouteId)
            case "route-missing":
                self.isReady = true
                self.statusText = "Reenviando a rota ao tablet…"
                self.onRouteNeeded?(acknowledgedRouteId)
            default:
                break
            }
            if status == "route-ok" {
                self.onRouteAcknowledged?(acknowledgedRouteId)
            }
        }
    }

    private func scheduleReconnect() {
        guard !reconnectScheduled else { return }
        reconnectScheduled = true
        queue.asyncAfter(deadline: .now() + 3) { [weak self] in
            DispatchQueue.main.async { self?.connect() }
        }
    }

    private static let routePartBytes = 480
    private static let maximumRouteParts = 64
}
