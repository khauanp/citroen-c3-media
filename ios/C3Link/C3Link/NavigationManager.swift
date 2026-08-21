import Combine
import CoreLocation
import Foundation
import UIKit

@MainActor
final class NavigationManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var destinationQuery = "" {
        didSet {
            if !suppressSuggestions { scheduleSuggestions() }
        }
    }
    @Published private(set) var searchResults: [DestinationResult] = []
    @Published private(set) var isSearching = false
    @Published private(set) var isCalculating = false
    @Published private(set) var isNavigating = false
    @Published private(set) var isRecalculating = false
    @Published private(set) var isImporting = false
    @Published private(set) var currentInstruction = ""
    @Published private(set) var routeSummary = ""
    @Published private(set) var errorMessage = ""
    @Published private(set) var locationStatus = "Aguardando GPS"
    @Published private(set) var currentCoordinate: CLLocationCoordinate2D?
    @Published private(set) var activeDestination: DestinationResult?
    @Published private(set) var routeCoordinates: [CLLocationCoordinate2D] = []
    @Published private(set) var routeRevision = 0
    @Published private(set) var routeConfirmedOnTablet = false
    let transport = C3LinkTransport()
    private let locationManager = CLLocationManager()
    private let navigationService = OpenNavigationService()
    private let destinationImporter = DestinationLinkImporter()
    private var tileRelay: MapTileRelay!
    private var route: NavigationRoute?
    private var destination: DestinationResult?
    private var routeId = ""
    private var stepIndex = 0
    private var lastLocation: CLLocation?
    private var remainingAtRoutePoint: [Double] = []
    private var lastRoutePointIndex = 0
    private var deviationCount = 0
    private var lastRecalculationAt = Date.distantPast
    private var routeRetryTask: Task<Void, Never>?
    private var routeKeepAliveTask: Task<Void, Never>?
    private var searchTask: Task<Void, Never>?
    private var suppressSuggestions = false

    override init() {
        super.init()
        let relay = MapTileRelay(transport: transport)
        tileRelay = relay
        transport.onTileRequest = { [weak relay] keys in relay?.request(keys) }
        transport.onReady = { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.routeConfirmedOnTablet = false
                self.sendCurrentRoute()
                if self.isNavigating { self.scheduleRouteRetries(for: self.routeId) }
                if let location = self.lastLocation { self.sendPosition(location) }
            }
        }
        transport.onRouteAcknowledged = { [weak self] acknowledgedRouteId in
            Task { @MainActor in self?.acknowledgeRoute(acknowledgedRouteId) }
        }
        transport.onRouteNeeded = { [weak self] requestedRouteId in
            Task { @MainActor in self?.resendRoute(requestedRouteId) }
        }
        transport.onRouteRejected = { [weak self] rejectedRouteId in
            Task { @MainActor in self?.rejectRoute(rejectedRouteId) }
        }
        locationManager.delegate = self
        locationManager.activityType = .automotiveNavigation
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = 20
        locationManager.pausesLocationUpdatesAutomatically = true
        locationManager.showsBackgroundLocationIndicator = true
        locationManager.requestWhenInUseAuthorization()
        locationManager.startUpdatingLocation()
        updateAuthorizationStatus(locationManager.authorizationStatus)
    }

    func searchDestination() {
        let query = destinationQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return }
        searchTask?.cancel()
        searchTask = Task { [weak self] in
            await self?.performSearch(query: query, reportErrors: true)
        }
    }

    func openExternalSearch(_ provider: ExternalMapProvider) {
        let query = destinationQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        var components: URLComponents?
        switch provider {
        case .googleMaps:
            components = URLComponents(string: "https://www.google.com/maps/search/")
            components?.queryItems = [URLQueryItem(name: "api", value: "1")] +
                (query.isEmpty ? [] : [URLQueryItem(name: "query", value: query)])
        case .waze:
            components = URLComponents(string: "https://waze.com/ul")
            components?.queryItems = (query.isEmpty ? [] : [URLQueryItem(name: "q", value: query)]) + [
                URLQueryItem(name: "navigate", value: "no"),
                URLQueryItem(name: "utm_source", value: "c3_link"),
            ]
        }
        if let url = components?.url { UIApplication.shared.open(url) }
    }

    func importDestinationFromClipboard() {
        guard !isImporting else { return }
        let clipboard = UIPasteboard.general.string ?? ""
        errorMessage = ""
        isImporting = true
        Task {
            do {
                switch try await destinationImporter.resolve(clipboard) {
                case let .coordinate(latitude, longitude, label):
                    let result = await navigationService.destination(
                        latitude: latitude,
                        longitude: longitude,
                        preferredLabel: label
                    )
                    isImporting = false
                    startNavigation(to: result)
                case let .search(query):
                    suppressSuggestions = true
                    destinationQuery = query
                    suppressSuggestions = false
                    isImporting = false
                    searchDestination()
                }
            } catch {
                errorMessage = error.localizedDescription
                isImporting = false
            }
        }
    }

    func startNavigation(to result: DestinationResult) {
        guard let origin = lastLocation else {
            errorMessage = "Aguardando sinal de GPS…"
            locationManager.startUpdatingLocation()
            return
        }
        searchTask?.cancel()
        destination = result
        activeDestination = result
        routeCoordinates = []
        routeRevision += 1
        routeConfirmedOnTablet = false
        suppressSuggestions = true
        destinationQuery = result.title
        suppressSuggestions = false
        searchResults = []
        errorMessage = ""
        isCalculating = true
        Task {
            do {
                let calculated = try await navigationService.route(from: origin.coordinate, to: result)
                install(calculated, destination: result)
            } catch {
                errorMessage = "Falha ao calcular a rota: \(error.localizedDescription)"
            }
            isCalculating = false
        }
    }

    func stopNavigation() {
        searchTask?.cancel()
        routeRetryTask?.cancel()
        routeRetryTask = nil
        routeKeepAliveTask?.cancel()
        routeKeepAliveTask = nil
        tileRelay.cancelPending()
        transport.sendStop()
        route = nil
        destination = nil
        activeDestination = nil
        routeId = ""
        stepIndex = 0
        remainingAtRoutePoint = []
        lastRoutePointIndex = 0
        deviationCount = 0
        currentInstruction = ""
        routeSummary = ""
        routeCoordinates = []
        routeRevision += 1
        routeConfirmedOnTablet = false
        isNavigating = false
        isRecalculating = false
        locationManager.allowsBackgroundLocationUpdates = false
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = 20
        locationManager.pausesLocationUpdatesAutomatically = true
    }

    private func scheduleSuggestions() {
        searchTask?.cancel()
        let query = destinationQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !isNavigating, query.count >= 3 else {
            if !isNavigating { searchResults = [] }
            isSearching = false
            return
        }
        searchTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 550_000_000)
            guard !Task.isCancelled, let self else { return }
            await self.performSearch(query: query, reportErrors: false)
        }
    }

    private func performSearch(query: String, reportErrors: Bool) async {
        guard !Task.isCancelled else { return }
        if reportErrors { errorMessage = "" }
        isSearching = true
        do {
            let results = try await navigationService.search(
                query: query,
                near: lastLocation,
                preferExactAddress: reportErrors
            )
            guard !Task.isCancelled,
                  destinationQuery.trimmingCharacters(in: .whitespacesAndNewlines) == query,
                  !isNavigating else { return }
            searchResults = results
            if reportErrors { errorMessage = "" }
        } catch is CancellationError {
            return
        } catch {
            if reportErrors { errorMessage = error.localizedDescription }
        }
        if destinationQuery.trimmingCharacters(in: .whitespacesAndNewlines) == query {
            isSearching = false
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last, location.horizontalAccuracy >= 0 else { return }
        Task { @MainActor in
            lastLocation = location
            currentCoordinate = location.coordinate
            locationStatus = "GPS ativo • ±\(Int(location.horizontalAccuracy)) m"
            if isNavigating { sendPosition(location) }
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            updateAuthorizationStatus(manager.authorizationStatus)
            if manager.authorizationStatus == .authorizedWhenInUse {
                manager.requestAlwaysAuthorization()
            }
            if manager.authorizationStatus == .authorizedAlways || manager.authorizationStatus == .authorizedWhenInUse {
                manager.startUpdatingLocation()
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in locationStatus = "GPS indisponível: \(error.localizedDescription)" }
    }

    private func install(_ calculated: NavigationRoute, destination: DestinationResult) {
        route = calculated
        self.destination = destination
        activeDestination = destination
        routeCoordinates = calculated.coordinates
        routeRevision += 1
        routeConfirmedOnTablet = false
        routeId = UUID().uuidString
        stepIndex = calculated.steps.count > 1 ? 1 : 0
        lastRoutePointIndex = 0
        deviationCount = 0
        remainingAtRoutePoint = remainingDistances(for: calculated.coordinates)
        currentInstruction = currentStep?.instruction ?? "Siga a rota"
        routeSummary = formatSummary(distance: calculated.distanceMeters, seconds: calculated.durationSeconds)
        isNavigating = true
        isRecalculating = false
        locationManager.activityType = .automotiveNavigation
        locationManager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        locationManager.distanceFilter = 2
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.startUpdatingLocation()
        sendCurrentRoute()
        scheduleRouteRetries(for: routeId)
        scheduleRouteKeepAlive(for: routeId)
        if let location = lastLocation { sendPosition(location) }
    }

    private var currentStep: NavigationStep? {
        guard let route, !route.steps.isEmpty else { return nil }
        return route.steps[min(stepIndex, route.steps.count - 1)]
    }

    private func sendCurrentRoute() {
        guard transport.isReady, let route, let destination else { return }
        guard route.encodedPolyline.utf8.count <= 28_000 else {
            errorMessage = "A rota ficou longa demais para ser enviada ao tablet"
            return
        }
        transport.sendRoute(
            routeId: routeId,
            destination: destination.title,
            polyline: route.encodedPolyline,
            distanceMeters: route.distanceMeters,
            durationSeconds: route.durationSeconds
        )
    }

    private func scheduleRouteRetries(for expectedRouteId: String) {
        routeRetryTask?.cancel()
        routeRetryTask = Task { [weak self] in
            var attempt = 0
            while !Task.isCancelled {
                let delay: UInt64
                if attempt < 8 {
                    delay = 650_000_000
                } else if attempt < 20 {
                    delay = 2_500_000_000
                } else {
                    delay = 10_000_000_000
                }
                try? await Task.sleep(nanoseconds: delay)
                guard !Task.isCancelled,
                      let self,
                      self.isNavigating,
                      self.routeId == expectedRouteId else { return }
                if self.routeConfirmedOnTablet { return }
                self.sendCurrentRoute()
                attempt += 1
            }
        }
    }

    private func scheduleRouteKeepAlive(for expectedRouteId: String) {
        routeKeepAliveTask?.cancel()
        routeKeepAliveTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                guard !Task.isCancelled,
                      let self,
                      self.isNavigating,
                      self.routeId == expectedRouteId else { return }
                self.sendCurrentRoute()
                if let location = self.lastLocation { self.sendPosition(location) }
            }
        }
    }

    private func acknowledgeRoute(_ acknowledgedRouteId: String?) {
        guard isNavigating, matchesCurrentRoute(acknowledgedRouteId) else { return }
        routeConfirmedOnTablet = true
        routeRetryTask?.cancel()
        routeRetryTask = nil
        if let location = lastLocation { sendPosition(location) }
    }

    private func resendRoute(_ requestedRouteId: String?) {
        guard isNavigating else { return }
        // A stale position/route on either side is repaired by making the
        // current iPhone route authoritative again.
        _ = requestedRouteId
        routeConfirmedOnTablet = false
        sendCurrentRoute()
        scheduleRouteRetries(for: routeId)
    }

    private func rejectRoute(_ rejectedRouteId: String?) {
        guard isNavigating, matchesCurrentRoute(rejectedRouteId) else { return }
        routeConfirmedOnTablet = false
        routeRetryTask?.cancel()
        guard let destination, let location = lastLocation, !isRecalculating else {
            errorMessage = "O tablet recusou os dados da rota. Toque em encerrar e escolha o destino novamente."
            return
        }
        isRecalculating = true
        errorMessage = "Recriando a rota para o tablet…"
        Task {
            do {
                let recalculated = try await navigationService.route(from: location.coordinate, to: destination)
                errorMessage = ""
                install(recalculated, destination: destination)
            } catch {
                isRecalculating = false
                errorMessage = "Não foi possível recriar a rota: \(error.localizedDescription)"
            }
        }
    }

    private func matchesCurrentRoute(_ candidate: String?) -> Bool {
        guard let candidate, !candidate.isEmpty else { return true }
        return candidate == routeId
    }

    private func sendPosition(_ location: CLLocation) {
        guard let route, !route.coordinates.isEmpty else { return }
        let nearest = nearestRoutePoint(to: location, coordinates: route.coordinates)
        lastRoutePointIndex = nearest.index
        let routeRemaining = remainingAtRoutePoint.indices.contains(nearest.index)
            ? remainingAtRoutePoint[nearest.index]
            : route.distanceMeters
        advanceStepIfNeeded(location)
        let step = currentStep
        let stepDistance = step.map {
            location.distance(from: CLLocation(latitude: $0.coordinate.latitude, longitude: $0.coordinate.longitude))
        } ?? routeRemaining
        let remainingSeconds = route.distanceMeters > 0
            ? route.durationSeconds * routeRemaining / route.distanceMeters
            : 0
        let instruction = step?.instruction ?? "Siga a rota"
        currentInstruction = instruction
        routeSummary = formatSummary(distance: routeRemaining, seconds: remainingSeconds)
        transport.sendPosition(
            routeId: routeId,
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            speedMps: max(0, location.speed),
            course: location.course >= 0 ? location.course : 0,
            instruction: instruction,
            maneuver: step?.maneuver ?? "straight",
            stepDistanceMeters: stepDistance,
            remainingDistanceMeters: routeRemaining,
            remainingSeconds: remainingSeconds
        )
        evaluateDeviation(nearest.distanceMeters, from: location)
    }

    private func advanceStepIfNeeded(_ location: CLLocation) {
        guard let route, stepIndex < route.steps.count - 1, let step = currentStep else { return }
        let target = CLLocation(latitude: step.coordinate.latitude, longitude: step.coordinate.longitude)
        let threshold = max(28.0, max(0, location.speed) * 4.5)
        if location.distance(from: target) <= threshold { stepIndex += 1 }
    }

    private func evaluateDeviation(_ distanceMeters: Double, from location: CLLocation) {
        guard distanceMeters > 75, location.horizontalAccuracy < 45 else {
            deviationCount = 0
            return
        }
        deviationCount += 1
        guard deviationCount >= 3,
              !isRecalculating,
              Date().timeIntervalSince(lastRecalculationAt) >= 15,
              let destination else { return }
        deviationCount = 0
        isRecalculating = true
        lastRecalculationAt = Date()
        Task {
            do {
                let recalculated = try await navigationService.route(from: location.coordinate, to: destination)
                install(recalculated, destination: destination)
            } catch {
                isRecalculating = false
                errorMessage = "Recálculo pendente: \(error.localizedDescription)"
            }
        }
    }

    private func nearestRoutePoint(
        to location: CLLocation,
        coordinates: [CLLocationCoordinate2D]
    ) -> (index: Int, distanceMeters: Double) {
        guard !coordinates.isEmpty else { return (0, .greatestFiniteMagnitude) }
        let lower = max(0, lastRoutePointIndex - 80)
        let upper = min(coordinates.count - 1, lastRoutePointIndex + 900)
        var bestIndex = lower
        var bestDistance = Double.greatestFiniteMagnitude
        for index in lower...upper {
            let point = coordinates[index]
            let distance = fastDistance(
                location.coordinate.latitude,
                location.coordinate.longitude,
                point.latitude,
                point.longitude
            )
            if distance < bestDistance {
                bestDistance = distance
                bestIndex = index
            }
        }
        if bestDistance > 250 {
            for (index, point) in coordinates.enumerated() {
                let distance = fastDistance(
                    location.coordinate.latitude,
                    location.coordinate.longitude,
                    point.latitude,
                    point.longitude
                )
                if distance < bestDistance {
                    bestDistance = distance
                    bestIndex = index
                }
            }
        }
        return (bestIndex, bestDistance)
    }

    private func remainingDistances(for coordinates: [CLLocationCoordinate2D]) -> [Double] {
        guard coordinates.count >= 2 else { return Array(repeating: 0, count: coordinates.count) }
        var result = Array(repeating: 0.0, count: coordinates.count)
        for index in stride(from: coordinates.count - 2, through: 0, by: -1) {
            let a = coordinates[index]
            let b = coordinates[index + 1]
            result[index] = result[index + 1] + fastDistance(a.latitude, a.longitude, b.latitude, b.longitude)
        }
        return result
    }

    private func fastDistance(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let radians = Double.pi / 180
        let x = (lon2 - lon1) * radians * cos((lat1 + lat2) * radians / 2)
        let y = (lat2 - lat1) * radians
        return sqrt(x * x + y * y) * 6_371_000
    }

    private func updateAuthorizationStatus(_ status: CLAuthorizationStatus) {
        switch status {
        case .authorizedAlways: locationStatus = "Localização sempre autorizada"
        case .authorizedWhenInUse: locationStatus = "Permita Sempre para bloquear a tela"
        case .denied, .restricted: locationStatus = "Autorize a localização nos Ajustes"
        case .notDetermined: locationStatus = "Aguardando permissão de localização"
        @unknown default: locationStatus = "Verificando localização"
        }
    }

    private func formatSummary(distance: Double, seconds: Double) -> String {
        let distanceText = distance < 1_000
            ? "\(Int(distance)) m"
            : String(format: "%.1f km", distance / 1_000)
        let minutes = Int(seconds / 60)
        let timeText = minutes < 60 ? "\(minutes) min" : "\(minutes / 60)h \(minutes % 60)min"
        return "\(distanceText) • \(timeText)"
    }
}
