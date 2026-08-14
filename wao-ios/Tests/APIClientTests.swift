import XCTest
@testable import WAO

final class APIClientTests: XCTestCase {
    override func tearDown() {
        MockURLProtocol.handler = nil
        super.tearDown()
    }

    func testDecodesRealProjectEnvelope() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.url?.path, "/api/projects")
            let json = """
            {"code":0,"message":"success","data":[{"id":"p1","name":"原生客户端","status":"进行中","progress":35,"goal":"实机可用"}],"timestamp":1}
            """
            return (Self.response(for: request), Data(json.utf8))
        }

        let projects = try await client.get("/api/projects", as: [Project].self)

        XCTAssertEqual(projects, [Project(id: "p1", name: "原生客户端", status: "进行中", progress: 35, goal: "实机可用")])
    }

    func testEncodesEmailLoginAndDecodesSession() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
            let body = try Self.requestBody(from: request)
            let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
            XCTAssertEqual(object["email"], "user@example.com")
            XCTAssertEqual(object["password"], "secret123")
            let json = """
            {"code":0,"message":"success","data":{"accountId":"a1","member":{"id":"m1","name":"测试用户","initial":"测","color":"#4358D6","role":"成员"}},"timestamp":1}
            """
            return (Self.response(for: request), Data(json.utf8))
        }

        let profile = try await client.send(
            "/api/auth/email/login",
            method: .post,
            body: EmailLoginRequest(email: "user@example.com", password: "secret123"),
            as: AuthSession.self
        )

        XCTAssertEqual(profile.member.name, "测试用户")
    }

    func testSurfacesBusinessFailureMessage() async {
        let client = makeClient { request in
            let json = """
            {"code":40000,"message":"项目未启用 AI Run","data":null,"timestamp":1}
            """
            return (Self.response(for: request, status: 400), Data(json.utf8))
        }

        do {
            let _: [AiRun] = try await client.get("/api/ai/runs")
            XCTFail("应抛出服务端错误")
        } catch {
            XCTAssertEqual(error.localizedDescription, "项目未启用 AI Run")
        }
    }

    private func makeClient(
        handler: @escaping (URLRequest) throws -> (HTTPURLResponse, Data)
    ) -> APIClient {
        MockURLProtocol.handler = handler
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockURLProtocol.self]
        return APIClient(
            baseURL: URL(string: "https://wao.example")!,
            session: URLSession(configuration: configuration)
        )
    }

    private static func response(for request: URLRequest, status: Int = 200) -> HTTPURLResponse {
        HTTPURLResponse(
            url: request.url!,
            statusCode: status,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        )!
    }

    private static func requestBody(from request: URLRequest) throws -> Data {
        if let body = request.httpBody { return body }
        let stream = try XCTUnwrap(request.httpBodyStream)
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1024)
        while true {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count < 0 { throw stream.streamError ?? APIError.invalidResponse }
            if count == 0 { break }
            data.append(buffer, count: count)
        }
        return data
    }
}

private final class MockURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = Self.handler else {
            XCTFail("未配置 MockURLProtocol")
            return
        }
        do {
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}
