import Foundation

enum ExternalMapProvider {
    case googleMaps
    case waze
}

enum ImportedDestination {
    case coordinate(latitude: Double, longitude: Double, label: String)
    case search(String)
}

enum DestinationImportError: LocalizedError {
    case emptyClipboard
    case unsupportedLink

    var errorDescription: String? {
        switch self {
        case .emptyClipboard:
            return "Copie primeiro o link do destino no Google Maps ou Waze"
        case .unsupportedLink:
            return "Não encontrei o destino nesse link. Compartilhe o local exato pelo Google Maps ou Waze e tente novamente"
        }
    }
}

/** Resolves shared Google Maps and Waze links without a paid Places API key. */
final class DestinationLinkImporter {
    private let session: URLSession

    init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 12
        configuration.timeoutIntervalForResource = 18
        configuration.waitsForConnectivity = true
        session = URLSession(configuration: configuration)
    }

    func resolve(_ clipboardText: String) async throws -> ImportedDestination {
        let text = clipboardText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw DestinationImportError.emptyClipboard }

        let sharedLabel = sharedDestinationLabel(from: text)
        let urls = mapURLs(in: text)
        guard !urls.isEmpty else {
            if let sharedLabel { return .search(sharedLabel) }
            return .search(cleanedPlainSearch(text))
        }

        var fallbackQueries: [String] = []
        for originalURL in urls {
            if let coordinate = coordinateDestination(from: originalURL, preferredLabel: sharedLabel) {
                return coordinate
            }
            if let sharedLabel, isDetailedAddress(sharedLabel) {
                return .search(sharedLabel)
            }
            if let query = searchQuery(from: originalURL) { fallbackQueries.append(query) }

            var request = URLRequest(url: originalURL)
            request.setValue(OpenNavigationService.userAgent, forHTTPHeaderField: "User-Agent")
            request.setValue("pt-BR,pt;q=0.9", forHTTPHeaderField: "Accept-Language")
            do {
                let (data, response) = try await session.data(for: request)
                if let finalURL = response.url {
                    if let coordinate = coordinateDestination(from: finalURL, preferredLabel: sharedLabel) {
                        return coordinate
                    }
                    if let query = searchQuery(from: finalURL) { fallbackQueries.insert(query, at: 0) }
                }
                if let label = pageDestinationLabel(from: data) {
                    fallbackQueries.insert(label, at: 0)
                }
            } catch {
                // Short-link expansion is a convenience. The shared label and
                // the original URL remain valid fallbacks while offline.
            }
        }

        // A Waze share sentence normally carries the full human-readable
        // destination. Prefer it over path slugs and viewport coordinates.
        if let sharedLabel { return .search(sharedLabel) }
        if let query = fallbackQueries.first(where: { !$0.isEmpty }) { return .search(query) }
        throw DestinationImportError.unsupportedLink
    }

    func coordinateDestination(
        from url: URL,
        preferredLabel: String?
    ) -> ImportedDestination? {
        let absolute = url.absoluteString.removingPercentEncoding ?? url.absoluteString
        let label = preferredLabel ?? destinationLabel(from: url)

        // Google place links encode the selected place separately from the
        // camera viewport. This pair is authoritative for the destination.
        if let captures = allCaptures(
            pattern: #"!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)"#,
            text: absolute
        ).last, let latitude = Double(captures[0]), let longitude = Double(captures[1]),
           valid(latitude, longitude) {
            return .coordinate(latitude: latitude, longitude: longitude, label: label)
        }

        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        let host = components.host?.lowercased() ?? ""
        let items = components.queryItems ?? []

        if host.contains("waze") {
            if let rawTo = value(named: "to", in: items),
               let coordinate = wazeCoordinate(from: rawTo) {
                return .coordinate(
                    latitude: coordinate.latitude,
                    longitude: coordinate.longitude,
                    label: label
                )
            }
            if let rawLocation = value(named: "ll", in: items),
               let coordinate = coordinate(from: rawLocation) {
                return .coordinate(
                    latitude: coordinate.latitude,
                    longitude: coordinate.longitude,
                    label: label
                )
            }
        }

        // Only fields that semantically represent the selected destination
        // are accepted. `center` and `/@lat,lon` are deliberately excluded:
        // Google uses them for the map camera and, on long routes, they point
        // somewhere between origin and destination.
        for key in ["destination", "daddr", "query", "q"] {
            if let rawValue = value(named: key, in: items),
               let coordinate = coordinate(from: rawValue) {
                return .coordinate(
                    latitude: coordinate.latitude,
                    longitude: coordinate.longitude,
                    label: label
                )
            }
        }
        return nil
    }

    func searchQuery(from url: URL) -> String? {
        if let components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            let items = components.queryItems ?? []
            for key in ["destination", "daddr", "query", "q"] {
                guard let raw = value(named: key, in: items) else { continue }
                let value = cleaned(raw)
                if coordinate(from: value) == nil, usefulSearch(value) { return value }
            }

            if let rawTo = value(named: "to", in: items) {
                let value = cleaned(rawTo)
                if !value.lowercased().hasPrefix("place."),
                   wazeCoordinate(from: value) == nil,
                   usefulSearch(value) {
                    return value
                }
            }
        }

        let decodedPath = cleaned(url.path)
        let parts = decodedPath.split(separator: "/", omittingEmptySubsequences: true).map(String.init)

        if let placeIndex = parts.firstIndex(where: { $0.caseInsensitiveCompare("place") == .orderedSame }),
           parts.indices.contains(placeIndex + 1) {
            let value = cleaned(parts[placeIndex + 1])
            if usefulSearch(value) { return value }
        }

        if let directionIndex = parts.firstIndex(where: { $0.caseInsensitiveCompare("dir") == .orderedSame }) {
            let candidates = parts.dropFirst(directionIndex + 1).filter { part in
                !part.hasPrefix("@") && !part.lowercased().hasPrefix("data=")
            }
            if let candidate = candidates.last {
                let value = cleaned(candidate)
                if usefulSearch(value) { return value }
            }
        }

        // Waze live-map pages contain country/state/city/place slugs. Joining
        // the final components gives MapKit enough geography to avoid a local
        // namesake when the destination is far away.
        if (url.host?.lowercased().contains("waze") ?? false),
           let directionIndex = parts.firstIndex(where: { $0.caseInsensitiveCompare("directions") == .orderedSame }) {
            let geographicParts = parts.dropFirst(directionIndex + 1).suffix(4).map(cleaned)
            let value = geographicParts.reversed().joined(separator: ", ")
            if usefulSearch(value) { return value }
        }
        return nil
    }

    func mapURLs(in text: String) -> [URL] {
        var urls: [URL] = []
        if let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) {
            let range = NSRange(text.startIndex..., in: text)
            detector.enumerateMatches(in: text, range: range) { result, _, _ in
                guard let url = result?.url,
                      let scheme = url.scheme?.lowercased(),
                      scheme == "http" || scheme == "https" else { return }
                urls.append(url)
            }
        }

        if urls.isEmpty,
           let expression = try? NSRegularExpression(pattern: #"https?://[^\s<>"]+"#, options: .caseInsensitive) {
            let range = NSRange(text.startIndex..., in: text)
            for match in expression.matches(in: text, range: range) {
                guard let matchRange = Range(match.range, in: text) else { continue }
                let raw = String(text[matchRange]).trimmingCharacters(in: CharacterSet(charactersIn: ".,;:!?)\"]}"))
                if let url = URL(string: raw) { urls.append(url) }
            }
        }

        var seen = Set<String>()
        return urls.filter { url in
            let host = url.host?.lowercased() ?? ""
            let isMap = host.contains("waze") || host.contains("google") || host.contains("goo.gl")
            return isMap && seen.insert(url.absoluteString).inserted
        }
    }

    func sharedDestinationLabel(from text: String) -> String? {
        let patterns = [
            #"(?is)(?:para\s+(?:dirigir|navegar)\s+até|(?:dirigir|navegar)\s+até)\s+(.+?)(?=,\s*(?:chegando|com\s+chegada)|\.\s*(?:acompanhe|veja)|\s+https?://|$)"#,
            #"(?is)(?:destination|destino)\s*:\s*(.+?)(?=\s+https?://|$)"#,
        ]
        for pattern in patterns {
            guard let expression = try? NSRegularExpression(pattern: pattern),
                  let match = expression.firstMatch(
                    in: text,
                    range: NSRange(text.startIndex..., in: text)
                  ),
                  match.numberOfRanges >= 2,
                  let range = Range(match.range(at: 1), in: text) else { continue }
            let value = cleaned(String(text[range]))
            if usefulSearch(value) { return value }
        }
        return nil
    }

    private func pageDestinationLabel(from data: Data) -> String? {
        guard data.count <= 5_000_000,
              let html = String(data: data, encoding: .utf8) else { return nil }
        let patterns = [
            #"(?is)<meta[^>]+(?:property|name)=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']"#,
            #"(?is)<title[^>]*>(.*?)</title>"#,
        ]
        for pattern in patterns {
            guard let expression = try? NSRegularExpression(pattern: pattern),
                  let match = expression.firstMatch(
                    in: html,
                    range: NSRange(html.startIndex..., in: html)
                  ),
                  match.numberOfRanges >= 2,
                  let range = Range(match.range(at: 1), in: html) else { continue }
            var value = decodeHTMLEntities(String(html[range]))
            value = value.replacingOccurrences(
                of: #"(?i)^driving directions to\s+"#,
                with: "",
                options: .regularExpression
            )
            value = value.replacingOccurrences(
                of: #"(?i)\s*[-–|]\s*(?:waze|google maps).*$"#,
                with: "",
                options: .regularExpression
            )
            value = cleaned(value)
            if usefulSearch(value) { return value }
        }
        return nil
    }

    private func wazeCoordinate(from value: String) -> (latitude: Double, longitude: Double)? {
        let cleanedValue = cleaned(value)
        if cleanedValue.lowercased().hasPrefix("ll.") {
            return coordinate(from: String(cleanedValue.dropFirst(3)))
        }
        return coordinate(from: cleanedValue)
    }

    private func coordinate(from value: String) -> (latitude: Double, longitude: Double)? {
        guard let values = captures(
            pattern: #"(?:^|[^\d.-])(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)(?:$|[^\d.])"#,
            text: " " + value + " "
        ), let latitude = Double(values[0]), let longitude = Double(values[1]),
           valid(latitude, longitude) else { return nil }
        return (latitude, longitude)
    }

    private func destinationLabel(from url: URL) -> String {
        if let query = searchQuery(from: url) { return query }
        return (url.host?.lowercased().contains("waze") ?? false)
            ? "Destino do Waze"
            : "Destino do Google Maps"
    }

    private func value(named key: String, in items: [URLQueryItem]) -> String? {
        items.first(where: { $0.name.caseInsensitiveCompare(key) == .orderedSame })?.value
    }

    private func cleanedPlainSearch(_ value: String) -> String {
        let withoutURLs = value.replacingOccurrences(
            of: #"https?://[^\s<>\"]+"#,
            with: " ",
            options: [.regularExpression, .caseInsensitive]
        )
        return cleaned(withoutURLs)
    }

    private func cleaned(_ value: String) -> String {
        (value.removingPercentEncoding ?? value)
            .replacingOccurrences(of: "+", with: " ")
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func decodeHTMLEntities(_ value: String) -> String {
        var result = value
        let replacements = [
            "&amp;": "&", "&quot;": "\"", "&#39;": "'",
            "&apos;": "'", "&lt;": "<", "&gt;": ">", "&nbsp;": " ",
        ]
        for (entity, replacement) in replacements {
            result = result.replacingOccurrences(of: entity, with: replacement)
        }
        return result
    }

    private func usefulSearch(_ value: String) -> Bool {
        let lowered = value.lowercased()
        let genericTitles: Set<String> = [
            "maps", "google maps", "waze", "directions", "live map",
            "driving directions, live traffic & road conditions updates",
        ]
        return value.count >= 3 &&
            !lowered.hasPrefix("place.") &&
            !lowered.hasPrefix("driving directions, live traffic") &&
            !genericTitles.contains(lowered)
    }

    private func isDetailedAddress(_ value: String) -> Bool {
        value.rangeOfCharacter(from: .decimalDigits) != nil ||
            value.contains(",") ||
            value.split(whereSeparator: { $0.isWhitespace }).count >= 4
    }

    private func valid(_ latitude: Double, _ longitude: Double) -> Bool {
        latitude.isFinite && longitude.isFinite &&
            (-90.0...90.0).contains(latitude) && (-180.0...180.0).contains(longitude)
    }

    private func captures(pattern: String, text: String) -> [String]? {
        guard let expression = try? NSRegularExpression(pattern: pattern),
              let match = expression.firstMatch(
                in: text,
                range: NSRange(text.startIndex..., in: text)
              ), match.numberOfRanges >= 3 else { return nil }
        let values = (1...2).compactMap { index -> String? in
            guard let range = Range(match.range(at: index), in: text) else { return nil }
            return String(text[range])
        }
        return values.count == 2 ? values : nil
    }

    private func allCaptures(pattern: String, text: String) -> [[String]] {
        guard let expression = try? NSRegularExpression(pattern: pattern) else { return [] }
        return expression.matches(
            in: text,
            range: NSRange(text.startIndex..., in: text)
        ).compactMap { match -> [String]? in
            guard match.numberOfRanges >= 3 else { return nil }
            let values = (1...2).compactMap { index -> String? in
                guard let range = Range(match.range(at: index), in: text) else { return nil }
                return String(text[range])
            }
            return values.count == 2 ? values : nil
        }
    }
}
