import XCTest
@testable import C3Link

final class WazeResourceRelayTests: XCTestCase {
    func testBinaryHTTPFramingAndChunkExtensions() throws {
        let payload = Data([0, 255, 128, 10])
        var packet = Data("HTTP/1.1 206 Partial Content\r\nContent-Type: image/png\r\nContent-Length: 4\r\nAccess-Control-Allow-Origin: https://embed.waze.com\r\n\r\n".utf8)
        packet.append(payload)
        let response = try WazeHTTPResource.parse(packet)
        XCTAssertEqual(response.status, 206)
        XCTAssertEqual(response.body, payload)
        XCTAssertEqual(response.headers["content-type"], "image/png")
        var chunked = Data("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4;ext=yes\r\n".utf8)
        chunked.append(payload)
        chunked.append(Data("\r\n0\r\n\r\n".utf8))
        XCTAssertEqual(try WazeHTTPResource.parse(chunked).body, payload)
        XCTAssertThrowsError(try WazeHTTPResource.parse(packet.dropLast()))
        XCTAssertThrowsError(try WazeHTTPResource.parse(Data("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n\r\n".utf8)))
        XCTAssertThrowsError(try WazeHTTPResource.parse(Data("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n0\r\n\r\n".utf8)))
    }

    func testHeadersRedirectsAndEncodingFailures() throws {
        let redirect = try WazeHTTPResource.parse(Data("HTTP/1.1 302 Found\r\nLocation: /iframe\r\nContent-Length: 0\r\n\r\n".utf8))
        XCTAssertEqual(redirect.headers["location"], "/iframe")
        let head = try WazeHTTPResource.parse(Data("HTTP/1.1 200 OK\r\nContent-Length: 1024\r\n\r\n".utf8), headOnly: true)
        XCTAssertTrue(head.body.isEmpty)
        XCTAssertThrowsError(try WazeHTTPResource.parse(Data("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 1\r\n\r\nx".utf8)))
    }
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
