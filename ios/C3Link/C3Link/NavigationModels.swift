import CoreLocation
import Foundation

struct DestinationResult: Identifiable, Equatable {
    let id: String
    let title: String
    let subtitle: String
    let latitude: Double
    let longitude: Double

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

struct NavigationStep {
    let instruction: String
    let maneuver: String
    let coordinate: CLLocationCoordinate2D
    let distanceMeters: Double
    let durationSeconds: Double
}

struct NavigationRoute {
    let encodedPolyline: String
    let coordinates: [CLLocationCoordinate2D]
    let distanceMeters: Double
    let durationSeconds: Double
    let steps: [NavigationStep]
}

struct MapTileKey: Hashable {
    let zoom: Int
    let x: Int
    let y: Int

    var id: String { "\(zoom)_\(x)_\(y)" }

    var isValid: Bool {
        guard (0...20).contains(zoom) else { return false }
        let count = 1 << zoom
        return (0..<count).contains(x) && (0..<count).contains(y)
    }
}
