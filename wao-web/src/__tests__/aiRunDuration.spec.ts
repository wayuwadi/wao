import { describe, expect, it } from 'vitest'
import { aiExecutionDuration, formatDuration, totalRunDuration } from '@/composables/aiRunDuration'
import type { AiRun } from '@/types'

const run = (overrides: Partial<AiRun>): AiRun => ({
  id: 'ar1', taskId: 't1', taskTitle: '计时', subId: null, subTitle: null,
  projectId: 'p1', projectName: 'WAO', engine: 'codex', status: 'succeeded',
  runnerId: 'rn1', runnerName: 'Linux', requestedRunnerId: null, branchName: null,
  retryOfRunId: null, resultSummary: null, reportUrl: null, latestProgress: null,
  progressUpdatedAt: null, progress: [], usage: { model: null, models: null,
    inputTokens: 0, outputTokens: 0, cachedInputTokens: 0, totalTokens: 0,
    costUsd: null, recorded: false }, sessionId: null, exitCode: 0,
  createdAt: '2026-08-31T08:59:00+08:00', startedAt: null, finishedAt: null,
  ...overrides,
})

describe('AI Run 真实耗时', () => {
  it('以首次领取而非入队时间计算总耗时', () => {
    expect(totalRunDuration(run({
      claimedAt: '2026-08-31T09:00:00+08:00',
      finishedAt: '2026-08-31T09:01:05+08:00',
    }))).toBe('1 分 5 秒')
  })

  it('活动运行使用当前时刻并兼容旧 API 的 startedAt', () => {
    expect(totalRunDuration(run({ status: 'running', startedAt: '2026-08-31T09:00:00Z' }),
      new Date('2026-08-31T09:02:03Z'))).toBe('2 分 3 秒')
  })

  it('实测值保留秒级差异，缺值不估算', () => {
    expect(formatDuration(3_723_000)).toBe('1 小时 2 分')
    expect(aiExecutionDuration(run({ aiExecutionDurationMs: 754_000 }))).toBe('12 分 34 秒')
    expect(aiExecutionDuration(run({}))).toBe('旧记录未记录')
  })
})
