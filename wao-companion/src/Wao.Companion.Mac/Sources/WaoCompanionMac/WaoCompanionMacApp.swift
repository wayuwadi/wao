import SwiftUI

@main
struct WaoCompanionMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        Settings { EmptyView() }
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var controller: CompanionController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let controller = CompanionController()
        self.controller = controller
        controller.start()
    }
}
