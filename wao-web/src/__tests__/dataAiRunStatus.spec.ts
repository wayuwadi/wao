import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import type { AiRun } from '@/types'

function run(status: AiRun['status'], createdAt = '2026-07-29T09:00:00'): AiRun {
  return {
    id: `ar-${status}`, taskId: 't1', taskTitle: '任务', subId: null, subTitle: null,
    projectId: 'p1', projectName: 'WAO', engine: 'codex', status,
    runnerId: null, runnerName: null, requestedRunnerId: null, branchName: null, retryOfRunId: null,
    resultSummary: null, reportUrl: null, latestProgress: null, progressUpdatedAt: null, progress: [],
    usage: {
      model: null, models: null, inputTokens: 0, outputTokens: 0, cachedInputTokens: 0,
      totalTokens: 0, costUsd: null, recorded: false,
    },
    sessionId: null, exitCode: null, createdAt, startedAt: null, finishedAt: null,
  }
}

describe('AI Run 状态投影', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('活动运行优先，完成后及时进入待验证且不覆盖本地正文', () => {
    const data = useDataStore()
    const task = data.taskById('t1')!
    task.desc = '尚未落库的本地描述'

    data.applyAiRunStatuses([run('succeeded'), run('running', '2026-07-29T08:59:00')])
    expect(task.status).toBe('AI 进行中')
    expect(task.desc).toBe('尚未落库的本地描述')

    data.applyAiRunStatuses([run('succeeded')])
    expect(task.status).toBe('待验证')
    expect(task.desc).toBe('尚未落库的本地描述')
  })

  it('失败与暂停恢复到进行中', () => {
    const data = useDataStore()
    data.applyAiRunStatuses([run('failed')])
    expect(data.taskById('t1')!.status).toBe('进行中')
    data.applyAiRunStatuses([run('cancelled')])
    expect(data.taskById('t1')!.status).toBe('进行中')
  })

  it('打开待验证任务时不被历史失败或暂停记录降回进行中', () => {
    const data = useDataStore()
    const task = data.taskById('t1')!
    task.status = '待验证'

    data.applyAiRunStatuses([run('failed')])
    expect(task.status).toBe('待验证')

    data.applyAiRunStatuses([run('cancelled')])
    expect(task.status).toBe('待验证')

    data.applyAiRunStatuses([run('running')])
    expect(task.status).toBe('AI 进行中')
  })

  it('不覆盖人工验收后的已完成状态', () => {
    const data = useDataStore()
    const task = data.taskById('t1')!
    task.status = '已完成'
    task.completedAt = '2026-07-29T10:00:00'

    data.applyAiRunStatuses([run('succeeded')])

    expect(task.status).toBe('已完成')
    expect(task.completedAt).toBe('2026-07-29T10:00:00')
  })
})
