import CoreLocation
import XCTest
@testable import C3Link

final class RouteGeometryTests: XCTestCase {
    func testLongStraightRoadKeepsVisibleIntermediateSegments() {
        let route = [
            CLLocationCoordinate2D(latitude: -25.43, longitude: -49.27),
            CLLocationCoordinate2D(latitude: -25.43, longitude: -49.22),
        ]
        let dense = RouteGeometry.densify(route)
        XCTAssertGreaterThan(dense.count, 70)
        XCTAssertEqual(dense.first?.latitude, route.first?.latitude)
        XCTAssertEqual(dense.last?.longitude, route.last?.longitude)
        for pair in zip(dense, dense.dropFirst()) {
            let distance = CLLocation(latitude: pair.0.latitude, longitude: pair.0.longitude)
                .distance(from: CLLocation(latitude: pair.1.latitude, longitude: pair.1.longitude))
            XCTAssertLessThanOrEqual(distance, RouteGeometry.maximumSegmentMeters + 0.2)
        }
    }

    func testRejectsGeometryThatWouldOverloadTablet() {
        let route = [
            CLLocationCoordinate2D(latitude: -30, longitude: -50),
            CLLocationCoordinate2D(latitude: 30, longitude: -50),
        ]
        XCTAssertTrue(RouteGeometry.densify(route).isEmpty)
    }
}
