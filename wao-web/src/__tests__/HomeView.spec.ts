import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia } from 'pinia'
import HomeView from '@/views/HomeView.vue'
import MarkdownEditor from '@/components/ui/MarkdownEditor.vue'
import { useDataStore } from '@/stores/data'
import { usePreferenceStore } from '@/stores/preferences'
import type { AiRunner } from '@/types'
import {
  fetchAiRunnerLog,
  fetchAiRunHistory,
  fetchAiEvolution,
  fetchAiTaskAutoRunPolicy,
  fetchAiRunners,
  fetchAiWorkflows,
  launchAutonomousGoal,
  pauseAiRun,
  removeAiRunner,
  removeAiWorkflow,
  requestRunnerScriptUpdate,
  rerunAiRun,
  saveAiTaskAutoRunPolicy,
} from '@/api/ai'
import { fetchWebhookEndpoints } from '@/api/system'
import { fetchChatReviews } from '@/api/chatReviews'

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
  fetchAiRunOptions: vi.fn<() => Promise<Record<string, unknown>>>().mockResolvedValue({
    codexModels: [{ id: 'gpt-5.6-sol', label: 'GPT-5.6 Sol', description: '复杂开发任务的旗舰模型' }],
    reasoningEfforts: [
      { id: 'high', label: '高', description: '为复杂任务投入更多推理' },
      { id: 'medium', label: '中', description: '速度与推理深度均衡' },
      { id: 'low', label: '低', description: '优先速度与较低消耗' },
    ],
    defaultCodexModel: 'gpt-5.6-sol', defaultReasoningEffort: 'high', serviceTier: 'default',
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
          usedPercent: 62, windowDurationMinutes: 10080,
          resetsAt: Math.floor(Date.now() / 1000) + 3_600,
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
  fetchAiTaskAutoRunPolicy: vi.fn<() => Promise<Record<string, unknown>>>().mockResolvedValue({
    enabled: true, idleHours: 2, projectId: 'p1', taskStatuses: ['待办'],
    notificationWebhookId: 'fw-auto', status: 'ready',
    message: '空闲达到 2 小时后，将从「Wa All in One」自动以 Plan 模式执行录入最早的匹配任务。',
    lastActivityAt: '2026-08-25T09:00:00', nextEligibleAt: '2026-08-25T11:00:00',
    lastCheckedAt: '2026-08-25T10:55:00', lastRunId: null,
  }),
  saveAiTaskAutoRunPolicy: vi.fn<(
    enabled: boolean,
    idleHours: number,
    projectId: string,
    taskStatuses: Array<'待办' | '收集中' | '进行中'>,
    notificationWebhookId: string | null,
  ) => Promise<Record<string, unknown>>>()
    .mockImplementation(async (enabled, idleHours, projectId, taskStatuses, notificationWebhookId) => ({
      enabled, idleHours, projectId, taskStatuses, notificationWebhookId, status: enabled ? 'ready' : 'disabled',
      message: enabled ? `空闲达到 ${idleHours} 小时后自动派发。` : '自动派发已关闭。',
      lastActivityAt: null, nextEligibleAt: null, lastCheckedAt: null, lastRunId: null,
    })),
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
  pauseAiRun: vi.fn<() => Promise<Record<string, unknown>>>().mockResolvedValue({
    id: 'ar2', taskId: 't2', taskTitle: '根据截图修复展示', previewImages: [],
    projectId: 'p1', projectName: 'WAO', engine: 'claude', status: 'cancelled',
    runnerId: 'rn2', runnerName: 'Linux 主节点', requestedRunnerId: 'rn2',
    branchName: 'ai/wao-ar2', retryOfRunId: null, resultSummary: '用户已暂停此 AI 任务',
    reportUrl: null, latestProgress: '用户已暂停 AI 任务', progressUpdatedAt: '2026-07-25T20:19:00',
    progress: [], usage: { model: null, models: null, inputTokens: 0, outputTokens: 0,
      cachedInputTokens: 0, totalTokens: 0, costUsd: null, recorded: false },
    sessionId: 'sess-1', exitCode: null, createdAt: '2026-07-25T20:18:00',
    startedAt: '2026-07-25T20:18:05', finishedAt: '2026-07-25T20:19:00',
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

vi.mock('@/api/chatReviews', () => ({
  fetchChatReviews: vi.fn<(typeof import('@/api/chatReviews'))['fetchChatReviews']>()
    .mockResolvedValue({ activeRun: null, history: [] }),
}))

vi.mock('@/api/system', () => ({
  fetchWebhookEndpoints: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([
    {
      id: 'fw-auto', name: '自动任务群', description: '无人值守任务通知', urlMask: 'https://open.feishu.cn/***',
      urlSet: true, enabled: true, systemDefault: false,
      createdAt: '2026-08-25T09:00:00', updatedAt: '2026-08-25T09:00:00',
    },
  ]),
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
  return { data, router, pinia, wrapper: mount(HomeView, { global: { plugins: [pinia, router] } }) }
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
    localStorage.clear()
  })

  it('首屏不再展示已要求删除的 AI 说明段落', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    expect(wrapper.find('.hero').text()).not.toContain('想法有多大，产出就有多大')
    expect(wrapper.find('.hero').text()).not.toContain('多台电脑并行开工')
    wrapper.unmount()
  })

  it('首页只保留理念、目标入口和 AI 控制台', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    const primary = wrapper.find('.home-primary')
    expect(primary.find('.hero').exists()).toBe(true)
    expect(primary.find('.goal-launcher').exists()).toBe(true)
    expect(wrapper.find('.home-recommendations').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('给此刻的你')
    expect(wrapper.text()).not.toContain('让剩余额度持续完善 WAO')
    expect(fetchAiEvolution).toHaveBeenCalled()
    expect(wrapper.find('.hero .weekly-quota').text()).toContain('Codex 周剩余额度')
    expect(wrapper.find('.hero .weekly-quota').text()).toContain('38%')
    expect(wrapper.find('.console-quota').exists()).toBe(false)
    expect(wrapper.find('.capacity-orbit').exists()).toBe(true)
    expect(wrapper.find('.hero-link').text()).toContain('查看 AI 详情')
    expect(wrapper.findAll('.hero-link')[1]!.text()).toContain('查看系统报告')
    wrapper.unmount()
  })

  it('最近完成列表上方可保存自动派发开关和空闲小时数', async () => {
    vi.useFakeTimers()
    const { wrapper } = mountHome()
    await flushPromises()

    const policy = wrapper.find('.auto-run-policy')
    expect(fetchAiTaskAutoRunPolicy).toHaveBeenCalled()
    expect(policy.text()).toContain('空闲时自动推进最早待办')
    expect(policy.text()).toContain('Plan 模式')
    expect(policy.text()).toContain('Wa All in One')
    expect(policy.text()).toContain('自动任务群')
    expect(fetchWebhookEndpoints).toHaveBeenCalled()
    expect(policy.find('.auto-run-switch').attributes('aria-checked')).toBe('true')

    await policy.find('.auto-run-switch').trigger('click')
    await policy.find('input').setValue(4)
    await policy.findAll('.auto-run-statuses input')[1]!.setValue(true)
    expect(policy.text()).toContain('设置尚未保存')
    await policy.find('.auto-run-save').trigger('click')
    await flushPromises()

    expect(saveAiTaskAutoRunPolicy).toHaveBeenCalledWith(false, 4, 'p1', ['待办', '收集中'], 'fw-auto')
    expect(policy.find('.auto-run-save').text()).toContain('已保存')
    wrapper.unmount()
  })

  it('从理念卡片进入 AI 详情仪表盘', async () => {
    vi.useFakeTimers()
    const { router, wrapper } = mountHome()
    await flushPromises()

    await wrapper.find('.hero-link').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/dashboard')
    wrapper.unmount()
  })

  it('从理念卡片进入系统报告', async () => {
    vi.useFakeTimers()
    const { router, wrapper } = mountHome()
    await flushPromises()

    await wrapper.findAll('.hero-link')[1]!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/system-reports')
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
      'p1', '把黄帝内经和易经结合 AI，做成每日一卦产品', 'rn2', false, null,
    )
    expect(editor.props('modelValue')).toBe('')
    expect(wrapper.findAll('.run-card').some((card) => card.text().includes('把黄帝内经和易经结合 AI'))).toBe(true)
    wrapper.unmount()
  })

  it('自主目标按成员偏好切换极简与详细标签', async () => {
    vi.useFakeTimers()
    const { pinia, wrapper } = mountHome()
    await flushPromises()
    const preferences = usePreferenceStore(pinia)

    expect(wrapper.findAll('.goal-control-field > span')).toHaveLength(0)
    expect(wrapper.find('.goal-workflow-select').attributes('aria-label')).toBe('放进哪条工作流')
    expect(wrapper.find('.goal-runner-select').text()).toContain('自动分配电脑')

    preferences.controlStyle = 'detailed'
    await flushPromises()
    expect(wrapper.findAll('.goal-control-field > span')).toHaveLength(2)
    expect(wrapper.text()).toContain('放进哪条工作流')
    expect(wrapper.text()).toContain('执行电脑')
    wrapper.unmount()
  })

  it('Codex 自主目标默认开启 Plan 模式并允许本次关闭', async () => {
    vi.useFakeTimers()
    vi.mocked(fetchAiWorkflows).mockResolvedValueOnce([{
      projectId: 'p1', projectName: 'Wa All in One',
      config: {
        projectId: 'p1', enabled: true, repoUrl: 'https://github.com/acme/wao.git', defaultBranch: 'main',
        workspaceDir: 'wao', defaultEngine: 'codex', preferredRunnerId: 'rn1', maxParallelRuns: 3,
      },
      configured: true, projectDeleted: false, preferredRunnerName: '娃哥电脑',
      activeRuns: 1, succeededRuns: 4, failedRuns: 1, lastRunAt: '2026-07-25T20:18:00',
    }])
    const { wrapper } = mountHome()
    await flushPromises()

    const planMode = wrapper.find('.goal-plan-mode')
    expect(planMode.attributes('aria-checked')).toBe('true')
    const editor = wrapper.findComponent(MarkdownEditor)
    editor.vm.$emit('update:modelValue', '先梳理步骤再完成首页体验优化')
    await flushPromises()
    await wrapper.find('.goal-submit').trigger('click')
    await flushPromises()
    expect(launchAutonomousGoal).toHaveBeenLastCalledWith(
      'p1', '先梳理步骤再完成首页体验优化', 'rn1', true, 'high',
    )

    editor.vm.$emit('update:modelValue', '直接完成第二个目标')
    await planMode.trigger('click')
    await flushPromises()
    expect(planMode.attributes('aria-checked')).toBe('false')
    await wrapper.find('.goal-submit').trigger('click')
    await flushPromises()
    expect(launchAutonomousGoal).toHaveBeenLastCalledWith('p1', '直接完成第二个目标', 'rn1', false, 'high')
    wrapper.unmount()
  })

  it('Codex 自主目标可选择高、中、低思考深度并固化低档', async () => {
    vi.useFakeTimers()
    vi.mocked(fetchAiWorkflows).mockResolvedValueOnce([{
      projectId: 'p1', projectName: 'Wa All in One',
      config: {
        projectId: 'p1', enabled: true, repoUrl: 'https://github.com/acme/wao.git', defaultBranch: 'main',
        workspaceDir: 'wao', defaultEngine: 'codex', preferredRunnerId: 'rn1', maxParallelRuns: 3,
      },
      configured: true, projectDeleted: false, preferredRunnerName: '娃哥电脑',
      activeRuns: 1, succeededRuns: 4, failedRuns: 1, lastRunAt: '2026-07-25T20:18:00',
    }])
    const { wrapper } = mountHome()
    await flushPromises()

    const reasoning = wrapper.get('select[aria-label="模型思考深度"]')
    expect(reasoning.text()).toContain('高 · 为复杂任务投入更多推理')
    expect(reasoning.text()).toContain('中 · 速度与推理深度均衡')
    expect(reasoning.text()).toContain('低 · 优先速度与较低消耗')
    expect((reasoning.element as HTMLSelectElement).value).toBe('high')
    await reasoning.setValue('low')
    wrapper.findComponent(MarkdownEditor).vm.$emit('update:modelValue', '快速完成一个低复杂度目标')
    await flushPromises()
    await wrapper.get('.goal-submit').trigger('click')
    await flushPromises()

    expect(launchAutonomousGoal).toHaveBeenLastCalledWith(
      'p1', '快速完成一个低复杂度目标', 'rn1', true, 'low',
    )
    wrapper.unmount()
  })

  it('离开首页后恢复未派发目标草稿，派发成功才清理', async () => {
    vi.useFakeTimers()
    const first = mountHome()
    await flushPromises()
    const firstEditor = first.wrapper.findComponent(MarkdownEditor)
    firstEditor.vm.$emit('update:modelValue', '切换页面后也不能丢掉这条目标')
    await flushPromises()

    expect(localStorage.getItem('wao-home-goal-draft-v1:wg')).toBe('切换页面后也不能丢掉这条目标')
    expect(first.wrapper.find('.goal-shortcut').text()).toContain('草稿已保存在本机')
    first.wrapper.unmount()

    const second = mountHome()
    await flushPromises()
    const restoredEditor = second.wrapper.findComponent(MarkdownEditor)
    expect(restoredEditor.props('modelValue')).toBe('切换页面后也不能丢掉这条目标')

    await second.wrapper.find('.goal-submit').trigger('click')
    await flushPromises()
    expect(localStorage.getItem('wao-home-goal-draft-v1:wg')).toBeNull()
    second.wrapper.unmount()
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

    expect(launchAutonomousGoal).toHaveBeenCalledWith('p1', '只在备用电脑执行这个目标', 'rn3', false, null)
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
    expect(launchAutonomousGoal).toHaveBeenCalledWith('p1', longGoal, 'rn2', false, null)
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

  it('在 AI 运行列表标出聊天复盘并从聊天页打开结果', async () => {
    vi.useFakeTimers()
    vi.mocked(fetchChatReviews).mockResolvedValueOnce({
      activeRun: null,
      history: [{
        id: 'cr-shared', sourceKind: 'SHARED_CHAT', rangeKind: 'MESSAGES', rangeValue: 20,
        startDate: null, endDate: null, focus: null, stage: 'SUCCEEDED', progressCurrent: 20,
        progressTotal: 20, currentDate: null, failedStage: null, failedDate: null, error: null,
        messageCount: 20, createdById: 'wd', createdByName: '娃弟',
        reportUrl: '/api/pages/ai-run-cr-shared', knowledgeUrl: '/kb/kb-chat-reviews?doc=crd-cr-shared',
        createdAt: '2026-08-26T10:00:00+08:00', startedAt: '2026-08-26T10:00:01+08:00',
        finishedAt: '2026-08-26T10:01:00+08:00',
      }],
    } as Awaited<ReturnType<typeof fetchChatReviews>>)
    const { router, wrapper } = mountHome()
    await flushPromises()

    const review = wrapper.findAll('.recent-card').find((card) => card.text().includes('聊天复盘'))!
    expect(review.text()).toContain('20 条真实消息')
    await review.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/chat?review=cr-shared')
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

  it('运行详情经二次确认后停止真实任务，取消确认时不调用接口', async () => {
    vi.useFakeTimers()
    vi.mocked(pauseAiRun).mockClear()
    const confirm = vi.spyOn(window, 'confirm')
      .mockReturnValueOnce(false)
      .mockReturnValueOnce(true)
    const { wrapper } = mountHome()
    await flushPromises()

    await wrapper.find('.run-card').trigger('click')
    const stop = wrapper.get('.run-detail footer .stop-run')
    expect(stop.text()).toContain('停止任务')
    await stop.trigger('click')
    expect(pauseAiRun).not.toHaveBeenCalled()

    await stop.trigger('click')
    await flushPromises()
    expect(confirm.mock.calls[1]?.[0]).toContain('未提交结果可能丢失')
    expect(pauseAiRun).toHaveBeenCalledWith('ar2')
    expect(wrapper.find('.run-detail footer .stop-run').exists()).toBe(false)
    expect(wrapper.find('.run-detail').text()).toContain('已取消')

    confirm.mockRestore()
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
