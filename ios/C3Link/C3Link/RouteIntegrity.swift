import CoreLocation
import Foundation

enum RouteIntegrity {
    static let identifierPrefix = "r3"
    static let maximumPolylineBytes = 350_000

    static func identifier(
        polyline: String,
        coordinates: [CLLocationCoordinate2D]
    ) -> String? {
        guard coordinates.count >= 2,
              polyline.utf8.count <= maximumPolylineBytes else { return nil }
        return [
            identifierPrefix,
            String(format: "%08x", crc32(Data(polyline.utf8))),
            String(coordinates.count),
        ].joined(separator: "_")
    }

    static func crc32(_ data: Data) -> UInt32 {
        var value = UInt32.max
        for byte in data {
            value ^= UInt32(byte)
            for _ in 0..<8 {
                value = (value >> 1) ^ (value & 1 == 1 ? 0xedb8_8320 : 0)
            }
        }
        return value ^ UInt32.max
    }
}
