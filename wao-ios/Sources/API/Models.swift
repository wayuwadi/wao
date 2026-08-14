import Foundation

struct Member: Codable, Equatable {
    let id: String
    let name: String
    let initial: String
    let color: String
    let role: String
}

struct AuthSession: Codable, Equatable {
    let accountId: String
    let member: Member
}

struct EmailLoginRequest: Encodable {
    let email: String
    let password: String
}

struct Project: Codable, Identifiable, Equatable {
    let id: String
    let name: String
    let status: String
    let progress: Int
    let goal: String
}

struct TaskItem: Codable, Identifiable, Equatable {
    let id: String
    let title: String
    let project: String
    let status: String
    let pr: String
    let due: String
}

struct TaskStatusPatch: Encodable {
    let status: String
}

struct AiRun: Codable, Identifiable, Equatable {
    let id: String
    let taskTitle: String
    let projectName: String
    let status: String
    let runnerName: String?
    let resultSummary: String?
    let latestProgress: String?
    let createdAt: String
}

struct LaunchGoalRequest: Encodable {
    let projectId: String
    let goal: String
}

struct AutomationJob: Codable, Identifiable, Equatable {
    let id: String
    let name: String
    let schedule: String
    let status: String
    let last: String
    let desc: String
    let handler: String?
}
