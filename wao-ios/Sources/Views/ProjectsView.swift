import SwiftUI

@MainActor
final class ProjectsStore: ObservableObject {
    @Published private(set) var projects: [Project] = []
    @Published private(set) var tasks: [TaskItem] = []
    @Published private(set) var isLoading = false
    @Published private(set) var savingTaskIds: Set<String> = []
    @Published var errorMessage: String?

    private let api: APIClient

    init(api: APIClient = .shared) {
        self.api = api
    }

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            async let projectRequest = api.get("/api/projects", as: [Project].self)
            async let taskRequest = api.get("/api/tasks", as: [TaskItem].self)
            (projects, tasks) = try await (projectRequest, taskRequest)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func toggleCompletion(_ task: TaskItem) async {
        guard !savingTaskIds.contains(task.id) else { return }
        savingTaskIds.insert(task.id)
        errorMessage = nil
        defer { savingTaskIds.remove(task.id) }
        do {
            let status = task.status == "已完成" ? "待办" : "已完成"
            let updated = try await api.send(
                "/api/tasks/\(task.id)",
                method: .patch,
                body: TaskStatusPatch(status: status),
                as: TaskItem.self
            )
            if let index = tasks.firstIndex(where: { $0.id == updated.id }) {
                tasks[index] = updated
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

struct ProjectsView: View {
    @StateObject private var store = ProjectsStore()

    var body: some View {
        Group {
            if store.isLoading && store.projects.isEmpty {
                ProgressView("正在加载项目…")
            } else if let message = store.errorMessage, store.projects.isEmpty {
                ContentUnavailableView {
                    Label("项目加载失败", systemImage: "wifi.exclamationmark")
                } description: {
                    Text(message)
                } actions: {
                    Button("重试") { Task { await store.load() } }
                }
            } else if store.projects.isEmpty {
                ContentUnavailableView("暂无项目", systemImage: "square.stack.3d.up")
            } else {
                List(store.projects) { project in
                    NavigationLink {
                        ProjectTasksView(project: project, store: store)
                    } label: {
                        VStack(alignment: .leading, spacing: 7) {
                            HStack {
                                Text(project.name).font(.headline)
                                Spacer()
                                Text(project.status)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            ProgressView(value: Double(project.progress), total: 100)
                            if !project.goal.isEmpty {
                                Text(project.goal)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(2)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
                .refreshable { await store.load() }
            }
        }
        .navigationTitle("项目")
        .task { await store.load() }
        .safeAreaInset(edge: .bottom) {
            if let message = store.errorMessage, !store.projects.isEmpty {
                ErrorBanner(message: message)
            }
        }
    }
}

private struct ProjectTasksView: View {
    let project: Project
    @ObservedObject var store: ProjectsStore

    private var tasks: [TaskItem] {
        store.tasks.filter { $0.project == project.id }
    }

    var body: some View {
        Group {
            if tasks.isEmpty {
                ContentUnavailableView("暂无任务", systemImage: "checklist")
            } else {
                List(tasks) { task in
                    Button {
                        Task { await store.toggleCompletion(task) }
                    } label: {
                        HStack(alignment: .top, spacing: 12) {
                            if store.savingTaskIds.contains(task.id) {
                                ProgressView().controlSize(.small)
                            } else {
                                Image(systemName: task.status == "已完成" ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(task.status == "已完成" ? Color.green : Color.secondary)
                            }
                            VStack(alignment: .leading, spacing: 5) {
                                Text(task.title)
                                    .foregroundStyle(.primary)
                                    .strikethrough(task.status == "已完成")
                                HStack {
                                    Text(task.pr)
                                    Text(task.status)
                                    if !task.due.isEmpty { Text("截止 \(task.due)") }
                                }
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .disabled(store.savingTaskIds.contains(task.id))
                }
            }
        }
        .navigationTitle(project.name)
        .navigationBarTitleDisplayMode(.inline)
    }
}
