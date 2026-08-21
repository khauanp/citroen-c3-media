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
        let stride = Double(coordinates.count - 1) / Double(maximumPoints - 1)
        return (0..<maximumPoints).map { index in
            coordinates[min(coordinates.count - 1, Int((Double(index) * stride).rounded()))]
        }
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
