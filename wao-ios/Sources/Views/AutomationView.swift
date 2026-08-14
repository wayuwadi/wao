import SwiftUI

@MainActor
final class AutomationStore: ObservableObject {
    @Published private(set) var jobs: [AutomationJob] = []
    @Published private(set) var isLoading = false
    @Published private(set) var savingJobIds: Set<String> = []
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
            jobs = try await api.get("/api/jobs", as: [AutomationJob].self)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func perform(_ action: String, on job: AutomationJob) async {
        guard !savingJobIds.contains(job.id) else { return }
        savingJobIds.insert(job.id)
        errorMessage = nil
        defer { savingJobIds.remove(job.id) }
        do {
            let updated = try await api.send("/api/jobs/\(job.id)/\(action)", method: .post, as: AutomationJob.self)
            if let index = jobs.firstIndex(where: { $0.id == updated.id }) {
                jobs[index] = updated
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

struct AutomationView: View {
    @StateObject private var store = AutomationStore()

    var body: some View {
        Group {
            if store.isLoading && store.jobs.isEmpty {
                ProgressView("正在加载自动化…")
            } else if let message = store.errorMessage, store.jobs.isEmpty {
                ContentUnavailableView {
                    Label("自动化加载失败", systemImage: "wifi.exclamationmark")
                } description: {
                    Text(message)
                } actions: {
                    Button("重试") { Task { await store.load() } }
                }
            } else if store.jobs.isEmpty {
                ContentUnavailableView("暂无自动化", systemImage: "bolt.badge.clock")
            } else {
                List(store.jobs) { job in
                    VStack(alignment: .leading, spacing: 7) {
                        HStack {
                            Text(job.name).font(.headline)
                            Spacer()
                            StatusText(value: job.status)
                        }
                        if !job.desc.isEmpty {
                            Text(job.desc).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        }
                        HStack {
                            Text(job.schedule)
                            Spacer()
                            Text(job.last)
                        }
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        HStack {
                            Button("立即运行") { Task { await store.perform("run", on: job) } }
                                .buttonStyle(.borderedProminent)
                            if job.status == "已暂停" {
                                Button("恢复") { Task { await store.perform("resume", on: job) } }
                            } else {
                                Button("暂停") { Task { await store.perform("pause", on: job) } }
                            }
                            if job.status == "待修复" {
                                Button("重新授权") { Task { await store.perform("reauth", on: job) } }
                            }
                            if store.savingJobIds.contains(job.id) { ProgressView().controlSize(.small) }
                        }
                        .disabled(store.savingJobIds.contains(job.id))
                    }
                    .padding(.vertical, 5)
                }
                .refreshable { await store.load() }
            }
        }
        .navigationTitle("自动化")
        .task { await store.load() }
        .safeAreaInset(edge: .bottom) {
            if let message = store.errorMessage, !store.jobs.isEmpty {
                ErrorBanner(message: message)
            }
        }
    }
}
