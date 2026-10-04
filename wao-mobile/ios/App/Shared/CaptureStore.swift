import Foundation
import Security

struct CaptureSession: Codable {
    var accountId: String
    var memberId: String
    var cookie: String
    var expiresAt: Date
}

enum CaptureFailure: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
}

final class NoCaptureRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

/// 主应用与分享扩展共用的真实草稿；凭据只进入共享 Keychain。
enum CaptureStore {
    static let origin = "https://wao.wayuwa.cn"
    static let group = "group.cn.wayuwa.wao.notes"
    static func container() throws -> URL {
        guard let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else {
            throw CaptureFailure.message("分享存储尚未配置，请检查应用与扩展的 App Group 签名")
        }
        let directory = url.appendingPathComponent("NoteCapture", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        return directory
    }
    static func validID(_ id: String) throws {
        guard id.range(of: "^[a-zA-Z0-9-]{1,80}$", options: .regularExpression) != nil else {
            throw CaptureFailure.message("导入编号无效")
        }
    }
    static func file(_ id: String) throws -> URL { try validID(id); return try container().appendingPathComponent(id + ".json") }
    static func save(_ item: CaptureItem) throws {
        try JSONEncoder().encode(item).write(to: file(item.id), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    static func read(_ id: String) throws -> CaptureItem { try JSONDecoder().decode(CaptureItem.self, from: Data(contentsOf: file(id))) }
    static func pending(account: String, member: String) throws -> [CaptureItem] {
        try FileManager.default.contentsOfDirectory(at: container(), includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }.map { try JSONDecoder().decode(CaptureItem.self, from: Data(contentsOf: $0)) }
            .filter { ($0.accountId == account && $0.memberId == member) || ($0.accountId == nil && $0.memberId == nil) }
    }
    static func acknowledge(_ id: String, account: String, member: String) throws {
        let item = try read(id)
        guard item.accountId == account && item.memberId == member else { throw CaptureFailure.message("这条导入属于其他成员") }
        try FileManager.default.removeItem(at: file(id))
        if let image = item.imagePath { try? FileManager.default.removeItem(at: imageFile(image)) }
    }
    static func imageFile(_ name: String) throws -> URL {
        guard name.range(of: "^[a-zA-Z0-9-]+\\.jpg$", options: .regularExpression) != nil else { throw CaptureFailure.message("图片编号无效") }
        return try container().appendingPathComponent(name)
    }
    private static func keychainQuery() throws -> [String: Any] {
        guard let access = Bundle.main.object(forInfoDictionaryKey: "WAOSharedKeychainGroup") as? String,
              !access.contains("$("), !access.isEmpty else { throw CaptureFailure.message("共享登录签名尚未配置") }
        return [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "cn.wayuwa.wao.notes.session",
                kSecAttrAccount as String: "session", kSecAttrAccessGroup as String: access]
    }
    static func session() throws -> CaptureSession? {
        var query = try keychainQuery()
        query[kSecReturnData as String] = true; query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw CaptureFailure.message("共享登录暂不可读取，请解锁手机后重试") }
        let session = try JSONDecoder().decode(CaptureSession.self, from: data)
        return session.expiresAt > Date() ? session : nil
    }
    static func clearSession() throws { let status = SecItemDelete(try keychainQuery() as CFDictionary); guard status == errSecSuccess || status == errSecItemNotFound else { throw CaptureFailure.message("共享登录清理失败") } }
    static func setSession(_ session: CaptureSession) throws {
        let query = try keychainQuery(), data = try JSONEncoder().encode(session)
        let update: [String: Any] = [kSecValueData as String: data]
        let status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var add = query; add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            add[kSecAttrSynchronizable as String] = false
            guard SecItemAdd(add as CFDictionary, nil) == errSecSuccess else { throw CaptureFailure.message("共享登录保存失败，请检查 Keychain 签名") }
        } else if status != errSecSuccess { throw CaptureFailure.message("共享登录更新失败") }
    }
    static func request(_ path: String, session: CaptureSession, method: String = "GET", data: Data? = nil,
                        contentType: String = "application/json") async throws -> [String: Any] {
        guard path.hasPrefix("/api/") else { throw CaptureFailure.message("请求路径无效") }
        var request = URLRequest(url: URL(string: origin + path)!)
        request.httpMethod = method; request.httpBody = data; request.timeoutInterval = 25
        request.setValue("WAO_SESSION=" + session.cookie, forHTTPHeaderField: "Cookie")
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let config = URLSessionConfiguration.ephemeral; config.httpCookieStorage = nil; config.httpShouldSetCookies = false
        let transport = URLSession(configuration: config, delegate: NoCaptureRedirects(), delegateQueue: nil)
        defer { transport.invalidateAndCancel() }
        let (body, response) = try await transport.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw CaptureFailure.message("服务器未返回有效响应") }
        if http.statusCode == 401 { throw CaptureFailure.message("请回 WAO 重新登录，内容已暂存本机") }
        let json = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
        guard (200..<300).contains(http.statusCode), json?["code"] as? Int == 0 else {
            throw CaptureFailure.message(json?["message"] as? String ?? "保存未完成，内容已暂存本机")
        }
        return json?["data"] as? [String: Any] ?? [:]
    }
    static func validate(_ session: CaptureSession) async throws {
        guard session.expiresAt > Date() else { throw CaptureFailure.message("登录已过期，请回 WAO 登录") }
        let actual = try await request("/api/auth/session", session: session)
        guard actual["accountId"] as? String == session.accountId,
              (actual["member"] as? [String: Any])?["id"] as? String == session.memberId else {
            throw CaptureFailure.message("登录成员已变化，导入保留给原成员")
        }
    }
    static func requireCurrent(_ original: CaptureSession) throws {
        guard let current = try session(), current.accountId == original.accountId,
              current.memberId == original.memberId, current.cookie == original.cookie else {
            throw CaptureFailure.message("登录已退出或成员已变化，导入仍保留在本机")
        }
    }
    static func markdown(_ item: CaptureItem) -> String { CaptureFormat.markdown(item) }
    static func submit(_ original: CaptureItem) async throws -> [String: Any] {
        guard let session = try session(), original.accountId == session.accountId, original.memberId == session.memberId else {
            throw CaptureFailure.message("请打开 WAO 登录并确认导入，内容仅存本机")
        }
        try await validate(session)
        try requireCurrent(session)
        var item = original
        if let image = item.imagePath, item.imageUrl == nil {
            let boundary = UUID().uuidString
            var multipart = Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"source\"\r\n\r\n小记书摘\r\n--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"excerpt.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".utf8)
            multipart.append(try Data(contentsOf: imageFile(image)))
            multipart.append(Data("\r\n--\(boundary)--\r\n".utf8))
            let uploaded = try await request("/api/images", session: session, method: "POST", data: multipart, contentType: "multipart/form-data; boundary=\(boundary)")
            guard let url = uploaded["url"] as? String, url.hasPrefix("/api/images/") else { throw CaptureFailure.message("原图上传未确认，导入保留在本机") }
            item.imageUrl = url; try save(item)
        }
        let payload: [String: Any] = ["title": item.title, "createOnly": true,
            "content": ["version": 1, "document": NSNull(), "markdown": markdown(item)]]
        try requireCurrent(session)
        let saved = try await request("/api/docs/notes/" + item.id, session: session, method: "PUT", data: JSONSerialization.data(withJSONObject: payload))
        guard saved["id"] as? String == item.id, saved["author"] as? String == session.memberId else { throw CaptureFailure.message("保存结果未确认，导入保留在本机") }
        let content = saved["content"] as? [String: Any]
        guard content?["markdown"] as? String == markdown(item) else {
            throw CaptureFailure.message("云端已有这篇小记且正文发生了变化。请到小记列表核对；本机输入未删除，也未覆盖云端")
        }
        try requireCurrent(session)
        let currentItem = try read(item.id)
        guard markdown(currentItem) == markdown(item), currentItem.title == item.title else {
            throw CaptureFailure.message("上传期间本机输入发生变化，新输入仍留在本机，请核对云端后继续")
        }
        try acknowledge(item.id, account: session.accountId, member: session.memberId)
        return saved
    }
}
