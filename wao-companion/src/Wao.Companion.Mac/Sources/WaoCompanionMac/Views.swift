import AppKit
import SwiftUI

struct PetView: View {
    @ObservedObject var store: CompanionStore

    var body: some View {
        VStack(spacing: 3) {
            VStack(spacing: 2) {
                Text(store.message)
                    .font(.system(size: 11.5))
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
                    .foregroundStyle(Color(red: 0.20, green: 0.18, blue: 0.28))
                Text(CompanionState.statusLabel(store.snapshot.status))
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .frame(maxWidth: 188)
            .background(.white.opacity(0.96), in: RoundedRectangle(cornerRadius: 13))
            .overlay(RoundedRectangle(cornerRadius: 13).stroke(Color.purple.opacity(0.20)))
            .shadow(color: .black.opacity(0.12), radius: 8, y: 2)

            ZStack(alignment: .topTrailing) {
                Image(nsImage: SpriteFrames.shared.image(store.frame))
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 132, height: 132)
                if store.snapshot.unreadCount > 0 {
                    Text(store.snapshot.unreadCount > 99 ? "99+" : "\(store.snapshot.unreadCount)")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 6)
                        .frame(minWidth: 24, minHeight: 24)
                        .background(Color(red: 0.91, green: 0.35, blue: 0.40), in: Capsule())
                }
            }
            .frame(width: 158, height: 140)
        }
        .frame(width: 205, height: 235)
        .contentShape(Rectangle())
        .allowsHitTesting(false)
    }
}

struct ActivityView: View {
    @ObservedObject var store: CompanionStore
    let dismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("WAO 活动").font(.system(size: 18, weight: .semibold))
                    Text(store.snapshot.unreadCount > 0 ? "\(store.snapshot.unreadCount) 条活动需要查看" : "没有需要处理的活动")
                        .font(.system(size: 11)).foregroundStyle(.secondary)
                }
                Spacer()
                Button("全部已读") { Task { await store.markAllRead() } }
                    .disabled(store.snapshot.unreadCount == 0)
            }
            ScrollView {
                LazyVStack(spacing: 8) {
                    ForEach(store.snapshot.activities) { activity in
                        Button {
                            Task { await store.markRead(activity); dismiss() }
                        } label: {
                            HStack(spacing: 10) {
                                RoundedRectangle(cornerRadius: 3)
                                    .fill(accent(activity.status)).frame(width: 7)
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack {
                                        Text(activity.title).font(.system(size: 12, weight: .semibold)).lineLimit(1)
                                        Spacer()
                                        Text(label(activity.status)).font(.system(size: 10, weight: .semibold))
                                            .foregroundStyle(accent(activity.status))
                                    }
                                    Text(activity.projectName).font(.system(size: 10.5)).foregroundStyle(.secondary)
                                    Text(activity.message).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(2)
                                }
                            }
                            .padding(11)
                            .background(Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 11))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            Text("选择活动后会在浏览器中打开对应 WAO 任务")
                .font(.system(size: 10)).foregroundStyle(.secondary).frame(maxWidth: .infinity)
        }
        .padding(16)
        .frame(width: 380, height: 440)
        .background(.regularMaterial)
    }

    private func accent(_ status: String) -> Color {
        switch status {
        case "NEEDS_INPUT": return .orange
        case "BLOCKED": return .red
        case "READY": return .green
        default: return .purple
        }
    }

    private func label(_ status: String) -> String {
        switch status {
        case "NEEDS_INPUT": return "需确认"
        case "BLOCKED": return "阻塞"
        case "READY": return "已完成"
        default: return "运行中"
        }
    }
}

struct PairingView: View {
    @ObservedObject var store: CompanionStore

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 12) {
                Text("W").font(.system(size: 24, weight: .bold)).foregroundStyle(.white)
                    .frame(width: 58, height: 58).background(Color.purple, in: RoundedRectangle(cornerRadius: 16))
                VStack(alignment: .leading, spacing: 4) {
                    Text("连接 WAO Companion").font(.system(size: 22, weight: .semibold))
                    Text("桌宠只读取你自己的 AI Run 状态，不获得 Runner 或密钥库权限。")
                        .font(.system(size: 11)).foregroundStyle(.secondary)
                }
            }
            VStack(spacing: 14) {
                Text(store.pairingCode).font(.system(size: 30, weight: .bold, design: .monospaced)).foregroundStyle(.purple)
                Text(store.pairingMessage).font(.system(size: 12)).foregroundStyle(.secondary)
                Button("在 WAO 网页确认") {
                    if let url = store.verificationURL { NSWorkspace.shared.open(url) }
                }.disabled(store.verificationURL == nil)
            }
            .frame(maxWidth: .infinity).padding(28)
            .background(Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 16))
            HStack {
                Text("设备令牌只保存在 macOS Keychain。")
                    .font(.system(size: 10.5)).foregroundStyle(.secondary)
                Spacer()
                Button("重新生成") { store.beginPairing() }
            }
        }
        .padding(28)
        .frame(width: 520, height: 390)
    }
}
