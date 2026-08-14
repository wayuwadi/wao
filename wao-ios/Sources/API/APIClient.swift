import Foundation

enum HTTPMethod: String {
    case get = "GET"
    case post = "POST"
    case patch = "PATCH"
}

enum APIError: LocalizedError, Equatable {
    case invalidConfiguration
    case invalidResponse
    case unauthorized
    case server(String)
    case transport(String)

    var errorDescription: String? {
        switch self {
        case .invalidConfiguration:
            return "服务器地址配置无效"
        case .invalidResponse:
            return "服务器返回了无法识别的数据"
        case .unauthorized:
            return "登录已过期，请重新登录"
        case .server(let message), .transport(let message):
            return message
        }
    }
}

struct APIEnvelope<Value: Decodable>: Decodable {
    let code: Int
    let message: String
    let data: Value
}

struct EmptyResponse: Decodable {}

actor APIClient {
    static let shared = APIClient()

    let baseURL: URL
    private let session: URLSession
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    init(baseURL: URL? = AppConfiguration.apiBaseURL, session: URLSession? = nil) {
        self.baseURL = baseURL ?? URL(string: "https://wao.wayuwa.cn")!
        if let session {
            self.session = session
        } else {
            let configuration = URLSessionConfiguration.default
            configuration.httpCookieStorage = .shared
            configuration.httpCookieAcceptPolicy = .always
            configuration.httpShouldSetCookies = true
            configuration.waitsForConnectivity = true
            configuration.timeoutIntervalForRequest = 30
            self.session = URLSession(configuration: configuration)
        }
    }

    func get<Value: Decodable>(_ path: String, as type: Value.Type = Value.self) async throws -> Value {
        try await request(path, method: .get, as: type)
    }

    func send<Body: Encodable, Value: Decodable>(
        _ path: String,
        method: HTTPMethod,
        body: Body,
        as type: Value.Type = Value.self
    ) async throws -> Value {
        try await request(path, method: method, body: body, as: type)
    }

    func send<Value: Decodable>(
        _ path: String,
        method: HTTPMethod,
        as type: Value.Type = Value.self
    ) async throws -> Value {
        try await request(path, method: method, as: type)
    }

    private func request<Value: Decodable>(
        _ path: String,
        method: HTTPMethod,
        body: (any Encodable)? = nil,
        as type: Value.Type
    ) async throws -> Value {
        guard let url = URL(string: path, relativeTo: baseURL) else {
            throw APIError.invalidConfiguration
        }
        var request = URLRequest(url: url)
        request.httpMethod = method.rawValue
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try encoder.encode(AnyEncodable(body))
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw APIError.transport(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else {
            throw APIError.invalidResponse
        }
        if http.statusCode == 401 {
            throw APIError.unauthorized
        }
        guard (200..<300).contains(http.statusCode) else {
            let failure = try? decoder.decode(APIFailureEnvelope.self, from: data)
            throw APIError.server(failure?.message ?? "请求失败（HTTP \(http.statusCode)）")
        }
        do {
            let envelope = try decoder.decode(APIEnvelope<Value>.self, from: data)
            guard envelope.code == 0 else {
                throw APIError.server(envelope.message)
            }
            return envelope.data
        } catch let error as APIError {
            throw error
        } catch {
            throw APIError.invalidResponse
        }
    }
}

private struct APIFailureEnvelope: Decodable {
    let message: String
}

private struct AnyEncodable: Encodable {
    private let encodeValue: (Encoder) throws -> Void

    init(_ value: any Encodable) {
        encodeValue = value.encode
    }

    func encode(to encoder: Encoder) throws {
        try encodeValue(encoder)
    }
}

enum AppConfiguration {
    static var apiBaseURL: URL? {
        guard let raw = Bundle.main.object(forInfoDictionaryKey: "WAO_API_BASE_URL") as? String else {
            return nil
        }
        return URL(string: raw)
    }
}
