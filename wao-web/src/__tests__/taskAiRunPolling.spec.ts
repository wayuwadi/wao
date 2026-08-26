import { describe, expect, it } from 'vitest'
import { nextTaskAiRunPollDelay } from '@/utils/taskAiRunPolling'

describe('任务 AI 档案轮询退避', () => {
  it('活动运行始终保持五秒反馈', () => {
    expect(nextTaskAiRunPollDelay([{ status: 'queued' }], 20)).toBe(5_000)
    expect(nextTaskAiRunPollDelay([{ status: 'running' }], 0)).toBe(5_000)
  })

  it('无活动运行时从十五秒逐级退避并封顶六十秒', () => {
    expect(nextTaskAiRunPollDelay([], 0)).toBe(15_000)
    expect(nextTaskAiRunPollDelay([{ status: 'succeeded' }], 1)).toBe(30_000)
    expect(nextTaskAiRunPollDelay([{ status: 'failed' }], 2)).toBe(60_000)
    expect(nextTaskAiRunPollDelay([{ status: 'cancelled' }], 99)).toBe(60_000)
  })

  it('失败与异常轮次回到最快空闲档', () => {
    expect(nextTaskAiRunPollDelay([], 10, true)).toBe(15_000)
    expect(nextTaskAiRunPollDelay([], -1)).toBe(15_000)
  })
})
