import CoreLocation
import Foundation

actor RoadSafetyService {
    static let sourceName = "OpenStreetMap"
    private static let endpoint = URL(string: "https://overpass-api.de/api/interpreter")!
    private static let cameraQueryRadiusMeters = 2_200
    private static let cameraRouteToleranceMeters = 120.0
    private static let roadQueryRadiusMeters = 120
    private static let roadMatchToleranceMeters = 55.0

    private let session: URLSession
    private var speedLimitCache: [String: Double] = [:]

    init() {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 22
        configuration.timeoutIntervalForResource = 35
        configuration.waitsForConnectivity = true
        session = URLSession(configuration: configuration)
    }

    func cameras(along route: [CLLocationCoordinate2D]) async -> [SafetyCamera] {
        guard route.count >= 2 else { return [] }
        let samples = sampledCoordinates(route, everyMeters: 4_000)
        var elements: [OverpassElement] = []
        for groupStart in stride(from: 0, to: samples.count, by: 12) {
            if Task.isCancelled { return [] }
            let group = samples[groupStart..<min(samples.count, groupStart + 12)]
            let clauses = group.flatMap { coordinate in
                let point = coordinate.overpassPoint
                return [
                    "nwr(around:\(Self.cameraQueryRadiusMeters),\(point))[\"highway\"=\"speed_camera\"];",
                    "nwr(around:\(Self.cameraQueryRadiusMeters),\(point))[\"enforcement\"=\"maxspeed\"];",
                ]
            }.joined(separator: "\n")
            let query = "[out:json][timeout:20];(\n\(clauses)\n);out center tags;"
            guard let response = try? await execute(query) else { continue }
            elements.append(contentsOf: response.elements)
        }

        var seen = Set<String>()
        return elements.compactMap { element -> SafetyCamera? in
            let identity = "\(element.type)-\(element.id)"
            guard seen.insert(identity).inserted,
                  element.isSpeedCamera,
                  let coordinate = element.coordinate else { return nil }
            let nearest = nearestRoutePoint(to: coordinate, route: route)
            guard nearest.distanceMeters <= Self.cameraRouteToleranceMeters else { return nil }
            return SafetyCamera(
                id: identity,
                coordinate: coordinate,
                routeIndex: nearest.index,
                speedLimitKph: RoadSafetyRules.speedLimitKph(from: element.tags?["maxspeed"])
            )
        }.sorted { first, second in
            if first.routeIndex != second.routeIndex { return first.routeIndex < second.routeIndex }
            return first.id < second.id
        }
    }

    func speedLimit(at location: CLLocation, courseDegrees: Double) async -> Double? {
        let key = String(format: "%.3f:%.3f", location.coordinate.latitude, location.coordinate.longitude)
        if let cached = speedLimitCache[key] { return cached > 0 ? cached : nil }
        let query = """
        [out:json][timeout:12];
        way(around:\(Self.roadQueryRadiusMeters),\(location.coordinate.overpassPoint))[\"highway\"][\"maxspeed\"];
        out geom tags;
        """
        guard let response = try? await execute(query) else { return nil }
        let result = bestRoadSpeedLimit(
            elements: response.elements,
            location: location.coordinate,
            courseDegrees: courseDegrees
        )
        speedLimitCache[key] = result ?? -1
        return result
    }

    private func execute(_ query: String) async throws -> OverpassResponse {
        var request = URLRequest(url: Self.endpoint)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue(OpenNavigationService.userAgent, forHTTPHeaderField: "User-Agent")
        var components = URLComponents()
        components.queryItems = [URLQueryItem(name: "data", value: query)]
        request.httpBody = components.percentEncodedQuery?.data(using: .utf8)
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw NavigationServiceError.invalidResponse
        }
        return try JSONDecoder().decode(OverpassResponse.self, from: data)
    }

    private func bestRoadSpeedLimit(
        elements: [OverpassElement],
        location: CLLocationCoordinate2D,
        courseDegrees: Double
    ) -> Double? {
        var best: (score: Double, limit: Double)?
        for element in elements {
            guard let limit = RoadSafetyRules.speedLimitKph(from: element.tags?["maxspeed"]),
                  let geometry = element.geometry, geometry.count >= 2 else { continue }
            let oneWay = element.tags?["oneway"]
            for pair in zip(geometry, geometry.dropFirst()) {
                let start = pair.0.coordinate
                let end = pair.1.coordinate
                let distance = pointToSegmentDistanceMeters(location, start, end)
                guard distance <= Self.roadMatchToleranceMeters else { continue }
                var bearing = bearingDegrees(from: start, to: end)
                if oneWay == "-1" { bearing = (bearing + 180).truncatingRemainder(dividingBy: 360) }
                var headingDifference = angularDifference(courseDegrees, bearing)
                if oneWay != "yes" && oneWay != "1" && oneWay != "-1" {
                    headingDifference = min(headingDifference, angularDifference(courseDegrees, bearing + 180))
                }
                if courseDegrees >= 0, headingDifference > 75 { continue }
                let score = distance + (courseDegrees >= 0 ? headingDifference * 0.35 : 0)
                if best == nil || score < best!.score { best = (score, limit) }
            }
        }
        return best?.limit
    }

    private func sampledCoordinates(
        _ coordinates: [CLLocationCoordinate2D],
        everyMeters interval: Double
    ) -> [CLLocationCoordinate2D] {
        guard let first = coordinates.first, let last = coordinates.last else { return [] }
        var result = [first]
        var distanceSinceSample = 0.0
        for pair in zip(coordinates, coordinates.dropFirst()) {
            distanceSinceSample += CLLocation(latitude: pair.0.latitude, longitude: pair.0.longitude)
                .distance(from: CLLocation(latitude: pair.1.latitude, longitude: pair.1.longitude))
            if distanceSinceSample >= interval {
                result.append(pair.1)
                distanceSinceSample = 0
            }
        }
        if result.last.map({ coordinateDistance($0, last) > 10 }) ?? true { result.append(last) }
        return result
    }

    private func nearestRoutePoint(
        to coordinate: CLLocationCoordinate2D,
        route: [CLLocationCoordinate2D]
    ) -> (index: Int, distanceMeters: Double) {
        var bestIndex = 0
        var bestDistance = Double.greatestFiniteMagnitude
        for (index, candidate) in route.enumerated() {
            let distance = coordinateDistance(coordinate, candidate)
            if distance < bestDistance {
                bestIndex = index
                bestDistance = distance
            }
        }
        return (bestIndex, bestDistance)
    }

    private func coordinateDistance(_ first: CLLocationCoordinate2D, _ second: CLLocationCoordinate2D) -> Double {
        CLLocation(latitude: first.latitude, longitude: first.longitude)
            .distance(from: CLLocation(latitude: second.latitude, longitude: second.longitude))
    }

    private func pointToSegmentDistanceMeters(
        _ point: CLLocationCoordinate2D,
        _ start: CLLocationCoordinate2D,
        _ end: CLLocationCoordinate2D
    ) -> Double {
        let latitudeScale = 111_132.0
        let longitudeScale = 111_320.0 * cos(point.latitude * .pi / 180)
        let ax = (start.longitude - point.longitude) * longitudeScale
        let ay = (start.latitude - point.latitude) * latitudeScale
        let bx = (end.longitude - point.longitude) * longitudeScale
        let by = (end.latitude - point.latitude) * latitudeScale
        let dx = bx - ax
        let dy = by - ay
        let lengthSquared = dx * dx + dy * dy
        guard lengthSquared > 0 else { return hypot(ax, ay) }
        let t = max(0, min(1, -(ax * dx + ay * dy) / lengthSquared))
        return hypot(ax + t * dx, ay + t * dy)
    }

    private func bearingDegrees(from start: CLLocationCoordinate2D, to end: CLLocationCoordinate2D) -> Double {
        let lat1 = start.latitude * .pi / 180
        let lat2 = end.latitude * .pi / 180
        let deltaLongitude = (end.longitude - start.longitude) * .pi / 180
        let y = sin(deltaLongitude) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLongitude)
        return (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
    }

    private func angularDifference(_ first: Double, _ second: Double) -> Double {
        let difference = abs(first - second).truncatingRemainder(dividingBy: 360)
        return min(difference, 360 - difference)
    }
}

private struct OverpassResponse: Decodable {
    let elements: [OverpassElement]
}

private struct OverpassElement: Decodable {
    let type: String
    let id: Int64
    let lat: Double?
    let lon: Double?
    let center: OverpassPoint?
    let geometry: [OverpassPoint]?
    let tags: [String: String]?

    var coordinate: CLLocationCoordinate2D? {
        if let lat, let lon { return CLLocationCoordinate2D(latitude: lat, longitude: lon) }
        return center?.coordinate
    }

    var isSpeedCamera: Bool {
        tags?["highway"] == "speed_camera" || tags?["enforcement"] == "maxspeed"
    }
}

private struct OverpassPoint: Decodable {
    let lat: Double
    let lon: Double

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }
}

private extension CLLocationCoordinate2D {
    var overpassPoint: String {
        String(format: "%.6f,%.6f", latitude, longitude)
    }
}
