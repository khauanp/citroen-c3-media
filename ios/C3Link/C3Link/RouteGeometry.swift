import CoreLocation

enum RouteGeometry {
    static let maximumPoints = 50_000
    static let maximumSegmentMeters = 60.0

    static func densify(_ coordinates: [CLLocationCoordinate2D]) -> [CLLocationCoordinate2D] {
        guard coordinates.count >= 2 else { return coordinates }
        var result = [coordinates[0]]
        for pair in zip(coordinates, coordinates.dropFirst()) {
            let start = pair.0
            let end = pair.1
            let distance = CLLocation(latitude: start.latitude, longitude: start.longitude)
                .distance(from: CLLocation(latitude: end.latitude, longitude: end.longitude))
            let divisions = max(1, Int(ceil(distance / maximumSegmentMeters)))
            guard result.count + divisions <= maximumPoints else { return [] }
            for index in 1...divisions {
                let progress = Double(index) / Double(divisions)
                result.append(
                    CLLocationCoordinate2D(
                        latitude: start.latitude + (end.latitude - start.latitude) * progress,
                        longitude: start.longitude + (end.longitude - start.longitude) * progress
                    )
                )
            }
        }
        return result
    }
}
