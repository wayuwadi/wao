import Foundation

@MainActor
final class SessionStore: ObservableObject {
    enum State: Equatable {
        case restoring
        case signedOut
        case signedIn(AuthSession)
    }

    @Published private(set) var state: State = .restoring
    @Published var errorMessage: String?
    @Published private(set) var isSubmitting = false

    private let api: APIClient
    private var didRestore = false

    init(api: APIClient = .shared) {
        self.api = api
    }

    func restore() async {
        guard !didRestore else { return }
        didRestore = true
        do {
            let profile = try await api.get("/api/auth/session", as: AuthSession.self)
            state = .signedIn(profile)
        } catch APIError.unauthorized {
            state = .signedOut
        } catch {
            errorMessage = error.localizedDescription
            state = .signedOut
        }
    }

    func login(email: String, password: String) async {
        guard !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !password.isEmpty else {
            errorMessage = "请输入邮箱和密码"
            return
        }
        isSubmitting = true
        errorMessage = nil
        defer { isSubmitting = false }
        do {
            let profile = try await api.send(
                "/api/auth/email/login",
                method: .post,
                body: EmailLoginRequest(email: email, password: password),
                as: AuthSession.self
            )
            state = .signedIn(profile)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func logout() async {
        isSubmitting = true
        defer { isSubmitting = false }
        do {
            _ = try await api.send("/api/auth/logout", method: .post, as: EmptyResponse.self)
        } catch {
            // 服务端会话即使已经失效，本机也必须退出，避免停留在私有数据页面。
        }
        HTTPCookieStorage.shared.cookies?.forEach { HTTPCookieStorage.shared.deleteCookie($0) }
        state = .signedOut
    }
}
