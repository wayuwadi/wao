import SwiftUI

struct RootView: View {
    @ObservedObject var session: SessionStore
    let profile: AuthSession

    var body: some View {
        TabView {
            NavigationStack {
                ProjectsView()
                    .accountToolbar(profile: profile, session: session)
            }
            .tabItem { Label("项目", systemImage: "square.stack.3d.up") }

            NavigationStack {
                AiRunsView()
                    .accountToolbar(profile: profile, session: session)
            }
            .tabItem { Label("AI", systemImage: "sparkles") }

            NavigationStack {
                AutomationView()
                    .accountToolbar(profile: profile, session: session)
            }
            .tabItem { Label("自动化", systemImage: "bolt.badge.clock") }
        }
    }
}

private extension View {
    func accountToolbar(profile: AuthSession, session: SessionStore) -> some View {
        toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Text(profile.member.name)
                    Button("退出登录", role: .destructive) {
                        Task { await session.logout() }
                    }
                } label: {
                    Text(profile.member.initial)
                        .font(.caption.bold())
                        .frame(width: 30, height: 30)
                        .background(Color.accentColor.opacity(0.15), in: Circle())
                        .accessibilityLabel("账号：\(profile.member.name)")
                }
            }
        }
    }
}
