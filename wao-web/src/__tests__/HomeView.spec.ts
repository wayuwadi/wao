import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia } from 'pinia'
import HomeView from '@/views/HomeView.vue'
import MarkdownEditor from '@/components/ui/MarkdownEditor.vue'
import { useDataStore } from '@/stores/data'
import type { AiRunner } from '@/types'
import { fetchDailyGuidance, generateDailyGuidance, type DailyGuidance } from '@/api/dailyGuidance'
import {
  fetchAiRunnerLog,
  fetchAiRunHistory,
  fetchAiEvolution,
  fetchAiRunners,
  fetchAiWorkflows,
  launchAutonomousGoal,
  launchEvolutionSuggestion,
  refreshEvolutionSuggestions,
  removeAiRunner,
  removeAiWorkflow,
  requestRunnerScriptUpdate,
  rerunAiRun,
  saveAiEvolutionSchedule,
  type AiEvolutionOverview,
  type AiEvolutionSuggestion,
} from '@/api/ai'

vi.mock('@/api/dailyGuidance', () => ({
  fetchDailyGuidance: vi.fn<() => Promise<Record<string, unknown>>>().mockResolvedValue({
    date: '2026-08-13', status: 'ready',
    content: '## 先理解团队\n\n- 先观察协作方式，再主动贡献。\n\n## 发挥优势\n\n- 用小成果建立信任。',
    message: '已结合今天的真实任务生成', generatedAt: '2026-08-13T08:30:00',
  }),
  generateDailyGuidance: vi.fn<() => Promise<Record<string, unknown>>>().mockResolvedValue({
    date: '2026-08-13', status: 'ready', content: '## 新建议\n\n- 主动认识一位同事。',
    message: '已结合今天的真实任务生成', generatedAt: '2026-08-13T09:00:00',
  }),
}))

vi.mock('@/api/ai', () => ({
  fetchAiRuns: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([
    {
      id: 'ar1',
      taskId: 't1',
      taskTitle: '修复自动任务',
      projectId: 'p1',
      projectName: 'WAO',
      engine: 'codex',
      status: 'failed',
      runnerId: 'rn1',
      runnerName: '娃哥电脑',
      requestedRunnerId: null,
      branchName: 'ai/wao-ar1',
      resultSummary: '失败阶段：准备 Git 工作区\n错误详情：仓库地址无法克隆',
      reportUrl: '/api/pages/ai-run-ar1',
      latestProgress: null,
      progressUpdatedAt: null,
      progress: [],
      sessionId: null,
      exitCode: -1,
      createdAt: '2026-07-25T18:50:00',
      startedAt: '2026-07-25T18:50:10',
      finishedAt: '2026-07-25T18:51:00',
    },
    {
      id: 'ar2', taskId: 't2', taskTitle: '根据截图修复展示',
      previewImages: ['/api/images/img-preview-1/raw', '/api/images/img-preview-2/raw'],
      projectId: 'p1', projectName: 'WAO',
      engine: 'claude',
      status: 'running', runnerId: 'rn2', runnerName: 'Linux 主节点', requestedRunnerId: 'rn2',
      branchName: 'ai/wao-ar2',
      resultSummary: null, latestProgress: '已定位问题，正在实现实时反馈',
      progressUpdatedAt: '2026-07-25T20:18:20',
      progress: [{ id: 'ap3', kind: 'message', content: '已定位问题，正在实现实时反馈', createdAt: '2026-07-25T20:18:20' }],
      sessionId: 'sess-1', exitCode: null, createdAt: '2026-07-25T20:18:00',
      startedAt: '2026-07-25T20:18:05', finishedAt: null,
    },
  ]),
  fetchAiRunHistory: vi.fn<() => Promise<Record<string, unknown>>>().mockResolvedValue({
    items: [{
      id: 'ar-history', taskId: 't3', taskTitle: '历史运行', previewImages: [],
      projectId: 'p1', projectName: 'WAO', engine: 'codex', status: 'succeeded',
      runnerId: 'rn1', runnerName: '娃哥电脑', requestedRunnerId: null, branchName: null,
      retryOfRunId: null, resultSummary: '已经完成', reportUrl: null, latestProgress: null,
      progressUpdatedAt: null, progress: [], usage: { model: null, models: null, inputTokens: 0,
        outputTokens: 0, cachedInputTokens: 0, totalTokens: 0, costUsd: null, recorded: false },
      sessionId: null, exitCode: 0, createdAt: '2026-07-24T10:00:00',
      startedAt: '2026-07-24T10:00:00', finishedAt: '2026-07-24T10:10:00',
    }],
    total: 21, page: 1, size: 20,
  }),
  fetchAiRunners: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([
    {
      id: 'rn1', name: '娃哥电脑', platform: 'Windows 11', engines: ['codex'],
      maxConcurrency: 2, activeRuns: 0, online: false, lastSeenAt: '2026-07-25T18:00:00',
    },
    {
      id: 'rn2', name: 'Linux 主节点', platform: 'Linux 7.0', engines: ['claude'],
      maxConcurrency: 2, activeRuns: 1, online: true, lastSeenAt: '2026-07-25T20:18:20',
    },
  ]),
  fetchAiRunnerLog: vi.fn<() => Promise<Record<string, unknown>>>().mockResolvedValue({
    runnerId: 'rn1', runnerName: '娃哥电脑',
    content: '2026-07-31 13:30:00 WAO AI Runner 已启动', updatedAt: '2026-07-31T13:30:00',
  }),
  fetchAiWorkflows: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([
    {
      projectId: 'p1',
      projectName: 'Wa All in One',
      config: {
        projectId: 'p1', enabled: true, repoUrl: 'https://github.com/acme/wao.git', defaultBranch: 'main',
        workspaceDir: 'wao', defaultEngine: 'claude', preferredRunnerId: 'rn2', maxParallelRuns: 3,
      },
      configured: true,
      projectDeleted: false,
      preferredRunnerName: 'Linux 主节点',
      activeRuns: 1,
      succeededRuns: 4,
      failedRuns: 1,
      lastRunAt: '2026-07-25T20:18:00',
    },
  ]),
  fetchAiEvolution: vi.fn<() => Promise<Record<string, unknown>>>().mockResolvedValue({
    codexQuotas: [{
      key: 'wage@example.com', accountEmail: 'wage@example.com', plan: 'pro',
      credits: { hasCredits: false, unlimited: false, balance: '0' },
      limits: [
        { id: 'codex', label: '通用使用限额', windows: [{
          usedPercent: 62, windowDurationMinutes: 10080, resetsAt: 1787201290,
        }] },
        { id: 'codex_bengalfox', label: 'GPT-5.3-Codex-Spark', windows: [{
          usedPercent: 0, windowDurationMinutes: 10080, resetsAt: 1787629119,
        }] },
      ],
      runnerNames: ['娃哥电脑', 'Linux 主节点'], lastSyncedAt: '2026-08-18T11:00:00', fresh: true,
    }],
    sessionQuotas: [{
      accountId: 'aa-codex', provider: 'codex', label: 'Codex 主账号', plan: 'plus',
      usedPercent: 12, remainingPercent: 88, resetsAt: '2026-08-14T15:00:00',
      lastSyncedAt: '2026-08-13T10:00:00', status: 'SYNCED', message: '五小时会话额度已用 12%',
    }],
    weeklyQuotas: [{
      accountId: 'aa-codex', provider: 'codex', label: 'Codex 主账号', plan: 'plus',
      usedPercent: 0, remainingPercent: 100, resetsAt: '2026-08-17T10:00:00',
      lastSyncedAt: '2026-08-13T10:00:00', status: 'SYNCED', message: '七天额度已用 0%',
    }],
    suggestions: [{
      id: 'ux-friction', title: '找出并消除一个高频使用阻力',
      description: '完成一项真实体验优化', icon: 'ri-magic-line', goal: '体验走查',
    }],
    autoStatus: 'ready', autoMessage: '已满足 12 小时空闲与周剩余额度门槛。',
    lastActivityAt: '2026-08-12T08:00:00', nextEligibleAt: '2026-08-12T20:00:00',
    nextTriggerAt: '2026-08-12T20:15:00',
    lastCheckedAt: '2026-08-13T10:00:00', lastRunId: null,
    intervalHours: 12, batchSize: 1,
  }),
  createAiPairCode: vi.fn<() => Promise<{ code: string; expiresAt: string }>>()
    .mockResolvedValue({ code: 'ABCD2345KMNP', expiresAt: '2026-07-25T20:30:00' }),
  saveAiProjectConfig: vi.fn<() => Promise<Record<string, unknown>>>().mockResolvedValue({}),
  launchAutonomousGoal: vi.fn<() => Promise<Record<string, unknown>>>().mockResolvedValue({
    id: 'ar-goal', taskId: 't-goal', taskTitle: '把黄帝内经和易经结合 AI', projectId: 'p1',
    projectName: 'Wa All in One', engine: 'claude', status: 'queued', runnerId: null,
    runnerName: null, requestedRunnerId: 'rn2', branchName: 'ai/wao-ar-goal', resultSummary: null,
    latestProgress: '目标已进入队列', progressUpdatedAt: '2026-07-25T20:19:00', progress: [],
    sessionId: null, exitCode: null, createdAt: '2026-07-25T20:19:00', startedAt: null, finishedAt: null,
  }),
  launchEvolutionSuggestion: vi.fn<() => Promise<Record<string, unknown>>>().mockResolvedValue({
    id: 'ar-evolve', taskId: 't-evolve', taskTitle: '找出并消除一个高频使用阻力', projectId: 'p1',
    projectName: 'Wa All in One', engine: 'claude', status: 'queued', runnerId: null,
    runnerName: null, requestedRunnerId: 'rn2', branchName: 'ai/wao-ar-evolve', resultSummary: null,
    latestProgress: '目标已进入队列', progressUpdatedAt: '2026-08-13T10:01:00', progress: [],
    sessionId: null, exitCode: null, createdAt: '2026-08-13T10:01:00', startedAt: null, finishedAt: null,
  }),
  refreshEvolutionSuggestions: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([
    {
      id: 'mined-1', title: '给任务增加依赖阻塞提示', description: '从群聊线索完善任务等待体验',
      icon: 'ri-chat-3-line', goal: '实现真实阻塞提示',
      sourceTitle: '飞书群聊 · 2026-08-12', sourceUrl: '/kb/k1?doc=d1',
    },
  ]),
  saveAiEvolutionSchedule: vi.fn<(intervalHours: number, batchSize: number) => Promise<Record<string, unknown>>>().mockImplementation(
    async (intervalHours: number, batchSize: number) => ({
      codexQuotas: [], sessionQuotas: [], weeklyQuotas: [], suggestions: [], autoStatus: 'ready',
      autoMessage: `已满足 ${intervalHours} 小时空闲与周剩余额度门槛，下次检查会自动选择最多 ${batchSize} 项改进。`,
      lastActivityAt: null, nextEligibleAt: null, nextTriggerAt: '2026-08-12T20:15:00',
      lastCheckedAt: null, lastRunId: null,
      intervalHours, batchSize,
    }),
  ),
  requestRunnerScriptUpdate: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([
    {
      id: 'rn1', name: '娃哥电脑', platform: 'Windows 11', engines: ['codex'],
      maxConcurrency: 2, activeRuns: 0, online: false, lastSeenAt: '2026-07-25T18:00:00',
      scriptUpToDate: false, updateRequested: true,
    },
    {
      id: 'rn2', name: 'Linux 主节点', platform: 'Linux 7.0', engines: ['claude'],
      maxConcurrency: 2, activeRuns: 1, online: true, lastSeenAt: '2026-07-25T20:18:20',
      scriptUpToDate: true, updateRequested: false,
    },
  ]),
  removeAiRunner: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([
    {
      id: 'rn2', name: 'Linux 主节点', platform: 'Linux 7.0', engines: ['claude'],
      maxConcurrency: 2, activeRuns: 1, online: true, lastSeenAt: '2026-07-25T20:18:20',
    },
  ]),
  removeAiWorkflow: vi.fn<() => Promise<void>>().mockResolvedValue(undefined),
  rerunAiRun: vi.fn<() => Promise<Record<string, unknown>>>()
    .mockResolvedValue({ id: 'ar3', taskId: 't1', taskTitle: '修复自动任务', retryOfRunId: 'ar1' }),
}))

/** 解除接入用例里要按台改列表，这里补齐 AiRunner 的全部字段。 */
function runner(patch: Partial<AiRunner>): AiRunner {
  return {
    id: 'rn1', name: '娃哥电脑', platform: 'Windows 11', engines: ['codex'],
    maxConcurrency: 2, configuredMaxConcurrency: null, activeRuns: 0, online: false,
    lastSeenAt: '2026-07-25T18:00:00', wakeMac: '', wakeHost: '', wakePort: 9,
    wakeConfigured: false, lastWakeRequestedAt: null, scriptUpToDate: null, updateRequested: false,
    ...patch,
  }
}

const windowsRunner = () => runner({})
const linuxRunner = () => runner({
  id: 'rn2', name: 'Linux 主节点', platform: 'Linux 7.0', engines: ['claude'],
  activeRuns: 1, online: true, lastSeenAt: '2026-07-25T20:18:20',
})

function mountHome() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  const pinia = createPinia()
  const data = useDataStore(pinia)
  return { data, router, wrapper: mount(HomeView, { global: { plugins: [pinia, router] } }) }
}

function pasteInto(el: Element, files: File[]) {
  const event = new Event('paste', { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'clipboardData', {
    value: { files, items: [], types: [], getData: () => '' },
  })
  el.dispatchEvent(event)
}

describe('HomeView AI 控制台', () => {
  afterEach(() => {
    vi.useRealTimers()
    sessionStorage.clear()
  })

  it('首屏不再展示已要求删除的 AI 说明段落', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    expect(wrapper.find('.hero').text()).not.toContain('想法有多大，产出就有多大')
    expect(wrapper.find('.hero').text()).not.toContain('多台电脑并行开工')
    wrapper.unmount()
  })

  it('顶部并排展示理念与目标入口，并把推荐内容收拢到下方双列区域', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    const primary = wrapper.find('.home-primary')
    expect(primary.find('.hero').exists()).toBe(true)
    expect(primary.find('.goal-launcher').exists()).toBe(true)

    const recommendations = wrapper.find('.home-recommendations')
    expect(recommendations.attributes('aria-label')).toBe('首页相关推荐')
    expect(recommendations.find('.daily-guidance').exists()).toBe(true)
    expect(recommendations.find('.evolution-panel').exists()).toBe(true)
    wrapper.unmount()
  })

  it('打开首页会展示结合真实任务生成且可刷新的每日建议', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    expect(fetchDailyGuidance).toHaveBeenCalled()
    expect(wrapper.find('.daily-guidance').text()).toContain('给此刻的你')
    expect(wrapper.find('.guidance-content').text()).toContain('先理解团队')
    expect(wrapper.find('.guidance-content').text()).toContain('发挥优势')

    await wrapper.find('.guidance-refresh').trigger('click')
    await flushPromises()
    expect(generateDailyGuidance).toHaveBeenCalledWith(true)
    expect(wrapper.find('.guidance-content').text()).toContain('主动认识一位同事')
    wrapper.unmount()
  })

  it('当天没有缓存时只等待后台预热，不在首页实时生成', async () => {
    vi.useFakeTimers()
    vi.mocked(generateDailyGuidance).mockClear()
    vi.mocked(fetchDailyGuidance).mockResolvedValueOnce({
      date: '2026-08-13', status: 'missing', content: null,
      message: '今天的建议尚未生成，后台会自动准备', generatedAt: null,
    })
    const { wrapper } = mountHome()
    await flushPromises()

    expect(generateDailyGuidance).not.toHaveBeenCalled()
    expect(wrapper.find('.guidance-state').text()).toContain('正在后台准备')
    expect(wrapper.find('.guidance-state button').text()).toContain('重新读取')
    wrapper.unmount()
  })

  it('刷新页面时先显示本次会话缓存并在后台校准服务端结果', async () => {
    vi.useFakeTimers()
    const parts = new Intl.DateTimeFormat('zh-CN', {
      timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    }).formatToParts(new Date())
    const date = Object.fromEntries(parts.map((part) => [part.type, part.value]))
    sessionStorage.setItem('wao-daily-guidance-v1:wg', JSON.stringify({
      date: `${date.year}-${date.month}-${date.day}`, status: 'ready',
      content: '## 已提前准备\n\n- 刷新后立即可见。', message: '已缓存', generatedAt: '2026-08-13T08:00:00',
    }))
    let resolveFetch!: (value: DailyGuidance) => void
    vi.mocked(fetchDailyGuidance).mockReturnValueOnce(new Promise((resolve) => { resolveFetch = resolve }))

    const { wrapper } = mountHome()

    expect(wrapper.find('.guidance-content').text()).toContain('刷新后立即可见')
    expect(wrapper.find('.daily-guidance').text()).not.toContain('正在读取今天的建议')

    resolveFetch({
      date: `${date.year}-${date.month}-${date.day}`, status: 'ready',
      content: '## 服务端结果\n\n- 已完成后台校准。', message: '已生成', generatedAt: '2026-08-13T09:00:00',
    })
    await flushPromises()
    expect(wrapper.find('.guidance-content').text()).toContain('已完成后台校准')
    wrapper.unmount()
  })

  it('会话缓存后台读取失败时保留内容且重试只读取不生成', async () => {
    vi.useFakeTimers()
    vi.mocked(generateDailyGuidance).mockClear()
    const parts = new Intl.DateTimeFormat('zh-CN', {
      timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    }).formatToParts(new Date())
    const date = Object.fromEntries(parts.map((part) => [part.type, part.value]))
    sessionStorage.setItem('wao-daily-guidance-v1:wg', JSON.stringify({
      date: `${date.year}-${date.month}-${date.day}`, status: 'ready',
      content: '## 已提前准备', message: '已缓存', generatedAt: '2026-08-13T08:00:00',
    }))
    vi.mocked(fetchDailyGuidance).mockRejectedValueOnce(new Error('网络暂时不可用'))

    const { wrapper } = mountHome()
    await flushPromises()

    expect(wrapper.find('.guidance-content').text()).toContain('已提前准备')
    expect(wrapper.find('.guidance-inline-error').text()).toContain('读取失败')
    await wrapper.find('.guidance-inline-error button').trigger('click')
    await flushPromises()
    expect(generateDailyGuidance).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('换一组失败时保留原建议并提供原地重试', async () => {
    vi.useFakeTimers()
    vi.mocked(generateDailyGuidance).mockRejectedValueOnce(new Error('本地 Codex 暂时繁忙'))
    const { wrapper } = mountHome()
    await flushPromises()

    await wrapper.find('.guidance-refresh').trigger('click')
    await flushPromises()
    expect(wrapper.find('.guidance-content').text()).toContain('先理解团队')
    expect(wrapper.find('.guidance-inline-error').text()).toContain('现有建议已保留')
    expect(wrapper.find('.guidance-inline-error button').text()).toContain('重试')
    wrapper.unmount()
  })

  it('首页展示真实 Codex 本轮和周剩余额度并可一键派发改进建议', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    expect(fetchAiEvolution).toHaveBeenCalled()
    expect(wrapper.find('.session-quota').text()).toContain('Codex 本轮剩余额度')
    expect(wrapper.find('.session-quota').text()).toContain('88%')
    expect(wrapper.find('.session-quota').text()).toContain('5 小时周期')
    expect(wrapper.find('.weekly-quota').text()).toContain('周剩余额度')
    expect(wrapper.find('.weekly-quota').text()).toContain('38%')
    expect(wrapper.find('.codex-usage-panel').text()).toContain('wage@example.com')
    expect(wrapper.find('.codex-usage-panel').text()).toContain('Pro 套餐')
    expect(wrapper.find('.codex-usage-panel').text()).toContain('点数余额0')
    expect(wrapper.find('.codex-usage-panel').text()).toContain('GPT-5.3-Codex-Spark')
    expect(wrapper.find('.codex-usage-panel').text()).toContain('100%')
    expect(wrapper.find('.codex-usage-panel').text()).toContain('2 台电脑同账号')
    expect(wrapper.find('.evolution-panel').text()).toContain('已满足 12 小时空闲')
    expect(wrapper.find('.evolution-policy').text()).toContain('建议挖掘和自动派发只选择用户可感知的功能与提效改进')
    expect(wrapper.find('.suggestion-card').text()).toContain('找出并消除一个高频使用阻力')

    await wrapper.find('.suggestion-card button').trigger('click')
    await flushPromises()
    expect(launchEvolutionSuggestion).toHaveBeenCalledWith('ux-friction', 'p1')
    expect(wrapper.findAll('.run-card').some((card) => card.text().includes('高频使用阻力'))).toBe(true)
    wrapper.unmount()
  })

  it('可以保存自动检测间隔和单次任务数', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    await wrapper.find('.schedule-toggle').trigger('click')
    const interval = wrapper.find('.evolution-schedule input')
    const batch = wrapper.find('.evolution-schedule select')
    expect((interval.element as HTMLInputElement).value).toBe('12')
    expect((batch.element as HTMLSelectElement).value).toBe('1')
    await interval.setValue('4')
    await batch.setValue('3')
    await wrapper.find('.evolution-schedule').trigger('submit')
    await flushPromises()

    expect(saveAiEvolutionSchedule).toHaveBeenCalledWith(4, 3)
    expect(wrapper.find('.schedule-feedback.saved').text()).toContain('已保存')
    expect(wrapper.find('.evolution-panel').text()).toContain('4 小时')
    expect(wrapper.find('.evolution-panel').text()).toContain('3 项')
    expect(wrapper.find('.evolution-schedule-summary').text()).toContain('当前策略 每 4 小时检查 · 每轮最多 3 个任务')
    expect(wrapper.find('.evolution-schedule-summary').text()).toContain('预计最早触发')
    expect(wrapper.find('.evolution-schedule-summary').text()).toContain('08/12 20:15')
    wrapper.unmount()
  })

  it('频率保存失败时保留草稿并可原地重试', async () => {
    vi.useFakeTimers()
    vi.mocked(saveAiEvolutionSchedule)
      .mockRejectedValueOnce(new Error('网络暂时不可用'))
      .mockResolvedValueOnce({
        codexQuotas: [], sessionQuotas: [], weeklyQuotas: [], suggestions: [], autoStatus: 'ready',
        autoMessage: '已满足 2 小时空闲与周剩余额度门槛，下次检查会自动选择最多 5 项改进。',
        lastActivityAt: null, nextEligibleAt: null, nextTriggerAt: null,
        lastCheckedAt: null, lastRunId: null,
        intervalHours: 2, batchSize: 5,
      })
    const { wrapper } = mountHome()
    await flushPromises()

    await wrapper.find('.schedule-toggle').trigger('click')
    await wrapper.find('.evolution-schedule input').setValue('2')
    await wrapper.find('.evolution-schedule select').setValue('5')
    await wrapper.find('.evolution-schedule').trigger('submit')
    await flushPromises()
    expect(wrapper.find('.schedule-feedback.error').text()).toContain('草稿仍在')
    expect((wrapper.find('.evolution-schedule input').element as HTMLInputElement).value).toBe('2')
    expect((wrapper.find('.evolution-schedule select').element as HTMLSelectElement).value).toBe('5')

    await wrapper.find('.evolution-schedule').trigger('submit')
    await flushPromises()
    expect(saveAiEvolutionSchedule).toHaveBeenLastCalledWith(2, 5)
    expect(wrapper.find('.schedule-feedback.saved').exists()).toBe(true)
    wrapper.unmount()
  })

  it('真实周额度为空、同步失败或读取失败时不在首页展示额度', async () => {
    vi.useFakeTimers()
    const unavailableOverview: AiEvolutionOverview = {
      codexQuotas: [],
      sessionQuotas: [],
      weeklyQuotas: [],
      suggestions: [{
        id: 'ux-friction', title: '找出并消除一个高频使用阻力',
        description: '完成一项真实体验优化', icon: 'ri-magic-line', goal: '体验走查',
        sourceTitle: '真实体验记录', sourceUrl: '/kb/k1?doc=d1',
      }],
      autoStatus: 'quota_unavailable', autoMessage: '等待真实额度',
      lastActivityAt: null, nextEligibleAt: null, nextTriggerAt: null,
      lastCheckedAt: null, lastRunId: null,
      intervalHours: 12, batchSize: 1,
    }
    vi.mocked(fetchAiEvolution).mockResolvedValueOnce(unavailableOverview)

    const emptyResult = mountHome()
    await flushPromises()
    expect(emptyResult.wrapper.find('.weekly-quota').exists()).toBe(false)
    expect(emptyResult.wrapper.find('.session-quota').exists()).toBe(false)
    expect(emptyResult.wrapper.find('.capacity').exists()).toBe(true)
    expect(emptyResult.wrapper.find('.hero-metrics').classes()).toContain('single')
    emptyResult.wrapper.unmount()

    vi.mocked(fetchAiEvolution).mockResolvedValueOnce({
      ...unavailableOverview,
      sessionQuotas: [{
        accountId: 'aa-codex', provider: 'codex', label: 'Codex 旧快照', plan: 'plus',
        usedPercent: 45, remainingPercent: 55, resetsAt: '2026-08-14T15:00:00',
        lastSyncedAt: '2026-08-13T10:00:00', status: 'FAILED', message: '额度接口暂时不可用',
      }],
      weeklyQuotas: [{
        accountId: 'aa-codex', provider: 'codex', label: 'Codex 旧快照', plan: 'plus',
        usedPercent: 35, remainingPercent: 65, resetsAt: '2026-08-17T10:00:00',
        lastSyncedAt: '2026-08-13T10:00:00', status: 'FAILED', message: '额度接口暂时不可用',
      }],
    })
    const staleResult = mountHome()
    await flushPromises()
    expect(staleResult.wrapper.find('.weekly-quota').exists()).toBe(false)
    expect(staleResult.wrapper.find('.session-quota').exists()).toBe(false)
    expect(staleResult.wrapper.find('.evolution-panel footer').exists()).toBe(false)
    staleResult.wrapper.unmount()

    vi.mocked(fetchAiEvolution).mockRejectedValueOnce(new Error('额度接口暂时不可用'))
    const failedResult = mountHome()
    await flushPromises()
    expect(failedResult.wrapper.find('.weekly-quota').exists()).toBe(false)
    expect(failedResult.wrapper.find('.capacity').exists()).toBe(true)
    failedResult.wrapper.unmount()
  })

  it('不足五条时仍用可靠的新建议换批并展示可追溯来源', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    await wrapper.find('.suggestion-refresh').trigger('click')
    await flushPromises()

    expect(refreshEvolutionSuggestions).toHaveBeenCalled()
    expect(wrapper.findAll('.suggestion-card')).toHaveLength(1)
    expect(wrapper.find('.suggestion-card').text()).toContain('给任务增加依赖阻塞提示')
    expect(wrapper.find('.suggestion-source').text()).toContain('飞书群聊 · 2026-08-12')
    expect(wrapper.find('.suggestion-source').attributes('href')).toBe('/kb/k1?doc=d1')
    wrapper.unmount()
  })

  it('换批期间暂停旧概览轮询，避免新建议被并发响应覆盖', async () => {
    vi.useFakeTimers()
    let resolveSuggestions!: (value: AiEvolutionSuggestion[]) => void
    vi.mocked(refreshEvolutionSuggestions).mockReturnValueOnce(new Promise((resolve) => {
      resolveSuggestions = resolve
    }))
    const { wrapper } = mountHome()
    await flushPromises()
    const overviewCalls = vi.mocked(fetchAiEvolution).mock.calls.length

    await wrapper.find('.suggestion-refresh').trigger('click')
    expect(wrapper.find('.suggestion-feedback').text()).toContain('正在从真实资料生成新一批建议')
    await vi.advanceTimersByTimeAsync(60_000)

    expect(fetchAiEvolution).toHaveBeenCalledTimes(overviewCalls)
    resolveSuggestions([{
      id: 'mined-race-safe', title: '不会被旧轮询覆盖的新建议', description: '刷新结果保持可见',
      icon: 'ri-chat-3-line', goal: '修复刷新竞态',
      sourceTitle: '飞书群聊 · 2026-08-15', sourceUrl: '/kb/k1?doc=d2',
    }])
    await flushPromises()
    expect(wrapper.find('.suggestion-card').text()).toContain('不会被旧轮询覆盖的新建议')
    wrapper.unmount()
  })

  it('换批失败时在旧建议前就地说明原因并允许重试', async () => {
    vi.useFakeTimers()
    vi.mocked(refreshEvolutionSuggestions).mockRejectedValueOnce(new Error('真实资料暂未提供可靠的新任务线索'))
    const { wrapper } = mountHome()
    await flushPromises()

    await wrapper.find('.suggestion-refresh').trigger('click')
    await flushPromises()

    const feedback = wrapper.find('.suggestion-feedback.error')
    expect(feedback.text()).toContain('换一批失败')
    expect(feedback.text()).toContain('原建议已保留')
    expect(feedback.find('button').text()).toBe('重试')
    expect(wrapper.find('.suggestion-card').text()).toContain('找出并消除一个高频使用阻力')
    const panelHtml = wrapper.find('.evolution-panel').html()
    expect(panelHtml.indexOf('suggestion-feedback error')).toBeLessThan(panelHtml.indexOf('suggestion-grid'))
    wrapper.unmount()
  })

  it('首次没有已保存批次时自动从真实资料生成', async () => {
    vi.useFakeTimers()
    vi.mocked(fetchAiEvolution).mockResolvedValueOnce({
      codexQuotas: [], sessionQuotas: [], weeklyQuotas: [], suggestions: [], autoStatus: 'quota_unavailable',
      autoMessage: '等待真实额度', lastActivityAt: null, nextEligibleAt: null,
      nextTriggerAt: null, lastCheckedAt: null, lastRunId: null, intervalHours: 12, batchSize: 1,
    })
    const refreshCalls = vi.mocked(refreshEvolutionSuggestions).mock.calls.length

    const { wrapper } = mountHome()
    await flushPromises()

    expect(refreshEvolutionSuggestions).toHaveBeenCalledTimes(refreshCalls + 1)
    expect(wrapper.find('.suggestion-card').text()).toContain('给任务增加依赖阻塞提示')
    wrapper.unmount()
  })

  it('只输入一句目标就能选择执行电脑并交给已启用的 AI 工作流', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    const editor = wrapper.findComponent(MarkdownEditor)
    editor.vm.$emit('update:modelValue', '把黄帝内经和易经结合 AI，做成每日一卦产品')
    await flushPromises()
    expect((wrapper.find('.goal-workflow-select').element as HTMLSelectElement).value).toBe('p1')
    const runnerSelect = wrapper.find('.goal-runner-select')
    expect((runnerSelect.element as HTMLSelectElement).value).toBe('rn2')
    expect(runnerSelect.text()).toContain('Linux 主节点 · 在线 1/2')
    expect(runnerSelect.text()).not.toContain('娃哥电脑')
    await wrapper.find('.goal-submit').trigger('click')
    await flushPromises()

    expect(launchAutonomousGoal).toHaveBeenCalledWith(
      'p1', '把黄帝内经和易经结合 AI，做成每日一卦产品', 'rn2',
    )
    expect(editor.props('modelValue')).toBe('')
    expect(wrapper.findAll('.run-card').some((card) => card.text().includes('把黄帝内经和易经结合 AI'))).toBe(true)
    wrapper.unmount()
  })

  it('自主目标可以改选另一台兼容电脑并允许离线排队', async () => {
    vi.useFakeTimers()
    vi.mocked(fetchAiRunners).mockResolvedValueOnce([
      windowsRunner(),
      linuxRunner(),
      runner({
        id: 'rn3', name: '备用 Linux 电脑', platform: 'Linux', engines: ['claude'],
        online: false, activeRuns: 0,
      }),
    ])
    const { wrapper } = mountHome()
    await flushPromises()

    const runnerSelect = wrapper.find('.goal-runner-select')
    expect(runnerSelect.text()).toContain('备用 Linux 电脑 · 离线')
    await runnerSelect.setValue('rn3')
    const editor = wrapper.findComponent(MarkdownEditor)
    editor.vm.$emit('update:modelValue', '只在备用电脑执行这个目标')
    await flushPromises()
    await wrapper.find('.goal-submit').trigger('click')
    await flushPromises()

    expect(launchAutonomousGoal).toHaveBeenCalledWith('p1', '只在备用电脑执行这个目标', 'rn3')
    wrapper.unmount()
  })

  it('长目标不再被 4000 字的人为上限拦住', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()
    const longGoal = `完成这个长目标\n${'详细背景'.repeat(1_100)}`

    const editor = wrapper.findComponent(MarkdownEditor)
    editor.vm.$emit('update:modelValue', longGoal)
    await flushPromises()

    expect(longGoal.length).toBeGreaterThan(4_000)
    expect(wrapper.find('.goal-shortcut').text()).toContain(`${longGoal.length} 字`)
    expect(wrapper.find('.goal-shortcut').text()).not.toContain('/ 4000')
    expect(wrapper.find('.goal-submit').attributes('disabled')).toBeUndefined()
    await wrapper.find('.goal-submit').trigger('click')
    await flushPromises()
    expect(launchAutonomousGoal).toHaveBeenCalledWith('p1', longGoal, 'rn2')
    wrapper.unmount()
  })

  it('自主目标可以一次粘贴多张图片，全部上传并内联后再派发', async () => {
    vi.useFakeTimers()
    const { data, wrapper } = mountHome()
    await flushPromises()
    let finishFirst!: (image: { url: string; name: string }) => void
    let finishSecond!: (image: { url: string; name: string }) => void
    const upload = vi.spyOn(data, 'uploadImage')
      .mockReturnValueOnce(new Promise((resolve) => { finishFirst = resolve }))
      .mockReturnValueOnce(new Promise((resolve) => { finishSecond = resolve }))
    const files = [
      new File([new Uint8Array(4)], 'first.png', { type: 'image/png' }),
      new File([new Uint8Array(4)], 'second.png', { type: 'image/png' }),
    ]

    pasteInto(wrapper.find('.goal-compose [contenteditable]').element, files)
    await flushPromises()
    expect(upload).toHaveBeenCalledTimes(2)
    expect(upload.mock.calls.map((call) => call[1])).toEqual(['自主目标', '自主目标'])
    expect(upload.mock.calls.map((call) => call[2])).toEqual([
      { requirePersisted: true },
      { requirePersisted: true },
    ])
    expect(wrapper.find('.goal-shortcut').text()).toContain('2 张图片上传中')
    expect(wrapper.find('.goal-submit').attributes('disabled')).toBeDefined()

    finishSecond({ url: '/api/images/second/raw', name: 'second.png' })
    await flushPromises()
    expect(wrapper.findAll('.goal-compose img')).toHaveLength(0)
    finishFirst({ url: '/api/images/first/raw', name: 'first.png' })
    await flushPromises()

    expect(wrapper.findAll('.goal-compose img').map((img) => img.attributes('src'))).toEqual([
      '/api/images/first/raw',
      '/api/images/second/raw',
    ])
    expect(wrapper.find('.goal-shortcut').text()).not.toContain('上传中')
    await wrapper.find('.goal-submit').trigger('click')
    await flushPromises()
    const launchCalls = vi.mocked(launchAutonomousGoal).mock.calls
    const submittedGoal = launchCalls[launchCalls.length - 1]?.[1] ?? ''
    expect(submittedGoal.indexOf('/api/images/first/raw')).toBeLessThan(submittedGoal.indexOf('/api/images/second/raw'))
    upload.mockRestore()
    wrapper.unmount()
  })

  it('在失败记录中展示摘要，点击后展示完整原因和退出码', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    expect(wrapper.find('.recent-grid').exists()).toBe(true)
    expect(wrapper.find('.recent-card').text()).toContain('查看详情')
    expect(wrapper.find('.recent-card').text()).toContain('Codex')
    expect(wrapper.find('.recent-main small').text()).toBe('失败阶段：准备 Git 工作区')

    await wrapper.find('.recent-card').trigger('click')

    expect(wrapper.find('.run-detail').text()).toContain('失败原因')
    expect(wrapper.find('.run-detail').text()).toContain('仓库地址无法克隆')
    expect(wrapper.find('.run-detail').text()).toContain('-1')
    expect(wrapper.find('.run-detail').text()).not.toContain('等待第一条 AI 反馈')
    expect(wrapper.find('.run-report-link').attributes('href')).toBe('/api/pages/ai-run-ar1')
    wrapper.unmount()
  })

  it('从紧凑的最近完成区打开可翻页的完整运行历史', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    expect(wrapper.find('.history-link').text()).toContain('查看全部')
    await wrapper.find('.history-link').trigger('click')
    await flushPromises()

    expect(fetchAiRunHistory).toHaveBeenCalledWith(1, 20)
    expect(wrapper.find('.run-history-detail').text()).toContain('全部运行历史')
    expect(wrapper.find('.run-history-detail').text()).toContain('历史运行')
    expect(wrapper.find('.history-pagination').text()).toContain('第 1 / 2 页')
    await wrapper.find('.run-history-detail .recent-card').trigger('click')
    expect(wrapper.findAll('.run-detail')).toHaveLength(2)
    await wrapper.findAll('.run-detail')[1]!.find('.detail-close').trigger('click')
    expect(wrapper.find('.run-history-detail').exists()).toBe(true)
    wrapper.unmount()
  })

  it('失败的运行可以带着报错重跑，成功的运行不给这个按钮', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    // 失败记录：详情里有「基于报错重跑」
    await wrapper.find('.recent-card').trigger('click')
    const retry = wrapper.find('.run-detail footer .retry')
    expect(retry.exists()).toBe(true)
    expect(retry.text()).toContain('基于报错重跑')

    await retry.trigger('click')
    await flushPromises()
    expect(rerunAiRun).toHaveBeenCalledWith('ar1')
    // 重跑后收起详情，回到列表看新排队的运行
    expect(wrapper.find('.run-detail').exists()).toBe(false)

    // 进行中的运行不该出现这个按钮
    await wrapper.find('.run-card').trigger('click')
    expect(wrapper.find('.run-detail footer .retry').exists()).toBe(false)
    wrapper.unmount()
  })

  it('运行卡片展示最新反馈和使用的 AI 工具，点击先打开实时记录而不是跳走', async () => {
    vi.useFakeTimers()
    const { router, wrapper } = mountHome()
    await flushPromises()

    expect(wrapper.find('.run-card').text()).toContain('已定位问题，正在实现实时反馈')
    expect(wrapper.find('.run-card').text()).toContain('Claude Code')
    expect(wrapper.find('.run-card').text()).toContain('根据截图修复展示')
    expect(wrapper.find('.run-card').text()).not.toContain('data:image')
    expect(wrapper.findAll('.run-card .run-image-strip img').map((image) => image.attributes('src'))).toEqual([
      '/api/images/img-preview-1/raw',
      '/api/images/img-preview-2/raw',
    ])
    await wrapper.find('.run-card').trigger('click')

    expect(wrapper.find('.run-detail').text()).toContain('AI 执行记录')
    expect(wrapper.find('.run-detail').text()).toContain('Claude Code')
    expect(wrapper.findAll('.run-detail-images img')).toHaveLength(2)
    expect(router.currentRoute.value.fullPath).toBe('/')
    wrapper.unmount()
  })

  it('电脑视图按机器展示接入的 AI 工具、槽位和正在跑的任务', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    await wrapper.findAll('.console-tabs button')[1]!.trigger('click')

    const cards = wrapper.findAll('.runner-card')
    expect(cards).toHaveLength(2)
    expect(cards[0]!.text()).toContain('Codex')
    expect(cards[0]!.classes()).toContain('offline')
    expect(cards[1]!.text()).toContain('Claude Code')
    expect(cards[1]!.text()).toContain('槽位 1 / 2')
    expect(cards[1]!.find('.runner-tasks button').text()).toBe('根据截图修复展示')
    wrapper.unmount()
  })

  it('执行电脑卡片按需读取日志，失败后可以重试并展示安全快照', async () => {
    vi.useFakeTimers()
    vi.mocked(fetchAiRunnerLog)
      .mockRejectedValueOnce(new Error('网络暂时不可用'))
      .mockResolvedValueOnce({
        runnerId: 'rn1', runnerName: '娃哥电脑',
        content: '2026-07-31 13:30:00 WAO AI Runner 已启动', updatedAt: '2026-07-31T13:30:00',
      })
    const { wrapper } = mountHome()
    await flushPromises()

    await wrapper.findAll('.console-tabs button')[1]!.trigger('click')
    await wrapper.findAll('.runner-card')[0]!.find('.runner-log-btn').trigger('click')
    await flushPromises()

    expect(fetchAiRunnerLog).toHaveBeenCalledWith('rn1')
    expect(wrapper.find('.runner-log-state.error').text()).toContain('网络暂时不可用')

    await wrapper.find('.runner-log-state.error button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.runner-log-content').text()).toContain('WAO AI Runner 已启动')
    expect(wrapper.find('.runner-log-meta').text()).toContain('更新于')
    wrapper.unmount()
  })

  it('电脑视图可以一键下发 Runner 脚本更新，卡片展示更新进度', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    await wrapper.findAll('.console-tabs button')[1]!.trigger('click')
    await wrapper.find('.section-head .pair-btn.ghost').trigger('click')
    await flushPromises()

    expect(requestRunnerScriptUpdate).toHaveBeenCalledTimes(1)
    const cards = wrapper.findAll('.runner-card')
    expect(cards[0]!.text()).toContain('更新已下发，等该电脑空闲后自动重启生效')
    expect(cards[1]!.text()).not.toContain('更新已下发')
    wrapper.unmount()
  })

  it('空闲的电脑可以确认后解除接入，卡片随即消失', async () => {
    vi.useFakeTimers()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { wrapper } = mountHome()
    await flushPromises()

    await wrapper.findAll('.console-tabs button')[1]!.trigger('click')
    // 解除后再拉列表时，这台电脑已经不在了
    vi.mocked(fetchAiRunners).mockResolvedValue([linuxRunner()])
    await wrapper.findAll('.runner-card')[0]!.find('header .icon-btn.danger').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalled()
    expect(removeAiRunner).toHaveBeenCalledWith('rn1')
    const cards = wrapper.findAll('.runner-card')
    expect(cards).toHaveLength(1)
    expect(cards[0]!.text()).toContain('Linux 主节点')
    confirm.mockRestore()
    wrapper.unmount()
  })

  it('还有任务在跑的电脑不能解除接入，也不会弹确认框', async () => {
    vi.useFakeTimers()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(fetchAiRunners).mockResolvedValue([windowsRunner(), linuxRunner()])
    const { wrapper } = mountHome()
    await flushPromises()

    await wrapper.findAll('.console-tabs button')[1]!.trigger('click')
    await wrapper.findAll('.runner-card')[1]!.find('header .icon-btn.danger').trigger('click')
    await flushPromises()

    expect(confirm).not.toHaveBeenCalled()
    expect(removeAiRunner).not.toHaveBeenCalledWith('rn2')
    expect(wrapper.findAll('.runner-card')).toHaveLength(2)
    confirm.mockRestore()
    wrapper.unmount()
  })

  it('工作流视图展示项目配置，点击后可以就地编辑默认 AI 工具', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    await wrapper.findAll('.console-tabs button')[2]!.trigger('click')
    await flushPromises()

    const card = wrapper.find('.workflow-card')
    expect(card.text()).toContain('Wa All in One')
    expect(card.text()).toContain('Claude Code')
    expect(card.text()).toContain('Linux 主节点')

    await card.find('.link-btn').trigger('click')
    const engineSelect = card.findAll('.flow-form select')[0]!
    expect((engineSelect.element as HTMLSelectElement).value).toBe('claude')
    wrapper.unmount()
  })

  it('删除工作流需要二次确认，并在确认后调用真实删除接口', async () => {
    vi.useFakeTimers()
    vi.mocked(removeAiWorkflow).mockClear()
    const confirm = vi.spyOn(window, 'confirm')
      .mockReturnValueOnce(false)
      .mockReturnValueOnce(true)
    const { wrapper } = mountHome()
    await flushPromises()

    await wrapper.findAll('.console-tabs button')[2]!.trigger('click')
    await flushPromises()
    const deleteButton = wrapper.find('.workflow-card .link-btn.danger')
    await deleteButton.trigger('click')
    expect(removeAiWorkflow).not.toHaveBeenCalled()

    vi.mocked(fetchAiWorkflows).mockResolvedValueOnce([])
    await deleteButton.trigger('click')
    await flushPromises()

    expect(confirm.mock.calls[1]?.[0]).toContain('历史运行记录保留')
    expect(removeAiWorkflow).toHaveBeenCalledWith('p1')
    expect(wrapper.find('.workflow-card').exists()).toBe(false)
    confirm.mockRestore()
    wrapper.unmount()
  })
})
