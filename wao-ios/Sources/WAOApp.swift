import SwiftUI

@main
struct WAOApp: App {
    @StateObject private var session = SessionStore()

    var body: some Scene {
        WindowGroup {
            Group {
                switch session.state {
                case .restoring:
                    ProgressView("正在恢复登录…")
                case .signedOut:
                    LoginView(session: session)
                case .signedIn(let profile):
                    RootView(session: session, profile: profile)
                }
            }
            .task {
                await session.restore()
            }
        }
    }
}
