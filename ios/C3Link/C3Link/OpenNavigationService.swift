import CoreLocation
import Foundation
import MapKit

enum NavigationServiceError: LocalizedError {
    case invalidEndpoint
    case invalidResponse
    case noDestination
    case noRoute
    case searchUnavailable

    var errorDescription: String? {
        switch self {
        case .invalidEndpoint: return "Endereço do serviço de mapas inválido"
        case .invalidResponse: return "O serviço de mapas respondeu de forma inválida"
        case .noDestination: return "Destino não encontrado"
        case .noRoute: return "Não foi possível calcular uma rota de carro"
        case .searchUnavailable: return "A busca de endereços está temporariamente indisponível. Aguarde alguns segundos e tente novamente"
        }
    }
}

final class OpenNavigationService {
    static let userAgent = "C3Link/2.2 (personal in-car navigation; https://github.com/khauanp/citroen-c3-media)"
    static let defaultRoutingEndpoint = "https://router.project-osrm.org"

    private let session: URLSession

    init() {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 35
        configuration.waitsForConnectivity = true
        configuration.requestCachePolicy = .useProtocolCachePolicy
        session = URLSession(configuration: configuration)
    }

    func search(
        query: String,
        near location: CLLocation?,
        preferExactAddress: Bool = false
    ) async throws -> [DestinationResult] {
        let trimmedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedQuery.isEmpty else { throw NavigationServiceError.noDestination }
        let shouldUseLocalBias = shouldBiasSearchLocally(trimmedQuery)
        let exactResult: DestinationResult?
        if preferExactAddress && !shouldUseLocalBias {
            exactResult = await geocodedResult(for: trimmedQuery)
        } else {
            exactResult = nil
        }
        var items: [MKMapItem] = []
        do {
            items = try await mapItems(
                matching: trimmedQuery,
                region: shouldUseLocalBias ? location.map { localSearchRegion($0) } : nil
            )
            if items.isEmpty, shouldUseLocalBias {
                items = try await mapItems(matching: trimmedQuery, region: nil)
            }
        } catch {
            if let exactResult { return [exactResult] }
            throw error
        }

        let queryTokens = significantTokens(in: trimmedQuery)
        var seen = Set<String>()
        let ranked = items.compactMap { item -> (DestinationResult, Double, Double)? in
            let latitude = item.placemark.coordinate.latitude
            let longitude = item.placemark.coordinate.longitude
            guard latitude.isFinite, longitude.isFinite else { return nil }
            let placemarkTitle = (item.placemark.title ?? "").nilIfBlank ?? ""
            let title = (item.name ?? "").nilIfBlank ?? placemarkTitle.nilIfBlank ?? trimmedQuery
            let subtitle = placemarkTitle == title ? "" : placemarkTitle
            let identity = "\(title.lowercased())|\(String(format: "%.5f", latitude))|\(String(format: "%.5f", longitude))"
            guard seen.insert(identity).inserted else { return nil }
            let result = DestinationResult(
                id: identity,
                title: title,
                subtitle: subtitle,
                latitude: latitude,
                longitude: longitude
            )
            let score = relevanceScore(
                query: trimmedQuery,
                queryTokens: queryTokens,
                candidate: title + " " + subtitle
            )
            let distance = location?.distance(
                from: CLLocation(latitude: latitude, longitude: longitude)
            ) ?? .greatestFiniteMagnitude
            return (result, score, distance)
        }.sorted { first, second in
            if abs(first.1 - second.1) > 0.001 { return first.1 > second.1 }
            if first.2 != second.2 { return first.2 < second.2 }
            return first.0.title.localizedCaseInsensitiveCompare(second.0.title) == .orderedAscending
        }

        var results: [DestinationResult] = []
        if let exactResult { results.append(exactResult) }
        for result in ranked.map(\.0) where !results.contains(where: { existing in
            abs(existing.latitude - result.latitude) < 0.000_02 &&
                abs(existing.longitude - result.longitude) < 0.000_02
        }) {
            results.append(result)
        }
        if results.isEmpty { throw NavigationServiceError.noDestination }
        return Array(results.prefix(8))
    }

    private func geocodedResult(for query: String) async -> DestinationResult? {
        do {
            guard let placemark = try await CLGeocoder().geocodeAddressString(
                query,
                in: nil,
                preferredLocale: Locale(identifier: "pt_BR")
            ).first,
            let location = placemark.location else { return nil }
            let title = placemark.name?.nilIfBlank ?? placemark.thoroughfare?.nilIfBlank ?? query
            let subtitle = [placemark.locality, placemark.administrativeArea, placemark.country]
                .compactMap { $0?.nilIfBlank }
                .reduce(into: [String]()) { values, value in
                    if !values.contains(value) { values.append(value) }
                }
                .joined(separator: " • ")
            return DestinationResult(
                id: "geocoded-\(location.coordinate.latitude)-\(location.coordinate.longitude)",
                title: title,
                subtitle: subtitle,
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude
            )
        } catch {
            return nil
        }
    }

    func destination(
        latitude: Double,
        longitude: Double,
        preferredLabel: String
    ) async -> DestinationResult {
        let coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        let genericLabel = preferredLabel.isEmpty || preferredLabel.hasPrefix("Destino do ")
        do {
            let placemarks = try await CLGeocoder().reverseGeocodeLocation(
                CLLocation(latitude: latitude, longitude: longitude),
                preferredLocale: Locale(identifier: "pt_BR")
            )
            if let placemark = placemarks.first {
                let street = [placemark.thoroughfare, placemark.subThoroughfare]
                    .compactMap { $0?.nilIfBlank }
                    .joined(separator: ", ")
                let locality = [placemark.locality, placemark.administrativeArea]
                    .compactMap { $0?.nilIfBlank }
                    .joined(separator: " - ")
                let subtitle = [street.nilIfBlank, locality.nilIfBlank, placemark.postalCode?.nilIfBlank]
                    .compactMap { $0 }
                    .reduce(into: [String]()) { values, value in
                        if !values.contains(value) { values.append(value) }
                    }
                    .joined(separator: " • ")
                let title = genericLabel
                    ? (placemark.name?.nilIfBlank ?? placemark.thoroughfare?.nilIfBlank ?? preferredLabel)
                    : preferredLabel
                return DestinationResult(
                    id: "imported-\(latitude)-\(longitude)",
                    title: title,
                    subtitle: subtitle,
                    latitude: coordinate.latitude,
                    longitude: coordinate.longitude
                )
            }
        } catch {
            // Coordinates from an authoritative share link are still more
            // precise than falling back to a guessed text result.
        }
        return DestinationResult(
            id: "imported-\(latitude)-\(longitude)",
            title: preferredLabel.isEmpty ? "Destino compartilhado" : preferredLabel,
            subtitle: String(format: "%.5f, %.5f", latitude, longitude),
            latitude: coordinate.latitude,
            longitude: coordinate.longitude
        )
    }

    private func mapItems(
        matching query: String,
        region: MKCoordinateRegion?
    ) async throws -> [MKMapItem] {
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = query
        request.resultTypes = [.address, .pointOfInterest]
        if let region { request.region = region }
        do {
            return try await MKLocalSearch(request: request).start().mapItems
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw NavigationServiceError.searchUnavailable
        }
    }

    private func localSearchRegion(_ location: CLLocation) -> MKCoordinateRegion {
        MKCoordinateRegion(
            center: location.coordinate,
            span: MKCoordinateSpan(latitudeDelta: 0.8, longitudeDelta: 0.8)
        )
    }

    private func shouldBiasSearchLocally(_ query: String) -> Bool {
        let hasNumber = query.rangeOfCharacter(from: .decimalDigits) != nil
        let separators = query.filter { $0 == "," || $0 == "-" }.count
        let words = query.split(whereSeparator: { $0.isWhitespace }).count
        // Complete addresses and city/state-qualified searches must remain
        // global. A local region is only useful for short POI/category terms.
        return !hasNumber && separators == 0 && words <= 3
    }

    private func relevanceScore(
        query: String,
        queryTokens: [String],
        candidate: String
    ) -> Double {
        let normalizedCandidate = normalizedSearchText(candidate)
        let normalizedQuery = normalizedSearchText(query)
        var score = normalizedCandidate.contains(normalizedQuery) ? 3.0 : 0.0
        for token in queryTokens where normalizedCandidate.contains(token) {
            score += token.allSatisfy { $0.isNumber } ? 2.0 : 1.0
        }
        return score / Double(max(1, queryTokens.count))
    }

    private func significantTokens(in value: String) -> [String] {
        let ignored: Set<String> = ["rua", "r", "avenida", "av", "rodovia", "estrada", "de", "da", "do", "das", "dos"]
        return normalizedSearchText(value)
            .split(separator: " ")
            .map(String.init)
            .filter { token in token.count >= 2 && !ignored.contains(token) }
    }

    private func normalizedSearchText(_ value: String) -> String {
        value.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "pt_BR"))
            .lowercased()
            .replacingOccurrences(of: #"[^a-z0-9]+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func route(from origin: CLLocationCoordinate2D, to destination: DestinationResult) async throws -> NavigationRoute {
        let defaults = UserDefaults.standard
        let endpoint = (defaults.string(forKey: "c3link.routing-endpoint") ?? "").nilIfBlank
            ?? Self.defaultRoutingEndpoint
        let coordinates = "\(origin.longitude),\(origin.latitude);\(destination.longitude),\(destination.latitude)"
        guard var components = URLComponents(string: endpoint + "/route/v1/driving/" + coordinates) else {
            throw NavigationServiceError.invalidEndpoint
        }
        components.queryItems = [
            URLQueryItem(name: "overview", value: "full"),
            URLQueryItem(name: "geometries", value: "polyline"),
            URLQueryItem(name: "steps", value: "true"),
            URLQueryItem(name: "alternatives", value: "false"),
        ]
        guard let url = components.url else { throw NavigationServiceError.invalidEndpoint }
        var request = URLRequest(url: url)
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("pt-BR,pt;q=0.9", forHTTPHeaderField: "Accept-Language")
        let (data, response) = try await session.data(for: request)
        try validate(response)
        let decoded = try JSONDecoder().decode(OSRMResponse.self, from: data)
        guard decoded.code == "Ok", let selected = decoded.routes.first else {
            throw NavigationServiceError.noRoute
        }
        var decodedCoordinates = PolylineEncoder.decode(selected.geometry, precision: 5)
        guard decodedCoordinates.count >= 2 else { throw NavigationServiceError.invalidResponse }

        var encoded = selected.geometry
        if decodedCoordinates.count > 1_200 || encoded.utf8.count > 8_000 {
            decodedCoordinates = PolylineEncoder.simplify(decodedCoordinates, maximumPoints: 1_200)
            encoded = PolylineEncoder.encode(decodedCoordinates, precision: 5)
        }
        var pointLimit = decodedCoordinates.count
        while encoded.utf8.count > 10_000 && pointLimit > 200 {
            pointLimit = max(200, pointLimit * 3 / 4)
            decodedCoordinates = PolylineEncoder.simplify(decodedCoordinates, maximumPoints: pointLimit)
            encoded = PolylineEncoder.encode(decodedCoordinates, precision: 5)
        }
        let steps = selected.legs.flatMap(\.steps).compactMap { step -> NavigationStep? in
            guard step.maneuver.location.count == 2 else { return nil }
            let coordinate = CLLocationCoordinate2D(
                latitude: step.maneuver.location[1],
                longitude: step.maneuver.location[0]
            )
            return NavigationStep(
                instruction: instruction(for: step),
                maneuver: maneuverKey(for: step),
                coordinate: coordinate,
                distanceMeters: step.distance,
                durationSeconds: step.duration
            )
        }
        return NavigationRoute(
            encodedPolyline: encoded,
            coordinates: decodedCoordinates,
            distanceMeters: selected.distance,
            durationSeconds: selected.duration,
            steps: steps
        )
    }

    private func validate(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw NavigationServiceError.invalidResponse
        }
    }

    private func maneuverKey(for step: OSRMStep) -> String {
        let modifier = step.maneuver.modifier ?? "straight"
        switch modifier {
        case "left", "slight left", "sharp left": return "left"
        case "right", "slight right", "sharp right": return "right"
        case "uturn": return "uturn"
        default:
            return step.maneuver.type.contains("roundabout") ? "roundabout" : "straight"
        }
    }

    private func instruction(for step: OSRMStep) -> String {
        let road = step.name.nilIfBlank.map { " na \($0)" } ?? ""
        let modifier = step.maneuver.modifier ?? "straight"
        switch step.maneuver.type {
        case "depart": return road.isEmpty ? "Inicie a rota" : "Siga\(road)"
        case "arrive": return "Você chegou ao destino"
        case "roundabout", "rotary":
            if let exit = step.maneuver.exit { return "Na rotatória, pegue a saída \(exit)\(road)" }
            return "Entre na rotatória\(road)"
        case "merge": return "Entre no fluxo\(road)"
        case "fork": return modifier.contains("left") ? "Mantenha-se à esquerda\(road)" : "Mantenha-se à direita\(road)"
        case "on ramp": return "Pegue o acesso\(road)"
        case "off ramp": return "Pegue a saída\(road)"
        case "continue", "new name": return "Continue\(road)"
        default:
            switch modifier {
            case "left": return "Vire à esquerda\(road)"
            case "slight left": return "Vire levemente à esquerda\(road)"
            case "sharp left": return "Faça uma curva fechada à esquerda\(road)"
            case "right": return "Vire à direita\(road)"
            case "slight right": return "Vire levemente à direita\(road)"
            case "sharp right": return "Faça uma curva fechada à direita\(road)"
            case "uturn": return "Faça o retorno\(road)"
            default: return road.isEmpty ? "Siga em frente" : "Siga\(road)"
            }
        }
    }
}

private struct OSRMResponse: Decodable {
    let code: String
    let routes: [OSRMRoute]
}

private struct OSRMRoute: Decodable {
    let geometry: String
    let distance: Double
    let duration: Double
    let legs: [OSRMLeg]
}

private struct OSRMLeg: Decodable {
    let steps: [OSRMStep]
}

private struct OSRMStep: Decodable {
    let distance: Double
    let duration: Double
    let name: String
    let maneuver: OSRMManeuver
}

private struct OSRMManeuver: Decodable {
    let type: String
    let modifier: String?
    let location: [Double]
    let exit: Int?
}

private extension String {
    var nilIfBlank: String? {
        let value = trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }
}
