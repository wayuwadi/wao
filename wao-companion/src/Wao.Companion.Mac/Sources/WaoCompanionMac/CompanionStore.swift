import AppKit
import Combine
import Foundation

@MainActor
final class CompanionStore: ObservableObject {
    @Published private(set) var snapshot = CompanionSnapshot(status: "IDLE", unreadCount: 0,
        serverTime: "", activities: [])
    @Published private(set) var offlineMessage: String?
    @Published private(set) var pairingCode = "••••-••••"
    @Published private(set) var pairingMessage = "正在创建配对码…"
    @Published private(set) var verificationURL: URL?
    @Published private(set) var frame = 0
    @Published var isDragging = false
    @Published var isHidden = false

    private let api = CompanionAPI()
    private var token: String?
    private var activityTask: Task<Void, Never>?
    private var pairingTask: Task<Void, Never>?
    private var animationTimer: Timer?
    private var frameCursor = 0
    private var previewMode = false

    var message: String {
        if let offlineMessage { return offlineMessage }
        let focus = snapshot.activities.first { $0.status == snapshot.status && ($0.unread || $0.status == "RUNNING") }
        return focus.map { $0.message.isEmpty ? $0.title : "\($0.title)：\($0.message)" } ?? "今天也一起把事情做好。"
    }

    func start(preview: Bool) {
        previewMode = preview
        startAnimation()
        if preview {
            snapshot = Self.previewSnapshot
            return
        }
        token = SecureTokenStore.load()
        if token == nil { beginPairing() }
        else { startActivityLoop() }
    }

    func beginPairing() {
        activityTask?.cancel()
        token = nil
        SecureTokenStore.clear()
        pairingTask?.cancel()
        pairingTask = Task {
            do {
                pairingMessage = "正在创建配对码…"
                let deviceId = UserDefaults.standard.string(forKey: "deviceId") ?? UUID().uuidString.lowercased()
                UserDefaults.standard.set(deviceId, forKey: "deviceId")
                let pairing = try await api.createPairing(.init(deviceId: deviceId,
                    deviceName: Host.current().localizedName ?? "Mac", platform: "macos-universal"))
                pairingCode = pairing.userCode
                verificationURL = URL(string: pairing.verificationUrl)
                pairingMessage = "请在 WAO 网页确认这台 Mac"
                if let verificationURL { NSWorkspace.shared.open(verificationURL) }
                while !Task.isCancelled {
                    try await Task.sleep(for: .seconds(2))
                    let result = try await api.pollPairing(id: pairing.pairingId, secret: pairing.pollSecret)
                    if result.status == "EXPIRED" {
                        pairingMessage = "配对码已过期，请重新生成。"
                        return
                    }
                    guard result.status == "PAIRED", let issued = result.deviceToken else { continue }
                    try SecureTokenStore.save(issued)
                    token = issued
                    pairingMessage = "配对成功"
                    NotificationCenter.default.post(name: .companionPaired, object: nil)
                    startActivityLoop()
                    return
                }
            } catch is CancellationError {
            } catch {
                pairingMessage = error.localizedDescription
            }
        }
    }

    func refresh() async {
        guard let token else { return }
        do {
            snapshot = try await api.activities(token: token)
            offlineMessage = nil
        } catch CompanionError.unauthorized {
            self.token = nil
            SecureTokenStore.clear()
            activityTask?.cancel()
            NotificationCenter.default.post(name: .companionNeedsPairing, object: nil)
        } catch {
            offlineMessage = "WAO 暂时离线 · 正在重试"
        }
    }

    func markRead(_ activity: CompanionActivity) async {
        guard let token else { return }
        try? await api.markRead(id: activity.id, token: token)
        if let url = URL(string: activity.deepLink) { NSWorkspace.shared.open(url) }
        await refresh()
    }

    func markAllRead() async {
        guard let token else { return }
        try? await api.markAllRead(token: token)
        await refresh()
    }

    func setDragging(_ dragging: Bool) {
        isDragging = dragging
        frameCursor = 0
        frame = dragging ? 6 : CompanionState.frames(for: snapshot.status)[0]
    }

    func playLanding() {
        frame = 8
        Task {
            try? await Task.sleep(for: .milliseconds(180))
            frame = 9
            try? await Task.sleep(for: .milliseconds(220))
            frame = CompanionState.frames(for: snapshot.status)[0]
        }
    }

    private func startActivityLoop() {
        activityTask?.cancel()
        activityTask = Task {
            while !Task.isCancelled, token != nil {
                await refresh()
                try? await Task.sleep(for: .seconds(isHidden ? 30 : 5))
            }
        }
    }

    private func startAnimation() {
        animationTimer?.invalidate()
        if NSWorkspace.shared.accessibilityDisplayShouldReduceMotion {
            frame = CompanionState.frames(for: snapshot.status)[0]
            return
        }
        animationTimer = Timer.scheduledTimer(withTimeInterval: 0.38, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                let frames = self.isDragging ? [6, 7] : CompanionState.frames(for: self.snapshot.status)
                self.frameCursor = (self.frameCursor + 1) % frames.count
                self.frame = frames[self.frameCursor]
            }
        }
    }

    static let previewSnapshot = CompanionSnapshot(status: "READY", unreadCount: 2,
        serverTime: ISO8601DateFormatter().string(from: Date()), activities: [
            .init(id: "preview-ready", status: "READY", title: "桌面宠物视觉优化", projectName: "WAO Companion",
                  message: "像素精灵和慢动作已经准备好。", occurredAt: nil,
                  deepLink: "https://wao.wayuwa.cn", unread: true),
            .init(id: "preview-running", status: "RUNNING", title: "准备 macOS 客户端", projectName: "跨平台",
                  message: "正在检查菜单栏、Keychain 和透明浮窗。", occurredAt: nil,
                  deepLink: "https://wao.wayuwa.cn", unread: false),
        ])
}

extension Notification.Name {
    static let companionPaired = Notification.Name("companionPaired")
    static let companionNeedsPairing = Notification.Name("companionNeedsPairing")
}
