import SwiftUI

struct ErrorBanner: View {
    let message: String

    var body: some View {
        Label(message, systemImage: "exclamationmark.triangle.fill")
            .font(.caption)
            .foregroundStyle(.red)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.regularMaterial)
    }
}

struct StatusText: View {
    let value: String

    var body: some View {
        Text(displayValue)
            .font(.caption.bold())
            .foregroundStyle(color)
    }

    private var displayValue: String {
        switch value {
        case "queued": return "排队中"
        case "claimed": return "已领取"
        case "running": return "运行中"
        case "succeeded": return "已成功"
        case "failed": return "失败"
        case "cancelled": return "已取消"
        default: return value
        }
    }

    private var color: Color {
        switch value {
        case "succeeded", "正常": return .green
        case "failed", "待修复": return .red
        case "running", "claimed": return .blue
        default: return .secondary
        }
    }
}
