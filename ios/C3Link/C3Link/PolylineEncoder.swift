import CoreLocation

enum PolylineEncoder {
    static func encode(_ coordinates: [CLLocationCoordinate2D], precision: Int = 5) -> String {
        let factor = pow(10.0, Double(precision))
        var result = ""
        var previousLatitude = 0
        var previousLongitude = 0
        for coordinate in coordinates {
            let latitude = Int((coordinate.latitude * factor).rounded())
            let longitude = Int((coordinate.longitude * factor).rounded())
            result += encodeValue(latitude - previousLatitude)
            result += encodeValue(longitude - previousLongitude)
            previousLatitude = latitude
            previousLongitude = longitude
        }
        return result
    }

    static func decode(_ encoded: String, precision: Int = 5) -> [CLLocationCoordinate2D] {
        let scalars = Array(encoded.unicodeScalars)
        let factor = pow(10.0, Double(precision))
        var index = 0
        var latitude = 0
        var longitude = 0
        var result: [CLLocationCoordinate2D] = []
        while index < scalars.count {
            guard let lat = decodeValue(scalars, index: &index),
                  let lon = decodeValue(scalars, index: &index) else { break }
            latitude += lat
            longitude += lon
            result.append(
                CLLocationCoordinate2D(
                    latitude: Double(latitude) / factor,
                    longitude: Double(longitude) / factor
                )
            )
        }
        return result
    }

    static func simplify(
        _ coordinates: [CLLocationCoordinate2D],
        maximumPoints: Int
    ) -> [CLLocationCoordinate2D] {
        guard coordinates.count > maximumPoints, maximumPoints >= 2 else { return coordinates }

        let origin = coordinates[0]
        let referenceLatitude = coordinates.reduce(0.0) { $0 + $1.latitude } / Double(coordinates.count)
        let longitudeScale = 111_320.0 * cos(referenceLatitude * .pi / 180.0)
        let latitudeScale = 111_132.0
        let projected = coordinates.map { coordinate in
            ProjectedPoint(
                x: (coordinate.longitude - origin.longitude) * longitudeScale,
                y: (coordinate.latitude - origin.latitude) * latitudeScale
            )
        }

        // Ramer-Douglas-Peucker preserves bends and street corners. Increase
        // the tolerated error only when the transport point limit requires it;
        // uniform stride sampling can draw dangerous shortcuts across blocks.
        var toleranceMeters = 0.75
        var keptIndices = douglasPeuckerIndices(projected, toleranceMeters: toleranceMeters)
        var attempts = 0
        while keptIndices.count > maximumPoints && attempts < 32 {
            toleranceMeters *= 1.5
            keptIndices = douglasPeuckerIndices(projected, toleranceMeters: toleranceMeters)
            attempts += 1
        }
        guard keptIndices.count <= maximumPoints else {
            return [coordinates[0], coordinates[coordinates.count - 1]]
        }
        return keptIndices.map { coordinates[$0] }
    }

    private struct ProjectedPoint {
        let x: Double
        let y: Double
    }

    private static func douglasPeuckerIndices(
        _ points: [ProjectedPoint],
        toleranceMeters: Double
    ) -> [Int] {
        guard points.count > 2 else { return Array(points.indices) }
        let toleranceSquared = toleranceMeters * toleranceMeters
        var keep = Array(repeating: false, count: points.count)
        keep[0] = true
        keep[points.count - 1] = true
        var segments: [(first: Int, last: Int)] = [(0, points.count - 1)]

        while let segment = segments.popLast() {
            guard segment.last > segment.first + 1 else { continue }
            let start = points[segment.first]
            let end = points[segment.last]
            var farthestIndex = -1
            var farthestDistanceSquared = 0.0

            for index in (segment.first + 1)..<segment.last {
                let distanceSquared = squaredDistance(
                    from: points[index],
                    toSegmentStart: start,
                    end: end
                )
                if distanceSquared > farthestDistanceSquared {
                    farthestDistanceSquared = distanceSquared
                    farthestIndex = index
                }
            }

            if farthestIndex >= 0 && farthestDistanceSquared > toleranceSquared {
                keep[farthestIndex] = true
                segments.append((segment.first, farthestIndex))
                segments.append((farthestIndex, segment.last))
            }
        }

        return keep.indices.filter { keep[$0] }
    }

    private static func squaredDistance(
        from point: ProjectedPoint,
        toSegmentStart start: ProjectedPoint,
        end: ProjectedPoint
    ) -> Double {
        let dx = end.x - start.x
        let dy = end.y - start.y
        let lengthSquared = dx * dx + dy * dy
        guard lengthSquared > 0 else {
            let px = point.x - start.x
            let py = point.y - start.y
            return px * px + py * py
        }
        let projection = max(0.0, min(1.0, ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared))
        let closestX = start.x + projection * dx
        let closestY = start.y + projection * dy
        let px = point.x - closestX
        let py = point.y - closestY
        return px * px + py * py
    }

    private static func encodeValue(_ value: Int) -> String {
        var shifted = value < 0 ? ~(value << 1) : value << 1
        var output = ""
        while shifted >= 0x20 {
            if let scalar = UnicodeScalar((0x20 | (shifted & 0x1f)) + 63) {
                output.unicodeScalars.append(scalar)
            }
            shifted >>= 5
        }
        if let scalar = UnicodeScalar(shifted + 63) {
            output.unicodeScalars.append(scalar)
        }
        return output
    }

    private static func decodeValue(_ scalars: [UnicodeScalar], index: inout Int) -> Int? {
        var result = 0
        var shift = 0
        while index < scalars.count, shift <= 30 {
            let value = Int(scalars[index].value) - 63
            index += 1
            guard value >= 0 else { return nil }
            result |= (value & 0x1f) << shift
            if value < 0x20 {
                return (result & 1) == 1 ? ~(result >> 1) : result >> 1
            }
            shift += 5
        }
        return nil
    }
}
