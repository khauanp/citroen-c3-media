import SwiftUI

@main
struct C3LinkApp: App {
    @StateObject private var navigation = NavigationManager()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(navigation)
        }
    }
}
