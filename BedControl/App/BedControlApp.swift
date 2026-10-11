import SwiftUI

@main
struct BedControlApp: App {
    @State private var controller = BedController()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(controller)
                .tint(.indigo)
        }
        .onChange(of: scenePhase) { _, phase in
            // Never leave a held movement running when the app leaves the foreground.
            if phase == .background {
                controller.endHold()
                controller.cancelFavorite()
            } else if phase != .active {
                controller.endHold()
            }
            if phase == .active { Task { await controller.refreshNow() } }
        }
    }
}
