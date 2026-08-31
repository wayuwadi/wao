import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AiRunLauncher from '@/components/ai/AiRunLauncher.vue'
import { usePreferenceStore } from '@/stores/preferences'

vi.mock('@/api/ai', () => ({
  fetchAiRunners: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([
    {
      id: 'rn-win', name: '娃哥Windows电脑', platform: 'Windows 11', engines: ['codex'],
      maxConcurrency: 2, activeRuns: 0, online: true, lastSeenAt: '2026-07-28T12:00:00',
    },
    {
      id: 'rn-linux', name: 'Linux 主节点', platform: 'Linux', engines: ['codex'],
      maxConcurrency: 2, activeRuns: 0, online: true, lastSeenAt: '2026-07-28T12:00:00',
    },
  ]),
  fetchAiProjectConfig: vi.fn<() => Promise<Record<string, unknown>>>().mockResolvedValue({
    projectId: 'p1', enabled: true, repoUrl: 'https://github.com/acme/wao.git',
    defaultBranch: 'main', workspaceDir: 'wao', defaultEngine: 'codex',
    preferredRunnerId: 'rn-win', maxParallelRuns: 2, failureRetryCount: 2,
  }),
  fetchAiRunOptions: vi.fn<() => Promise<Record<string, unknown>>>().mockResolvedValue({
    codexModels: [
      { id: 'gpt-5.6-sol', label: 'GPT-5.6 Sol', description: '复杂开发任务的旗舰模型' },
      { id: 'gpt-5.6-terra', label: 'GPT-5.6 Terra', description: '兼顾能力与成本' },
    ],
    reasoningEfforts: [
      { id: 'high', label: '高', description: '为复杂任务投入更多推理' },
      { id: 'medium', label: '中', description: '速度与推理深度均衡' },
      { id: 'low', label: '低', description: '优先速度与较低消耗' },
    ],
    defaultCodexModel: 'gpt-5.6-sol', defaultReasoningEffort: 'high',
    defaultExecutionMode: 'plan', serviceTier: 'default',
  }),
  saveAiRunDefaultExecutionMode: vi.fn<(mode: string) => Promise<Record<string, unknown>>>().mockImplementation(
    async (mode) => ({
      codexModels: [], reasoningEfforts: [], defaultCodexModel: 'gpt-5.6-sol',
      defaultReasoningEffort: 'high', defaultExecutionMode: mode, serviceTier: 'default',
    }),
  ),
}))

vi.mock('@/api/aiAccount', () => ({
  fetchAiAccounts: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([
    {
      id: 'aa-1', provider: 'codex', label: 'CLI Proxy', email: null, enabled: true,
      authMethod: 'api_key', plan: null, quotaStatus: 'SYNCED', quotaMessage: '连接正常',
      quotaWindow: null, quotaPercent: null, quotaResetsAt: null, quotaRawJson: null,
      tokenExpiresAt: null, lastSyncedAt: null, createdAt: '2026-07-28T12:00:00',
      baseUrl: 'https://proxy.example/v1', model: 'gpt-5.4', apiProtocol: 'responses',
    },
  ]),
}))

describe('AiRunLauncher', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('极简风格收起自描述标签，详细风格恢复标签', async () => {
    const preferences = usePreferenceStore()
    const compact = mount(AiRunLauncher, { props: { projectId: 'p1' } })
    await flushPromises()

    expect(compact.find('.launcher').classes()).toContain('compact')
    expect(compact.findAll('.label')).toHaveLength(0)
    expect(compact.find('select[aria-label="执行电脑"]').exists()).toBe(true)
    expect(compact.find('.plan-workflow-mode').text()).toContain('Plan 模式')
    compact.unmount()

    preferences.controlStyle = 'detailed'
    const detailed = mount(AiRunLauncher, { props: { projectId: 'p1' } })
    await flushPromises()
    expect(detailed.find('.launcher').classes()).not.toContain('compact')
    expect(detailed.findAll('.label').some((label) => label.text() === '执行电脑')).toBe(true)
    detailed.unmount()
  })

  it('超长子任务范围不挤掉开始按钮，并保留完整标题提示', async () => {
    const scope = `子任务「${'移动端关联与执行状态'.repeat(40)}」`
    const wrapper = mount(AiRunLauncher, { props: { projectId: 'p1', scope } })
    await flushPromises()

    expect(wrapper.find('.scope').attributes('title')).toBe(`本次范围：${scope}`)
    expect(wrapper.find('footer .primary').exists()).toBe(true)
  })

  it('选择集中账号后默认改用 Linux 主节点', async () => {
    const wrapper = mount(AiRunLauncher, { props: { projectId: 'p1' } })
    await flushPromises()

    const selects = wrapper.findAll('select')
    await selects[2]!.setValue('aa-1')
    expect(wrapper.text()).toContain('自定义 API 使用账号中配置的模型')
    expect(wrapper.findAll('select')[1]!.element.value).toBe('rn-linux')
    await wrapper.find('footer .primary').trigger('click')

    expect(wrapper.text()).toContain('CLI Proxy · gpt-5.4')
    expect(wrapper.emitted('start')).toEqual([['codex', 'rn-linux', 'aa-1', null, null, true, false]])
  })

  it('使用执行电脑本地登录态时保留项目首选 Windows 电脑', async () => {
    const wrapper = mount(AiRunLauncher, { props: { projectId: 'p1' } })
    await flushPromises()

    const selects = wrapper.findAll('select')
    await selects[2]!.setValue('aa-1')
    expect(wrapper.findAll('select')[1]!.element.value).toBe('rn-linux')
    await wrapper.findAll('select')[0]!.setValue('')
    expect(wrapper.findAll('select')[3]!.element.value).toBe('rn-win')
    await wrapper.find('footer .primary').trigger('click')

    expect(wrapper.emitted('start')).toEqual([['codex', 'rn-win', null, 'gpt-5.6-sol', 'high', true, false]])
  })

  it('集中账号没有对应 Linux CLI 时明确阻止静默落到 Windows', async () => {
    const { fetchAiRunners } = await import('@/api/ai')
    vi.mocked(fetchAiRunners).mockResolvedValueOnce([
      {
        id: 'rn-win', name: '娃哥Windows电脑', platform: 'Windows 11', engines: ['codex'],
        maxConcurrency: 2, activeRuns: 0, online: true, lastSeenAt: '2026-07-28T12:00:00',
        wakeMac: null, wakeHost: null, wakePort: 9, wakeConfigured: false,
        lastWakeRequestedAt: null,
      },
    ])
    const wrapper = mount(AiRunLauncher, { props: { projectId: 'p1' } })
    await flushPromises()

    const selects = wrapper.findAll('select')
    await selects[2]!.setValue('aa-1')

    expect(wrapper.text()).toContain('Linux 主节点尚未接入 Codex')
    expect(wrapper.find('footer .primary').attributes('disabled')).toBeDefined()
  })

  it('可为本次运行选择 GPT-5.6 Sol 高推理且明确关闭 Fast', async () => {
    const wrapper = mount(AiRunLauncher, { props: { projectId: 'p1' } })
    await flushPromises()

    const selects = wrapper.findAll('select')
    await selects[0]!.setValue('gpt-5.6-sol')
    await selects[1]!.setValue('high')
    await wrapper.find('footer .primary').trigger('click')

    expect(wrapper.text()).toContain('标准速度（Fast 未开启）')
    expect(wrapper.emitted('start')).toEqual([['codex', 'rn-win', null, 'gpt-5.6-sol', 'high', true, false]])
  })

  it('允许为低复杂度任务选择低思考深度', async () => {
    const wrapper = mount(AiRunLauncher, { props: { projectId: 'p1' } })
    await flushPromises()

    await wrapper.findAll('select')[1]!.setValue('low')
    await wrapper.find('footer .primary').trigger('click')

    expect(wrapper.find('select[aria-label="推理强度"]').text()).toContain('低 · 优先速度与较低消耗')
    expect(wrapper.emitted('start')).toEqual([['codex', 'rn-win', null, 'gpt-5.6-sol', 'low', true, false]])
  })

  it('Codex 默认开启 Plan 模式，也允许切换为直接执行', async () => {
    const wrapper = mount(AiRunLauncher, { props: { projectId: 'p1' } })
    await flushPromises()

    const toggle = wrapper.find('.plan-workflow-mode')
    expect(toggle.attributes('aria-checked')).toBe('true')
    expect(toggle.text()).toContain('推荐项自动选择')
    await wrapper.find('.direct-mode').trigger('click')
    await wrapper.find('footer .primary').trigger('click')

    expect(toggle.attributes('aria-checked')).toBe('false')
    expect(wrapper.emitted('start')).toEqual([['codex', 'rn-win', null, 'gpt-5.6-sol', 'high', false, false]])
  })

  it('目标模式与 Plan 互斥，并把真实目标模式随派工提交', async () => {
    const wrapper = mount(AiRunLauncher, { props: { projectId: 'p1' } })
    await flushPromises()

    await wrapper.find('.goal-mode').trigger('click')
    expect(wrapper.find('.goal-mode').attributes('aria-checked')).toBe('true')
    expect(wrapper.find('.plan-workflow-mode').attributes('aria-checked')).toBe('false')
    await wrapper.find('footer .primary').trigger('click')

    expect(wrapper.emitted('start')).toEqual([['codex', 'rn-win', null, 'gpt-5.6-sol', 'high', false, true]])
  })

  it('读取服务端默认目标模式，并可在原位置把本次选择保存为后续默认', async () => {
    const { fetchAiRunOptions, saveAiRunDefaultExecutionMode } = await import('@/api/ai')
    vi.mocked(fetchAiRunOptions).mockResolvedValueOnce({
      codexModels: [{ id: 'gpt-5.6-sol', label: 'GPT-5.6 Sol', description: '复杂开发任务的旗舰模型' }],
      reasoningEfforts: [{ id: 'high', label: '高', description: '为复杂任务投入更多推理' }],
      defaultCodexModel: 'gpt-5.6-sol', defaultReasoningEffort: 'high',
      defaultExecutionMode: 'goal', serviceTier: 'default',
    })
    const wrapper = mount(AiRunLauncher, { props: { projectId: 'p1' } })
    await flushPromises()

    expect(wrapper.find('.goal-mode').attributes('aria-checked')).toBe('true')
    expect(wrapper.find('.default-mode-setting').text()).toContain('默认：目标模式')
    await wrapper.find('.direct-mode').trigger('click')
    expect(wrapper.find('.default-mode-setting button').text()).toContain('将直接执行设为默认')
    await wrapper.find('.default-mode-setting button').trigger('click')
    await flushPromises()

    expect(saveAiRunDefaultExecutionMode).toHaveBeenCalledWith('direct')
    expect(wrapper.find('.default-mode-setting').text()).toContain('默认：直接执行')
    expect(wrapper.find('.default-mode-setting').text()).toContain('已保存')
  })
})
