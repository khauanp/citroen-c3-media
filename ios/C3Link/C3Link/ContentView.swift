import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var navigation: NavigationManager
    @State private var showingSettings = false
    @State private var fitRequestID = 0
    @FocusState private var destinationFocused: Bool

    var body: some View {
        NavigationStack {
            ZStack {
                Color(.systemGroupedBackground).ignoresSafeArea()
                ScrollView {
                    LazyVStack(spacing: 16) {
                        hero
                        ConnectionCard(transport: navigation.transport, gpsStatus: navigation.locationStatus)
                        destinationCard
                        searchResults
                        routeMapCard
                        audioCard
                        footer
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
                .scrollDismissesKeyboard(.interactively)
            }
            .navigationTitle("C3 Link")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showingSettings = true } label: {
                        Image(systemName: "gearshape.fill")
                    }
                    .accessibilityLabel("Ajustes avançados")
                }
            }
            .sheet(isPresented: $showingSettings) { AdvancedSettingsView() }
        }
    }

    private var hero: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(.white.opacity(0.16))
                    .frame(width: 62, height: 62)
                Image(systemName: "car.side.fill")
                    .font(.system(size: 31, weight: .bold))
                    .foregroundStyle(.white)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("C3 LINK")
                    .font(.system(size: 25, weight: .black, design: .rounded))
                    .foregroundStyle(.white)
                Text("Navegação visual com a tela bloqueada")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.white.opacity(0.82))
            }
            Spacer(minLength: 0)
        }
        .padding(18)
        .background(
            LinearGradient(
                colors: [Color(red: 0.82, green: 0.06, blue: 0.10), Color(red: 0.37, green: 0.04, blue: 0.12)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            ),
            in: RoundedRectangle(cornerRadius: 24, style: .continuous)
        )
        .shadow(color: .red.opacity(0.18), radius: 18, y: 9)
    }

    private var destinationCard: some View {
        VStack(alignment: .leading, spacing: 13) {
            Label("Escolha o destino", systemImage: "location.magnifyingglass")
                .font(.headline)

            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
                TextField("Rua, número, cidade ou lugar", text: $navigation.destinationQuery)
                    .textInputAutocapitalization(.words)
                    .textContentType(.fullStreetAddress)
                    .submitLabel(.search)
                    .focused($destinationFocused)
                    .onSubmit {
                        destinationFocused = false
                        navigation.searchDestination()
                    }
                if !navigation.destinationQuery.isEmpty && !navigation.isNavigating {
                    Button {
                        navigation.destinationQuery = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.tertiary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Limpar destino")
                }
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 52)
            .background(Color(.tertiarySystemBackground), in: RoundedRectangle(cornerRadius: 15))
            .overlay {
                RoundedRectangle(cornerRadius: 15)
                    .stroke(destinationFocused ? Color.blue : Color.primary.opacity(0.07), lineWidth: 1.5)
            }

            if !navigation.isNavigating {
                Button {
                    destinationFocused = false
                    navigation.searchDestination()
                } label: {
                    HStack {
                        if navigation.isSearching { ProgressView().tint(.white) }
                        Label(
                            navigation.isSearching ? "Buscando endereços…" : "Buscar destino",
                            systemImage: "arrow.right.circle.fill"
                        )
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
                .controlSize(.large)
                .disabled(
                    navigation.destinationQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                        navigation.isSearching || navigation.isCalculating
                )
            }

            Divider()

            Text("OU TRAGA UM DESTINO PRONTO")
                .font(.caption2.bold())
                .tracking(0.8)
                .foregroundStyle(.secondary)

            ViewThatFits(in: .horizontal) {
                HStack(spacing: 9) { providerButtons }
                VStack(spacing: 9) { providerButtons }
            }

            Button { navigation.importDestinationFromClipboard() } label: {
                HStack {
                    if navigation.isImporting { ProgressView() }
                    Label(
                        navigation.isImporting ? "Lendo o destino…" : "Colar link do Maps ou Waze",
                        systemImage: "doc.on.clipboard.fill"
                    )
                    .frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .disabled(navigation.isImporting || navigation.isCalculating)

            Text("Aceita o link sozinho ou a mensagem completa compartilhada pelo Waze.")
                .font(.caption)
                .foregroundStyle(.secondary)

            if !navigation.errorMessage.isEmpty {
                Label(navigation.errorMessage, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
                    .background(Color.red.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
            }
        }
        .c3Card()
    }

    @ViewBuilder
    private var providerButtons: some View {
        Button { navigation.openExternalSearch(.googleMaps) } label: {
            Label("Abrir Google Maps", systemImage: "map.fill")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)

        Button { navigation.openExternalSearch(.waze) } label: {
            Label("Abrir Waze", systemImage: "car.fill")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
    }

    @ViewBuilder
    private var searchResults: some View {
        if !navigation.searchResults.isEmpty && !navigation.isNavigating {
            VStack(alignment: .leading, spacing: 9) {
                HStack {
                    Label("Sugestões", systemImage: "sparkle.magnifyingglass")
                        .font(.headline)
                    Spacer()
                    Text("confira cidade e estado")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                ForEach(navigation.searchResults) { result in
                    Button {
                        destinationFocused = false
                        navigation.startNavigation(to: result)
                    } label: {
                        HStack(spacing: 12) {
                            ZStack {
                                Circle()
                                    .fill(Color.red.opacity(0.12))
                                    .frame(width: 40, height: 40)
                                Image(systemName: "mappin.and.ellipse")
                                    .foregroundStyle(.red)
                            }
                            VStack(alignment: .leading, spacing: 3) {
                                Text(result.title)
                                    .font(.body.weight(.semibold))
                                    .foregroundStyle(.primary)
                                    .lineLimit(2)
                                    .multilineTextAlignment(.leading)
                                if !result.subtitle.isEmpty {
                                    Text(result.subtitle)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(2)
                                        .multilineTextAlignment(.leading)
                                }
                            }
                            Spacer(minLength: 4)
                            Image(systemName: "chevron.right")
                                .font(.caption.bold())
                                .foregroundStyle(.tertiary)
                        }
                        .padding(.vertical, 7)
                    }
                    .buttonStyle(.plain)
                    if result.id != navigation.searchResults.last?.id { Divider() }
                }
            }
            .c3Card()
        }
    }

    @ViewBuilder
    private var routeMapCard: some View {
        if let destination = navigation.activeDestination,
           navigation.isCalculating || navigation.isNavigating {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("VISÃO GERAL DA ROTA")
                            .font(.caption2.bold())
                            .tracking(0.8)
                            .foregroundStyle(.blue)
                        Text(destination.title)
                            .font(.headline)
                            .lineLimit(2)
                        if !destination.subtitle.isEmpty {
                            Text(destination.subtitle)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                        }
                    }
                    Spacer()
                    Button {
                        fitRequestID += 1
                    } label: {
                        Image(systemName: "arrow.up.left.and.arrow.down.right")
                            .frame(width: 32, height: 32)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityLabel("Mostrar rota inteira")
                }

                ZStack {
                    RoutePreviewMap(
                        routeCoordinates: navigation.routeCoordinates,
                        safetyCameras: navigation.safetyCameras,
                        currentCoordinate: navigation.currentCoordinate,
                        destination: destination,
                        routeRevision: navigation.routeRevision,
                        fitRequestID: fitRequestID
                    )
                    .frame(height: 300)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))

                    if navigation.isCalculating {
                        VStack(spacing: 9) {
                            ProgressView()
                                .controlSize(.large)
                            Text("Calculando a rota completa…")
                                .font(.footnote.bold())
                        }
                        .padding(16)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
                    }
                }
                .overlay(alignment: .bottomLeading) {
                    Text("Arraste, gire ou use dois dedos para dar zoom")
                        .font(.caption2.bold())
                        .foregroundStyle(.white)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(.black.opacity(0.68), in: Capsule())
                        .padding(10)
                }

                if navigation.isNavigating {
                    HStack(spacing: 9) {
                        Image(systemName: navigation.routeConfirmedOnTablet ? "checkmark.circle.fill" : "arrow.triangle.2.circlepath")
                            .foregroundStyle(navigation.routeConfirmedOnTablet ? .green : .orange)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(
                                navigation.routeConfirmedOnTablet
                                    ? "Rota azul confirmada no tablet"
                                    : "Enviando e conferindo a rota no tablet…"
                            )
                            .font(.footnote.bold())
                            Text(navigation.routeSummary)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }

                    if !navigation.currentInstruction.isEmpty {
                        Label(navigation.currentInstruction, systemImage: "arrow.turn.up.right")
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(12)
                            .background(Color.blue.opacity(0.09), in: RoundedRectangle(cornerRadius: 12))
                    }

                    if navigation.currentSpeedLimitKph != nil || navigation.upcomingCamera != nil {
                        HStack(spacing: 14) {
                            if let limit = navigation.currentSpeedLimitKph {
                                Label("Limite \(Int(limit.rounded())) km/h", systemImage: "speedometer")
                            }
                            if let camera = navigation.upcomingCamera {
                                Label(
                                    "Radar a \(formatSafetyDistance(camera.distanceMeters))",
                                    systemImage: "camera.fill"
                                )
                            }
                            Spacer(minLength: 0)
                        }
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.red)
                        .accessibilityLabel("Dados viários do OpenStreetMap")
                    }

                    Label("Pode bloquear a tela: o mapa segue no tablet e o GPS não fala.", systemImage: "lock.fill")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.green)

                    Button(role: .destructive) { navigation.stopNavigation() } label: {
                        Label("Encerrar navegação", systemImage: "stop.circle.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                }
            }
            .c3Card()
        }
    }

    private func formatSafetyDistance(_ meters: Double) -> String {
        meters < 1_000
            ? "\(Int(meters.rounded())) m"
            : String(format: "%.1f km", meters / 1_000)
    }

    private var audioCard: some View {
        HStack(alignment: .top, spacing: 13) {
            ZStack {
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.red.opacity(0.12))
                    .frame(width: 46, height: 46)
                Image(systemName: "music.note")
                    .font(.title3.bold())
                    .foregroundStyle(.red)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("Áudio continua independente do mapa")
                    .font(.headline)
                Text("Mantenha Citroën C3 como saída de áudio. O caminho continua iPhone → tablet → Bluetooth do rádio.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .c3Card()
    }

    private var footer: some View {
        Text("O mapa interativo acima serve para conferir todo o trajeto. A navegação principal permanece no tablet, sem alterar o espelhamento ou a música.")
            .font(.caption)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 4)
            .padding(.bottom, 12)
    }
}

private struct ConnectionCard: View {
    @ObservedObject var transport: C3LinkTransport
    let gpsStatus: String

    private var gpsReady: Bool {
        gpsStatus.contains("Sempre") || gpsStatus.contains("ativo")
    }

    var body: some View {
        HStack(spacing: 12) {
            StatusIcon(
                symbol: transport.isReady ? "ipad.and.iphone" : "wifi.exclamationmark",
                color: transport.isReady ? .green : .orange
            )
            VStack(alignment: .leading, spacing: 3) {
                Text(transport.isReady ? "Tablet conectado" : "Aguardando o tablet")
                    .font(.headline)
                Text(transport.statusText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 4) {
                Label(gpsReady ? "GPS OK" : "GPS", systemImage: "location.fill")
                    .font(.caption2.bold())
                    .foregroundStyle(gpsReady ? .green : .orange)
                Text(gpsStatus)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .multilineTextAlignment(.trailing)
            }
        }
        .c3Card()
    }
}

private struct StatusIcon: View {
    let symbol: String
    let color: Color

    var body: some View {
        ZStack {
            Circle()
                .fill(color.opacity(0.13))
                .frame(width: 46, height: 46)
            Image(systemName: symbol)
                .font(.title3.bold())
                .foregroundStyle(color)
        }
    }
}

private struct C3CardModifier: ViewModifier {
    func body(content: Content) -> some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .stroke(Color.primary.opacity(0.055), lineWidth: 1)
            }
    }
}

private extension View {
    func c3Card() -> some View { modifier(C3CardModifier()) }
}

private struct AdvancedSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage("c3link.routing-endpoint") private var routingEndpoint = OpenNavigationService.defaultRoutingEndpoint

    var body: some View {
        NavigationStack {
            Form {
                Section("Serviços de mapa") {
                    TextField("Rotas", text: $routingEndpoint)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                }
                Section {
                    Button("Restaurar serviços padrão") {
                        routingEndpoint = OpenNavigationService.defaultRoutingEndpoint
                    }
                } footer: {
                    Text("A busca e as sugestões usam o serviço de mapas do iPhone. Este endereço é usado somente para calcular a rota visual enviada ao tablet.")
                }
            }
            .navigationTitle("Ajustes avançados")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("OK") { dismiss() }
                }
            }
        }
    }
}
