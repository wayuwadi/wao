import Foundation

final class CompanionAPI {
    private let baseURL = URL(string: "https://wao.wayuwa.cn")!
    private let session: URLSession
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(session: URLSession = .shared) {
        self.session = session
    }

    func createPairing(_ body: CreatePairingRequest) async throws -> PairingCreated {
        try await send("/api/public/companion/pairings", method: "POST", body: body)
    }

    func pollPairing(id: String, secret: String) async throws -> PairingPoll {
        let escaped = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
        return try await send("/api/public/companion/pairings/\(escaped)/poll", method: "POST",
                              body: PollPairingRequest(pollSecret: secret))
    }

    func activities(token: String) async throws -> CompanionSnapshot {
        try await send("/api/public/companion/activities", method: "GET", token: token)
    }

    func markRead(id: String, token: String) async throws {
        let escaped = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
        let _: EmptyResponse = try await send("/api/public/companion/activities/\(escaped)/read",
                                              method: "POST", body: EmptyRequest(), token: token)
    }

    func markAllRead(token: String) async throws {
        let _: EmptyResponse = try await send("/api/public/companion/activities/read-all",
                                              method: "POST", body: EmptyRequest(), token: token)
    }

    private func send<T: Decodable>(_ path: String, method: String, token: String? = nil) async throws -> T {
        try await send(path, method: method, body: Optional<EmptyRequest>.none, token: token)
    }

    private func send<T: Decodable, Body: Encodable>(_ path: String, method: String,
                                                      body: Body?, token: String? = nil) async throws -> T {
        guard let url = URL(string: path, relativeTo: baseURL)?.absoluteURL else {
            throw CompanionError.api("WAO 请求地址无效。")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 15
        request.setValue("WAO-Companion/1.0 (macOS)", forHTTPHeaderField: "User-Agent")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.httpBody = try encoder.encode(body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw CompanionError.api("WAO 返回了无效响应。") }
        if http.statusCode == 401 { throw CompanionError.unauthorized }
        let envelope: ApiEnvelope<T>
        do { envelope = try decoder.decode(ApiEnvelope<T>.self, from: data) }
        catch { throw CompanionError.api("WAO 返回 HTTP \(http.statusCode)。") }
        guard (200..<300).contains(http.statusCode), envelope.code == 0 else {
            throw CompanionError.api(envelope.message ?? "WAO 返回 HTTP \(http.statusCode)。")
        }
        guard let value = envelope.data else {
            if T.self == EmptyResponse.self { return EmptyResponse() as! T }
            throw CompanionError.emptyData
        }
        return value
    }
}

private struct EmptyRequest: Encodable {}
private struct EmptyResponse: Decodable {}
