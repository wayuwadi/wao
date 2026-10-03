/** 仅更新历史小记错误中的旧操作指引，诊断前缀及原始持久记录保持不变。 */
export function noteAiRecoveryMessage(message: string | undefined): string | undefined {
  return message
    ?.replace(/请检查本地 Runner 后重试/g, '请检查本机 Codex 登录、网络和服务状态后重试')
    .replace(/请检查 Runner 后重试/g, '请检查本机 Codex 登录、网络和服务状态后重试')
    .replace(/请检查 Runner 网络后重试/g, '请检查本机 Codex 所在电脑的网络后重试')
    .replace(/请查看 Runner 状态后重试/g, '请检查本机 Codex 登录和服务状态后重试')
    .replace(/请更新 Runner 后重试/g, '请更新本机 Codex 并确认配图能力后重试')
}
