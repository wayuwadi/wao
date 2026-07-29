import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AiRunLauncher from '@/components/ai/AiRunLauncher.vue'

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
    preferredRunnerId: 'rn-win', maxParallelRuns: 2,
  }),
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
    await selects[0]!.setValue('aa-1')
    expect(selects[1]!.element.value).toBe('rn-linux')
    await wrapper.find('footer .primary').trigger('click')

    expect(wrapper.text()).toContain('CLI Proxy · gpt-5.4')
    expect(wrapper.emitted('start')).toEqual([['codex', 'rn-linux', 'aa-1']])
  })

  it('使用执行电脑本地登录态时保留项目首选 Windows 电脑', async () => {
    const wrapper = mount(AiRunLauncher, { props: { projectId: 'p1' } })
    await flushPromises()

    const selects = wrapper.findAll('select')
    await selects[0]!.setValue('aa-1')
    expect(selects[1]!.element.value).toBe('rn-linux')
    await selects[0]!.setValue('')
    expect(selects[1]!.element.value).toBe('rn-win')
    await wrapper.find('footer .primary').trigger('click')

    expect(wrapper.emitted('start')).toEqual([['codex', 'rn-win', null]])
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
    await selects[0]!.setValue('aa-1')

    expect(wrapper.text()).toContain('Linux 主节点尚未接入 Codex')
    expect(wrapper.find('footer .primary').attributes('disabled')).toBeDefined()
  })
})
