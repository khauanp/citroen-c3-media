import Foundation
import Network
import Security

struct WazeHTTPResource {
    let status: Int
    let headers: [String: String]
    let body: Data

    static let maximumBytes = 8 * 1024 * 1024

    /// HTTP/1.1 framing for a TLS connection requesting identity encoding.
    static func parse(_ packet: Data, headOnly: Bool = false) throws -> WazeHTTPResource {
        guard let boundary = packet.range(of: Data("\r\n\r\n".utf8)), boundary.lowerBound <= 65536 else {
            throw URLError(.badServerResponse)
        }
        let lines = String(decoding: packet[..<boundary.lowerBound], as: UTF8.self).components(separatedBy: "\r\n")
        let first = (lines.first ?? "").split(separator: " ")
        guard first.count >= 2, first[0].hasPrefix("HTTP/1."), let status = Int(first[1]), (200...599).contains(status) else {
            throw URLError(.badServerResponse)
        }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { throw URLError(.badServerResponse) }
            let name = String(line[..<colon]).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            if headers[name] != nil && ["content-length", "transfer-encoding"].contains(name) { throw URLError(.badServerResponse) }
            headers[name] = value
        }
        var body = Data(packet[boundary.upperBound...])
        if headOnly || status == 204 || status == 304 { body = Data() }
        else if let transfer = headers["transfer-encoding"] {
            guard transfer.lowercased() == "chunked", headers["content-length"] == nil else { throw URLError(.badServerResponse) }
            var decoded = Data()
            var offset = 0
            while true {
                guard offset < body.count,
                      let lineEnd = body.range(of: Data("\r\n".utf8), in: offset..<body.count) else { throw URLError(.badServerResponse) }
                guard let sizeText = String(decoding: body[offset..<lineEnd.lowerBound], as: UTF8.self).split(separator: ";", maxSplits: 1).first,
                      let size = Int(sizeText, radix: 16), size >= 0, size <= maximumBytes - decoded.count else { throw URLError(.dataLengthExceedsMaximum) }
                offset = lineEnd.upperBound
                if size == 0 {
                    guard offset + 2 <= body.count else { throw URLError(.badServerResponse) }
                    break
                }
                guard size <= body.count - offset - 2,
                      body[offset + size] == 13, body[offset + size + 1] == 10 else { throw URLError(.badServerResponse) }
                decoded.append(body[offset..<offset + size])
                offset += size + 2
            }
            body = decoded
        } else if let length = headers["content-length"] {
            guard let count = Int(length), count >= 0, count == body.count else { throw URLError(.badServerResponse) }
        }
        guard body.count <= maximumBytes else { throw URLError(.dataLengthExceedsMaximum) }
        // We requested identity. Do not mistake compressed bytes for JavaScript.
        if let encoding = headers["content-encoding"], encoding.lowercased() != "identity", !body.isEmpty {
            throw URLError(.cannotDecodeContentData)
        }
        headers.removeValue(forKey: "transfer-encoding")
        headers.removeValue(forKey: "content-length")
        headers.removeValue(forKey: "content-encoding")
        return WazeHTTPResource(status: status, headers: headers, body: body)
    }
}

@MainActor
final class WazeCellularClient {
    func fetch(_ url: URL, method: String, headers: [String: String], redirects: Int = 0) async throws -> WazeHTTPResource {
        guard WazeResourceRelay.allowed(url), redirects < 6 else { throw URLError(.unsupportedURL) }
        let request = WazeCellularRequest(url: url, method: method, headers: headers)
        let response = try await withTaskCancellationHandler(operation: {
            try Task.checkCancellation()
            return try await request.run()
        }, onCancel: { Task { @MainActor in request.cancel() } })
        if (300...399).contains(response.status), let location = response.headers["location"],
           let next = URL(string: location, relativeTo: url)?.absoluteURL {
            return try await fetch(next, method: method, headers: headers, redirects: redirects + 1)
        }
        return response
    }
}

@MainActor
private final class WazeCellularRequest {
    private let url: URL
    private let method: String
    private let headers: [String: String]
    private var connection: NWConnection?
    private var continuation: CheckedContinuation<WazeHTTPResource, Error>?
    private var received = Data()
    private var timer: DispatchWorkItem?
    private var cancelled = false

    init(url: URL, method: String, headers: [String: String]) { self.url = url; self.method = method; self.headers = headers }

    func run() async throws -> WazeHTTPResource {
        try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            if cancelled { finish(.failure(URLError(.cancelled))); return }
            guard let host = url.host else { finish(.failure(URLError(.badURL))); return }
            let tls = NWProtocolTLS.Options()
            sec_protocol_options_set_tls_server_name(tls.securityProtocolOptions, host)
            sec_protocol_options_add_tls_application_protocol(tls.securityProtocolOptions, "http/1.1")
            let parameters = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
            parameters.requiredInterfaceType = .cellular
            let connection = NWConnection(host: NWEndpoint.Host(host), port: 443, using: parameters)
            self.connection = connection
            connection.stateUpdateHandler = { [weak self] state in
                Task { @MainActor in
                    guard let self, self.continuation != nil else { return }
                    switch state {
                    case .ready: self.send()
                    case .failed(let error): self.finish(.failure(error))
                    case .cancelled: self.finish(.failure(URLError(.cancelled)))
                    default: break
                    }
                }
            }
            let timeout = DispatchWorkItem { [weak self] in self?.finish(.failure(URLError(.timedOut))) }
            timer = timeout
            DispatchQueue.main.asyncAfter(deadline: .now() + 22, execute: timeout)
            connection.start(queue: .main)
        }
    }

    private func send() {
        guard let parts = URLComponents(url: url, resolvingAgainstBaseURL: true), let host = url.host else { finish(.failure(URLError(.badURL))); return }
        let path = (parts.percentEncodedPath.isEmpty ? "/" : parts.percentEncodedPath) + (parts.percentEncodedQuery.map { "?" + $0 } ?? "")
        var text = "\(method) \(path) HTTP/1.1\r\nHost: \(host)\r\nConnection: close\r\nAccept-Encoding: identity\r\n"
        for name in ["accept", "accept-language", "user-agent", "origin", "referer", "range"] {
            if let value = headers[name], !value.contains("\r"), !value.contains("\n") { text += "\(name): \(value)\r\n" }
        }
        connection?.send(content: Data((text + "\r\n").utf8), completion: .contentProcessed { [weak self] error in
            Task { @MainActor in
                if let error { self?.finish(.failure(error)) } else { self?.receive() }
            }
        })
    }

    private func receive() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 32768) { [weak self] data, _, complete, error in
            Task { @MainActor in
                guard let self, self.continuation != nil else { return }
                if let error { self.finish(.failure(error)); return }
                if let data { self.received.append(data) }
                guard self.received.count <= WazeHTTPResource.maximumBytes + 1024 * 1024 else {
                    self.finish(.failure(URLError(.dataLengthExceedsMaximum))); return
                }
                if complete {
                    do { self.finish(.success(try WazeHTTPResource.parse(self.received, headOnly: self.method == "HEAD"))) }
                    catch { self.finish(.failure(error)) }
                } else { self.receive() }
            }
        }
    }

    func cancel() { cancelled = true; finish(.failure(URLError(.cancelled))) }
    private func finish(_ result: Result<WazeHTTPResource, Error>) {
        guard let continuation else { return }
        self.continuation = nil
        timer?.cancel(); timer = nil
        connection?.cancel(); connection = nil
        self.received = Data()
        continuation.resume(with: result)
    }
}
