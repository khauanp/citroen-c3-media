import CoreImage
import Foundation
import UIKit

/** Downloads only tiles currently visible on the tablet and relays them locally. */
final class MapTileRelay {
    private weak var transport: C3LinkTransport?
    private let operationQueue: OperationQueue
    private let session: URLSession
    private let imageContext = CIContext(options: [.cacheIntermediates: false])
    private let lock = NSLock()
    private var inFlight: Set<MapTileKey> = []
    private var generation = 0

    init(transport: C3LinkTransport) {
        self.transport = transport
        operationQueue = OperationQueue()
        operationQueue.name = "com.c3media.c3link.tiles"
        operationQueue.qualityOfService = .utility
        operationQueue.maxConcurrentOperationCount = 2

        let configuration = URLSessionConfiguration.default
        configuration.urlCache = URLCache(
            memoryCapacity: 8 * 1024 * 1024,
            diskCapacity: 128 * 1024 * 1024,
            diskPath: "c3-link-map-tiles"
        )
        configuration.requestCachePolicy = .useProtocolCachePolicy
        configuration.timeoutIntervalForRequest = 18
        configuration.timeoutIntervalForResource = 30
        configuration.httpMaximumConnectionsPerHost = 2
        configuration.waitsForConnectivity = true
        session = URLSession(configuration: configuration)
    }

    func request(_ keys: [MapTileKey]) {
        lock.lock()
        let requestGeneration = generation
        lock.unlock()
        for key in keys where key.isValid {
            lock.lock()
            let inserted = inFlight.insert(key).inserted
            lock.unlock()
            guard inserted else { continue }
            operationQueue.addOperation { [weak self] in
                guard let self else { return }
                let semaphore = DispatchSemaphore(value: 0)
                Task {
                    await self.fetchAndSend(key, generation: requestGeneration)
                    semaphore.signal()
                }
                _ = semaphore.wait(timeout: .now() + 35)
                self.lock.lock()
                if requestGeneration == self.generation {
                    self.inFlight.remove(key)
                }
                self.lock.unlock()
            }
        }
    }

    func cancelPending() {
        lock.lock()
        generation += 1
        inFlight.removeAll()
        lock.unlock()
        operationQueue.cancelAllOperations()
        session.getAllTasks { tasks in tasks.forEach { $0.cancel() } }
    }

    private func fetchAndSend(_ key: MapTileKey, generation requestGeneration: Int) async {
        guard isCurrent(requestGeneration) else { return }
        guard let url = URL(string: "https://tile.openstreetmap.org/\(key.zoom)/\(key.x)/\(key.y).png") else {
            transport?.sendTileError(key)
            return
        }
        var request = URLRequest(url: url)
        request.cachePolicy = .useProtocolCachePolicy
        request.setValue(OpenNavigationService.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("image/png", forHTTPHeaderField: "Accept")
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse,
                  (200...299).contains(http.statusCode),
                  !data.isEmpty,
                  data.count <= Self.maximumTileBytes,
                  data.starts(with: Self.pngSignature) else {
                transport?.sendTileError(key)
                return
            }
            guard isCurrent(requestGeneration) else { return }
            let displayData = highContrastPNG(from: data) ?? data
            let expiry = expiryEpochSeconds(from: http)
            let transferId = UUID().uuidString
            let partCount = Int(ceil(Double(displayData.count) / Double(Self.chunkBytes)))
            guard partCount > 0, partCount <= 512 else {
                transport?.sendTileError(key)
                return
            }
            for part in 0..<partCount {
                guard isCurrent(requestGeneration) else { return }
                let lower = part * Self.chunkBytes
                let upper = min(displayData.count, lower + Self.chunkBytes)
                let chunk = displayData.subdata(in: lower..<upper)
                transport?.sendTileChunk(
                    key: key,
                    transferId: transferId,
                    part: part,
                    partCount: partCount,
                    data: chunk,
                    expiresAtEpochSeconds: expiry
                )
                // Keep route and GPS packets responsive while a map tile is
                // being transferred over the tablet hotspot.
                try? await Task.sleep(nanoseconds: 1_500_000)
            }
        } catch {
            // Cancellation belongs to an old route/generation. Do not poison a
            // newly started route with a stale tile-error backoff.
            if isCurrent(requestGeneration) {
                transport?.sendTileError(key)
            }
        }
    }

    private func isCurrent(_ requestGeneration: Int) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return requestGeneration == generation
    }

    private func highContrastPNG(from data: Data) -> Data? {
        guard let image = CIImage(data: data) else { return nil }
        let contrasted = image.applyingFilter("CIColorControls", parameters: [
            kCIInputSaturationKey: 0.78,
            kCIInputContrastKey: 1.42,
        ])
        let toned = contrasted.applyingFilter("CIColorMatrix", parameters: [
            "inputRVector": CIVector(x: 0.76, y: 0, z: 0, w: 0),
            "inputGVector": CIVector(x: 0, y: 0.76, z: 0, w: 0),
            "inputBVector": CIVector(x: 0, y: 0, z: 0.76, w: 0),
            "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 1),
            "inputBiasVector": CIVector(x: 0.039, y: 0.039, z: 0.039, w: 0),
        ])
        guard let cgImage = imageContext.createCGImage(toned, from: image.extent),
              let png = UIImage(cgImage: cgImage).pngData(),
              png.count <= Self.maximumTileBytes else { return nil }
        return png
    }

    private func expiryEpochSeconds(from response: HTTPURLResponse) -> Int64 {
        let now = Date()
        if let cacheControl = response.value(forHTTPHeaderField: "Cache-Control"),
           let range = cacheControl.range(of: #"max-age=(\d+)"#, options: .regularExpression) {
            let match = String(cacheControl[range])
            if let seconds = TimeInterval(match.split(separator: "=").last ?? "") {
                return Int64(now.addingTimeInterval(max(seconds, Self.minimumCacheSeconds)).timeIntervalSince1970)
            }
        }
        if let expires = response.value(forHTTPHeaderField: "Expires"),
           let date = Self.httpDateFormatter.date(from: expires) {
            return Int64(max(date.timeIntervalSince1970, now.addingTimeInterval(Self.minimumCacheSeconds).timeIntervalSince1970))
        }
        return Int64(now.addingTimeInterval(Self.minimumCacheSeconds).timeIntervalSince1970)
    }

    private static let httpDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "EEE',' dd MMM yyyy HH':'mm':'ss z"
        return formatter
    }()

    private static let pngSignature = Data([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
    private static let chunkBytes = 900
    private static let maximumTileBytes = 512 * 1024
    private static let minimumCacheSeconds: TimeInterval = 7 * 24 * 60 * 60
}
