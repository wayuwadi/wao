import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import AiRunArchive from '@/components/ai/AiRunArchive.vue'
import type { AiRun } from '@/types'

function goalRun(): AiRun {
  return {
    id: 'ar-goal', taskId: 't1', taskTitle: '接入目标模式', subId: null, subTitle: null,
    projectId: 'p1', projectName: 'WAO', engine: 'codex', requestedModel: 'gpt-5.6-sol',
    reasoningEffort: 'high', planMode: false, goalMode: true,
    goalObjective: '完成目标模式，直到部署成功且线上验收通过', goalStatus: 'active',
    goalTokensUsed: 12_345, goalTimeUsedSeconds: 67, status: 'running',
    runnerId: 'rn1', runnerName: 'Linux 主节点', requestedRunnerId: 'rn1', branchName: 'ai/wao-ar-goal',
    retryOfRunId: null, resultSummary: null, reportUrl: null, latestProgress: '目标持续执行中',
    progressUpdatedAt: '2026-08-31T12:00:00', progress: [],
    usage: { model: null, models: null, inputTokens: 0, outputTokens: 0,
      cachedInputTokens: 0, totalTokens: 0, costUsd: null, recorded: false },
    sessionId: 'thread-goal-1', exitCode: null, createdAt: '2026-08-31T12:00:00',
    startedAt: '2026-08-31T12:00:00', finishedAt: null,
  }
}

describe('AI Run 目标模式档案', () => {
  it('展示原生目标的目标、状态、Token 与持续时间', () => {
    const wrapper = mount(AiRunArchive, {
      props: { runs: [goalRun()], loaded: true },
      global: {
        plugins: [createPinia()],
        stubs: {
          AiRunProgressTimeline: true,
          AiRunDeliverySteps: true,
          AiRunMemoryReferences: true,
          AiRunSkillCapabilities: true,
        },
      },
    })

    const card = wrapper.get('.ai-run-goal')
    expect(wrapper.get('.ai-run-parameters').text()).toContain('目标模式')
    expect(card.text()).toContain('持续执行中')
    expect(card.text()).toContain('完成目标模式，直到部署成功且线上验收通过')
    expect(card.text()).toContain('12K Token')
    expect(card.text()).toContain('1 分 7 秒')
    wrapper.unmount()
  })
})
