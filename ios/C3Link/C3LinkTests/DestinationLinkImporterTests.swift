import XCTest
@testable import C3Link

final class DestinationLinkImporterTests: XCTestCase {
    private let importer = DestinationLinkImporter()

    func testExtractsURLAndDestinationFromCompleteWazeShareMessage() {
        let message = "Estou usando o Waze para dirigir até Rua das Flores, 120, Curitiba, PR, chegando às 22:10. Acompanhe o meu percurso no Waze! https://waze.com/ul?ll=-25.4284%2C-49.2733&navigate=yes"

        XCTAssertEqual(importer.mapURLs(in: message).count, 1)
        XCTAssertEqual(
            importer.sharedDestinationLabel(from: message),
            "Rua das Flores, 120, Curitiba, PR"
        )
    }

    func testCompleteWazeMessageResolvesWithoutSearchingTheShareSentence() async throws {
        let message = "Estou usando o Waze para dirigir até Rua das Flores, 120, Curitiba, PR, chegando às 22:10. Acompanhe o meu percurso no Waze! https://waze.com/ul/h7-example"

        guard case let .search(query) = try await importer.resolve(message) else {
            return XCTFail("Expected the address extracted from the Waze message")
        }
        XCTAssertEqual(query, "Rua das Flores, 120, Curitiba, PR")
    }

    func testWazeAuthoritativeCoordinateIsImported() throws {
        let url = try XCTUnwrap(URL(string: "https://waze.com/ul?ll=-25.4284%2C-49.2733&navigate=yes"))

        guard case let .coordinate(latitude, longitude, label)? = importer.coordinateDestination(
            from: url,
            preferredLabel: "Centro de Curitiba"
        ) else {
            return XCTFail("Expected a Waze coordinate")
        }
        XCTAssertEqual(latitude, -25.4284, accuracy: 0.000_001)
        XCTAssertEqual(longitude, -49.2733, accuracy: 0.000_001)
        XCTAssertEqual(label, "Centro de Curitiba")
    }

    func testDistantGoogleRouteUsesDestinationInsteadOfViewportCenter() throws {
        let url = try XCTUnwrap(URL(string: "https://www.google.com/maps/dir/Sorocaba,+SP/Curitiba,+PR/@-24.3000,-48.2000,7z"))

        XCTAssertNil(importer.coordinateDestination(from: url, preferredLabel: nil))
        XCTAssertEqual(importer.searchQuery(from: url), "Curitiba, PR")
    }

    func testGoogleSelectedPlaceCoordinateWinsOverMapCamera() throws {
        let url = try XCTUnwrap(URL(string: "https://www.google.com/maps/place/Teatro/data=!4m5!3m4!1s0x0:0x0!8m2!3d-23.5012!4d-47.4526/@-23.7,-47.8,10z"))

        guard case let .coordinate(latitude, longitude, _)? = importer.coordinateDestination(
            from: url,
            preferredLabel: nil
        ) else {
            return XCTFail("Expected the selected Google place coordinate")
        }
        XCTAssertEqual(latitude, -23.5012, accuracy: 0.000_001)
        XCTAssertEqual(longitude, -47.4526, accuracy: 0.000_001)
    }

    func testGoogleDirectionsDataUsesLastSelectedCoordinate() throws {
        let url = try XCTUnwrap(URL(string: "https://www.google.com/maps/dir/A/B/data=!3d-23.5000!4d-47.4000!3d-25.4284!4d-49.2733/@-24.0,-48.0,7z"))

        guard case let .coordinate(latitude, longitude, _)? = importer.coordinateDestination(
            from: url,
            preferredLabel: "Destino final"
        ) else {
            return XCTFail("Expected the final selected coordinate")
        }
        XCTAssertEqual(latitude, -25.4284, accuracy: 0.000_001)
        XCTAssertEqual(longitude, -49.2733, accuracy: 0.000_001)
    }

    func testOfficialGoogleDirectionsDestinationParameter() throws {
        let url = try XCTUnwrap(URL(string: "https://www.google.com/maps/dir/?api=1&origin=Sorocaba%2CSP&destination=Florianopolis%2CSC"))

        XCTAssertEqual(importer.searchQuery(from: url), "Florianopolis,SC")
    }
}
