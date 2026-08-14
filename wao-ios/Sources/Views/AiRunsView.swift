import SwiftUI

@MainActor
final class AiRunsStore: ObservableObject {
    @Published private(set) var runs: [AiRun] = []
    @Published private(set) var projects: [Project] = []
    @Published private(set) var isLoading = false
    @Published private(set) var isLaunching = false
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
            async let runRequest = api.get("/api/ai/runs", as: [AiRun].self)
            async let projectRequest = api.get("/api/projects", as: [Project].self)
            (runs, projects) = try await (runRequest, projectRequest)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func launch(projectId: String, goal: String) async -> Bool {
        let trimmed = goal.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            errorMessage = "请输入要交给 AI 的目标"
            return false
        }
        isLaunching = true
        errorMessage = nil
        defer { isLaunching = false }
        do {
            let run = try await api.send(
                "/api/ai/goals",
                method: .post,
                body: LaunchGoalRequest(projectId: projectId, goal: trimmed),
                as: AiRun.self
            )
            runs.insert(run, at: 0)
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }
}

struct AiRunsView: View {
    @StateObject private var store = AiRunsStore()
    @State private var showingLauncher = false

    var body: some View {
        Group {
            if store.isLoading && store.runs.isEmpty {
                ProgressView("正在加载 AI 运行…")
            } else if let message = store.errorMessage, store.runs.isEmpty {
                ContentUnavailableView {
                    Label("运行记录加载失败", systemImage: "wifi.exclamationmark")
                } description: {
                    Text(message)
                } actions: {
                    Button("重试") { Task { await store.load() } }
                }
            } else if store.runs.isEmpty {
                ContentUnavailableView("暂无 AI 运行", systemImage: "sparkles", description: Text("可以从右上角派发一个真实目标。"))
            } else {
                List(store.runs) { run in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(alignment: .firstTextBaseline) {
                            Text(run.taskTitle).font(.headline)
                            Spacer()
                            StatusText(value: run.status)
                        }
                        Text(run.projectName).font(.subheadline).foregroundStyle(.secondary)
                        if let progress = run.latestProgress, !progress.isEmpty {
                            Text(progress).font(.caption).lineLimit(3)
                        } else if let result = run.resultSummary, !result.isEmpty {
                            Text(result).font(.caption).lineLimit(3)
                        }
                        HStack {
                            if let runner = run.runnerName { Text(runner) }
                            Spacer()
                            Text(run.createdAt)
                        }
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                }
                .refreshable { await store.load() }
            }
        }
        .navigationTitle("AI 运行")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    showingLauncher = true
                } label: {
                    Label("派发", systemImage: "plus")
                }
                .disabled(store.projects.isEmpty)
            }
        }
        .task { await store.load() }
        .sheet(isPresented: $showingLauncher) {
            GoalLauncherView(store: store, isPresented: $showingLauncher)
        }
        .safeAreaInset(edge: .bottom) {
            if let message = store.errorMessage, !store.runs.isEmpty {
                ErrorBanner(message: message)
            }
        }
    }
}

private struct GoalLauncherView: View {
    @ObservedObject var store: AiRunsStore
    @Binding var isPresented: Bool
    @State private var projectId = ""
    @State private var goal = ""

    var body: some View {
        NavigationStack {
            Form {
                Picker("项目", selection: $projectId) {
                    ForEach(store.projects) { project in
                        Text(project.name).tag(project.id)
                    }
                }
                TextEditor(text: $goal)
                    .frame(minHeight: 180)
                    .overlay(alignment: .topLeading) {
                        if goal.isEmpty {
                            Text("写清目标、约束和验收标准…")
                                .foregroundStyle(.tertiary)
                                .padding(.top, 8)
                                .padding(.leading, 5)
                                .allowsHitTesting(false)
                        }
                    }
                if let message = store.errorMessage {
                    Label(message, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                }
            }
            .navigationTitle("派发 AI 目标")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { isPresented = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(store.isLaunching ? "派发中…" : "派发") {
                        Task {
                            if await store.launch(projectId: projectId, goal: goal) {
                                isPresented = false
                            }
                        }
                    }
                    .disabled(store.isLaunching || projectId.isEmpty || goal.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .onAppear {
                if projectId.isEmpty { projectId = store.projects.first?.id ?? "" }
            }
        }
    }
}
