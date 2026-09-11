import XCTest
@testable import C3Link

final class WazeResourceRelayTests: XCTestCase {
    @MainActor func testResourceBoundariesRejectLookalikeHostsAndLocalTargets() {
        for text in ["https://embed.waze.com/iframe", "https://www.gstatic.com/a.js", "https://fonts.googleapis.com/css"] {
            XCTAssertTrue(WazeResourceRelay.allowed(URL(string: text)!))
        }
        for text in ["https://waze.com.attacker.invalid/a", "https://evilwaze.com/a", "https://127.0.0.1/a",
                     "http://embed.waze.com/a", "https://user@waze.com/a", "https://waze.com:8443/a", "file:///tmp/a"] {
            XCTAssertFalse(WazeResourceRelay.allowed(URL(string: text)!))
        }
    }
}
