import CoreLocation
import XCTest
@testable import C3Link

final class RouteIntegrityTests: XCTestCase {
    func testCRC32ReferenceVector() {
        XCTAssertEqual(RouteIntegrity.crc32(Data("123456789".utf8)), 0xcbf4_3926)
    }

    func testLongRouteIsEncodedWithoutPointLoss() {
        let coordinates = (0..<25_000).map { index in
            let progress = Double(index) / 24_999
            return CLLocationCoordinate2D(
                latitude: -25.45 + progress * 1.55 + sin(Double(index) * 0.07) * 0.000_08,
                longitude: -49.25 + progress * 0.55 + cos(Double(index) * 0.09) * 0.000_08
            )
        }
        let polyline = PolylineEncoder.encode(coordinates, precision: 5)
        XCTAssertGreaterThan(polyline.utf8.count, 28_000)
        let decoded = PolylineEncoder.decode(polyline, precision: 5)
        XCTAssertEqual(decoded.count, coordinates.count)

        let identifier = RouteIntegrity.identifier(polyline: polyline, coordinates: decoded)
        XCTAssertNotNil(identifier)
        XCTAssertLessThanOrEqual(identifier?.count ?? .max, 80)
        XCTAssertTrue(identifier?.hasPrefix("r3_") == true)
    }

    func testIdentityChangesWhenAnyPolylineByteChanges() {
        let coordinates = [
            CLLocationCoordinate2D(latitude: -25.4, longitude: -49.2),
            CLLocationCoordinate2D(latitude: -25.3, longitude: -49.1),
        ]
        let first = PolylineEncoder.encode(coordinates)
        let second = first + "?"
        XCTAssertNotEqual(
            RouteIntegrity.identifier(polyline: first, coordinates: coordinates),
            RouteIntegrity.identifier(polyline: second, coordinates: coordinates)
        )
    }
}
