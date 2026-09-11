import Foundation

enum RoadSafetyRules {
    static func speedLimitKph(from raw: String?) -> Double? {
        guard let raw else { return nil }
        let normalized = raw.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        if ["none", "signals", "variable", "walk", "national"].contains(normalized) { return nil }
        guard let match = normalized.range(of: #"\d+(?:[.,]\d+)?"#, options: .regularExpression),
              let value = Double(normalized[match].replacingOccurrences(of: ",", with: ".")),
              value > 0 else { return nil }
        let kph = normalized.contains("mph") ? value * 1.609_344 : value
        return (5...160).contains(kph) ? kph : nil
    }

    static func isSpeeding(speedMps: Double, limitKph: Double?) -> Bool {
        guard let limitKph, limitKph > 0 else { return false }
        return max(0, speedMps) * 3.6 > limitKph + 2
    }
}
