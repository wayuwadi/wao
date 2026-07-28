import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AiRunLauncher from '@/components/ai/AiRunLauncher.vue'

vi.mock('@/api/ai', () => ({
  fetchAiRunners: vi.fn<() => Promise<Array<Record<string, unknown>>>>().mockResolvedValue([
    {
      id: 'rn-1', name: 'Codex Runner', platform: 'Linux', engines: ['codex'],
      maxConcurrency: 2, activeRuns: 0, online: true, lastSeenAt: '2026-07-28T12:00:00',
    },
  ]),
  fetchAiProjectConfig: vi.fn<() => Promise<Record<string, unknown>>>().mockResolvedValue({
    projectId: 'p1', enabled: true, repoUrl: 'https://github.com/acme/wao.git',
    defaultBranch: 'main', workspaceDir: 'wao', defaultEngine: 'codex',
    preferredRunnerId: null, maxParallelRuns: 2,
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
  it('把选中的集中账号随启动事件下发', async () => {
    const wrapper = mount(AiRunLauncher, { props: { projectId: 'p1' } })
    await flushPromises()

    const selects = wrapper.findAll('select')
    await selects[0]!.setValue('aa-1')
    await wrapper.find('footer .primary').trigger('click')

    expect(wrapper.text()).toContain('CLI Proxy · gpt-5.4')
    expect(wrapper.emitted('start')).toEqual([['codex', null, 'aa-1']])
  })
})
