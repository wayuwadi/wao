import Foundation

struct CaptureItem: Codable {
    var id: String
    var title = ""
    var excerpt = ""
    var thoughts = ""
    var sourceUrl = ""
    var sourceTitle = ""
    var accountId: String?
    var memberId: String?
    var imagePath: String?
    var imageUrl: String?
}

/// 无网络、存储和模型依赖的格式边界，供 App 与扩展使用同一份实现。
enum CaptureFormat {
    static func newID() -> String { UUID().uuidString.lowercased().replacingOccurrences(of: "-", with: "") }
    static func escape(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "([\\\\`*_{}\\[\\]()#+.!|~-])", with: "\\\\$1", options: .regularExpression)
    }
    static func markdown(_ item: CaptureItem) -> String {
        let source = [item.sourceTitle, item.sourceUrl].filter { !$0.isEmpty }.joined(separator: " · ")
        let quote = item.excerpt.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n").map { "> " + escape($0) }.joined(separator: "\n")
        let image = item.imageUrl.map { "![书摘原图](\($0))\n\n" } ?? ""
        return image + "## 书摘\n\n" + quote + "\n\n" + (source.isEmpty ? "" : "来源：" + escape(source) + "\n\n") + "## 我的感想\n\n" + item.thoughts
    }
}
