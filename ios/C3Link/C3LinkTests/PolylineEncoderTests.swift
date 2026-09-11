import CoreLocation
import XCTest
@testable import C3Link

final class PolylineEncoderTests: XCTestCase {
    func testEncodeDecodeRoundTripKeepsEveryRoutePoint() {
        let route = (0..<2_000).map { index in
            CLLocationCoordinate2D(
                latitude: -25.40 + Double(index) * 0.000_01,
                longitude: -49.25 + sin(Double(index) / 30.0) * 0.000_2
            )
        }

        let decoded = PolylineEncoder.decode(PolylineEncoder.encode(route))

        XCTAssertEqual(decoded.count, route.count)
        XCTAssertEqual(decoded.first!.latitude, route.first!.latitude, accuracy: 0.000_01)
        XCTAssertEqual(decoded.last!.longitude, route.last!.longitude, accuracy: 0.000_01)
    }

    func testSimplifierKeepsAStreetCornerThatUniformSamplingWouldMiss() {
        var route: [CLLocationCoordinate2D] = []
        for index in 0...250 {
            route.append(
                CLLocationCoordinate2D(
                    latitude: -25.40,
                    longitude: -49.25 + Double(index) / 250.0 * 0.01
                )
            )
        }
        for index in 1...1_750 {
            route.append(
                CLLocationCoordinate2D(
                    latitude: -25.40 + Double(index) / 1_750.0 * 0.10,
                    longitude: -49.24
                )
            )
        }

        let simplified = PolylineEncoder.simplify(route, maximumPoints: 3)

        XCTAssertEqual(simplified.count, 3)
        XCTAssertEqual(simplified[1].latitude, -25.40, accuracy: 0.000_001)
        XCTAssertEqual(simplified[1].longitude, -49.24, accuracy: 0.000_001)
    }

    func testSimplifierHonorsPointLimitAndKeepsBothEnds() {
        let route = (0..<3_000).map { index in
            CLLocationCoordinate2D(
                latitude: -25.40 + Double(index) * 0.000_01,
                longitude: -49.25 + sin(Double(index) / 8.0) * 0.001
            )
        }

        let simplified = PolylineEncoder.simplify(route, maximumPoints: 120)

        XCTAssertLessThanOrEqual(simplified.count, 120)
        XCTAssertEqual(simplified.first!.latitude, route.first!.latitude, accuracy: 0.000_001)
        XCTAssertEqual(simplified.first!.longitude, route.first!.longitude, accuracy: 0.000_001)
        XCTAssertEqual(simplified.last!.latitude, route.last!.latitude, accuracy: 0.000_001)
        XCTAssertEqual(simplified.last!.longitude, route.last!.longitude, accuracy: 0.000_001)
    }
}
