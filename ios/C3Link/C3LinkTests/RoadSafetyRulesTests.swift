import XCTest
@testable import C3Link

final class RoadSafetyRulesTests: XCTestCase {
    func testBrazilianSpeedLimitValues() {
        XCTAssertEqual(RoadSafetyRules.speedLimitKph(from: "40"), 40)
        XCTAssertEqual(RoadSafetyRules.speedLimitKph(from: "60 km/h"), 60)
        XCTAssertEqual(RoadSafetyRules.speedLimitKph(from: "30,5"), 30.5)
        XCTAssertNil(RoadSafetyRules.speedLimitKph(from: "signals"))
        XCTAssertNil(RoadSafetyRules.speedLimitKph(from: "BR:urban"))
    }

    func testSpeedometerTurnsRedOnlyAboveKnownLimitAndHysteresis() {
        XCTAssertFalse(RoadSafetyRules.isSpeeding(speedMps: 60 / 3.6, limitKph: 60))
        XCTAssertFalse(RoadSafetyRules.isSpeeding(speedMps: 62 / 3.6, limitKph: 60))
        XCTAssertTrue(RoadSafetyRules.isSpeeding(speedMps: 63 / 3.6, limitKph: 60))
        XCTAssertFalse(RoadSafetyRules.isSpeeding(speedMps: 100 / 3.6, limitKph: nil))
    }
}
