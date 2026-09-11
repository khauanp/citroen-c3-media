import Foundation
import Network

/// HTTP resources for the tablet WebView; the existing UDP/audio link is untouched.
@MainActor
final class WazeResourceRelay {
    private let token = UUID().uuidString
    private var listener: NWListener?
    private var ready = false
    private var connections: [UUID: NWConnection] = [:]
    private var tasks: [UUID: Task<Void, Never>] = [:]
    private let session: URLSession
    private let cellular = WazeCellularClient()

    init() {
        let config = URLSessionConfiguration.ephemeral
        config.allowsCellularAccess = true
        config.allowsExpensiveNetworkAccess = true
        config.waitsForConnectivity = false
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 25
        config.httpMaximumConnectionsPerHost = 2
        session = URLSession(configuration: config)
    }

    static func allowed(_ url: URL) -> Bool {
        guard url.scheme?.lowercased() == "https", url.user == nil, url.password == nil,
              url.port == nil || url.port == 443, let host = url.host?.lowercased(),
              url.absoluteString.count <= 8192 else { return false }
        return ["waze.com", "wazestatic.com", "gstatic.com", "googleapis.com", "google.com"]
            .contains { host == $0 || host.hasSuffix("." + $0) }
    }

    func show(latitude: Double?, longitude: Double?) async throws {
        if listener == nil {
            let parameters = NWParameters.tcp
            parameters.requiredInterfaceType = .wifi
            let server = try NWListener(using: parameters, on: 8081)
            server.stateUpdateHandler = { [weak self] state in
                Task { @MainActor in
                    self?.ready = { if case .ready = state { return true }; return false }()
                }
            }
            server.newConnectionHandler = { [weak self] connection in
                Task { @MainActor in self?.accept(connection) }
            }
            listener = server
            server.start(queue: .main)
        }
        for _ in 0..<30 {
            if ready { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        guard ready else { throw URLError(.cannotConnectToHost) }
        var map = URLComponents(string: "https://embed.waze.com/iframe")!
        map.queryItems = [URLQueryItem(name: "zoom", value: "14")]
        if let latitude, let longitude {
            map.queryItems! += [URLQueryItem(name: "lat", value: String(latitude)), URLQueryItem(name: "lon", value: String(longitude))]
        }
        var endpoint = URLComponents(string: "http://\(C3LinkTransport.tabletHost):8080/set-route")!
        endpoint.queryItems = [URLQueryItem(name: "waze_url", value: map.url!.absoluteString)]
        var request = URLRequest(url: endpoint.url!)
        request.setValue(token, forHTTPHeaderField: "X-C3-Relay-Token")
        request.timeoutInterval = 5
        let (_, response) = try await session.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
    }

    private func accept(_ connection: NWConnection) {
        guard connections.count < 4 else { connection.cancel(); return }
        let id = UUID()
        connections[id] = connection
        connection.start(queue: .main)
        // Also closes a peer that connects but never finishes its request headers.
        DispatchQueue.main.asyncAfter(deadline: .now() + 30) { [weak self] in self?.finish(id) }
        receive(id, Data())
    }

    private func receive(_ id: UUID, _ pending: Data) {
        guard let connection = connections[id] else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, complete, error in
            Task { @MainActor in
                guard let self else { return }
                var buffer = pending
                if let data { buffer.append(data) }
                guard buffer.count <= 16384, error == nil else { self.finish(id); return }
                if let end = buffer.range(of: Data("\r\n\r\n".utf8)) {
                    self.process(id, String(decoding: buffer[..<end.lowerBound], as: UTF8.self))
                } else if complete { self.finish(id) }
                else { self.receive(id, buffer) }
            }
        }
    }

    private func process(_ id: UUID, _ text: String) {
        let lines = text.components(separatedBy: "\r\n")
        let first = (lines.first ?? "").split(separator: " ")
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            headers[String(line[..<colon]).lowercased()] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        guard first.count == 3, ["GET", "HEAD"].contains(String(first[0])),
              headers["x-c3-relay-token"] == token,
              let components = URLComponents(string: "http://localhost" + String(first[1])),
              components.path == "/fetch-proxy",
              let items = components.queryItems?.filter({ $0.name == "url" }), items.count == 1,
              let value = items[0].value, let url = URL(string: value), Self.allowed(url) else {
            send(id, status: 403, headers: [:], body: Data("Requisicao recusada".utf8)); return
        }
        let method = String(first[0])
        tasks[id] = Task { [weak self] in
            guard let self else { return }
            do {
                let resource = try await cellular.fetch(url, method: method, headers: headers)
                guard !(300...399).contains(resource.status) else { throw URLError(.badServerResponse) }
                var output: [String: String] = [:]
                for name in ["Content-Type", "Content-Language", "Cache-Control", "Expires", "Access-Control-Allow-Origin",
                    "Access-Control-Allow-Credentials", "Access-Control-Expose-Headers", "Content-Security-Policy",
                    "X-Frame-Options", "Content-Range", "Accept-Ranges", "Vary"] {
                    if let value = resource.headers[name.lowercased()], !value.contains("\r"), !value.contains("\n") { output[name] = value }
                }
                send(id, status: resource.status, headers: output, body: resource.body)
            } catch {
                send(id, status: 502, headers: ["Content-Type": "text/plain; charset=utf-8"], body: Data("Sem recurso pela internet do iPhone".utf8))
            }
        }
    }

    private func send(_ id: UUID, status: Int, headers: [String: String], body: Data) {
        guard let connection = connections[id] else { return }
        var head = "HTTP/1.1 \(status) Proxy\r\nConnection: close\r\nContent-Length: \(body.count)\r\n"
        for (name, value) in headers { head += "\(name): \(value)\r\n" }
        var packet = Data((head + "\r\n").utf8)
        packet.append(body)
        connection.send(content: packet, completion: .contentProcessed { [weak self] _ in
            Task { @MainActor in self?.finish(id) }
        })
    }

    private func finish(_ id: UUID) {
        tasks.removeValue(forKey: id)?.cancel()
        connections.removeValue(forKey: id)?.cancel()
    }
}
