import { describe, expect, it } from 'vitest'
import { noteAiRecoveryMessage } from '@/utils/noteAiRecoveryMessage'

describe('小记历史错误恢复指引', () => {
  it('保留退出码和能力缺失原因，只更新配图恢复动作', () => {
    expect(noteAiRecoveryMessage('Codex 配图执行失败（退出码 17）：当前 Codex 会话没有可用的配图工具，请更新 Runner 后重试'))
      .toBe('Codex 配图执行失败（退出码 17）：当前 Codex 会话没有可用的配图工具，请更新本机 Codex 并确认配图能力后重试')
  })
  it('保留历史执行超时和网络原因', () => {
    expect(noteAiRecoveryMessage('只读 AI 任务执行超时，请检查本地 Runner 后重试')).toContain('执行超时')
    expect(noteAiRecoveryMessage('上游网络连接失败，请检查 Runner 网络后重试'))
      .toBe('上游网络连接失败，请检查本机 Codex 所在电脑的网络后重试')
  })
  it('不改写当前错误和诊断中的普通 Runner 名称', () => {
    const diagnostic = 'Codex 登录失效，原文已保留；日志文件 Runner.log'
    expect(noteAiRecoveryMessage(diagnostic)).toBe(diagnostic)
    expect(noteAiRecoveryMessage(undefined)).toBeUndefined()
  })
})
