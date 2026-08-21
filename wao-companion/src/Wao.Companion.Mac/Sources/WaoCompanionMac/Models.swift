import Foundation

struct ApiEnvelope<T: Decodable>: Decodable {
    let code: Int
    let message: String?
    let data: T?
}

struct CreatePairingRequest: Encodable {
    let deviceId: String
    let deviceName: String
    let platform: String
}

struct PollPairingRequest: Encodable {
    let pollSecret: String
}

struct PairingCreated: Decodable {
    let pairingId: String
    let userCode: String
    let pollSecret: String
    let verificationUrl: String
    let expiresAt: String
}

struct PairingPoll: Decodable {
    let status: String
    let deviceId: String?
    let deviceToken: String?
    let expiresAt: String
}

struct CompanionActivity: Codable, Identifiable, Equatable {
    let id: String
    let status: String
    let title: String
    let projectName: String
    let message: String
    let occurredAt: String?
    let deepLink: String
    let unread: Bool
}

struct CompanionSnapshot: Codable, Equatable {
    let status: String
    let unreadCount: Int
    let serverTime: String
    let activities: [CompanionActivity]
}

enum CompanionState {
    static func aggregate(_ activities: [CompanionActivity]) -> String {
        for status in ["NEEDS_INPUT", "BLOCKED", "READY"] {
            if activities.contains(where: { $0.unread && $0.status == status }) { return status }
        }
        return activities.contains(where: { $0.status == "RUNNING" }) ? "RUNNING" : "IDLE"
    }

    static func frames(for status: String) -> [Int] {
        switch status {
        case "RUNNING": return [4, 5]
        case "NEEDS_INPUT": return [11, 0]
        case "READY": return [10, 1]
        case "BLOCKED": return [8, 0]
        default: return [0, 1]
        }
    }

    static func statusLabel(_ status: String) -> String {
        switch status {
        case "RUNNING": return "正在工作"
        case "NEEDS_INPUT": return "需要你的确认"
        case "READY": return "已经完成"
        case "BLOCKED": return "遇到阻塞"
        default: return "空闲陪伴"
        }
    }
}

enum CompanionError: LocalizedError {
    case api(String)
    case unauthorized
    case emptyData

    var errorDescription: String? {
        switch self {
        case .api(let message): return message
        case .unauthorized: return "设备已被撤销，请重新配对。"
        case .emptyData: return "WAO 返回了空数据。"
        }
    }
}
