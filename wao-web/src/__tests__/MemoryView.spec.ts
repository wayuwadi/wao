import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MemoryView from '@/views/MemoryView.vue'
import { createMemory, fetchMemories, fetchMemoryRevisions, reviseMemory, type MemoryDocument, type MemoryRevision } from '@/api/unifiedMemories'

const route = { query: {} as Record<string, string> }
const replace = vi.fn()
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ replace }) }))
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({
  userId: 'wg', org: { id: 'o1', name: 'WAO' }, currentUser: { role: '超级管理员' },
}) }))
vi.mock('@/api/board', () => ({ fetchProjects: vi.fn().mockResolvedValue([]) }))
vi.mock('@/api/ai', () => ({ fetchAiRunners: vi.fn().mockResolvedValue([{
  id: 'rn1', name: '开发电脑', platform: 'linux', engines: ['codex'], maxConcurrency: 1,
  activeRuns: 0, online: true, lastSeenAt: null, wakeMac: null, wakeHost: null,
  wakePort: 9, wakeConfigured: false, lastWakeRequestedAt: null,
}]) }))
vi.mock('@/api/unifiedMemories', async () => {
  const actual = await vi.importActual<typeof import('@/api/unifiedMemories')>('@/api/unifiedMemories')
  return { ...actual, fetchMemories: vi.fn(), createMemory: vi.fn(), reviseMemory: vi.fn(),
    fetchMemoryRevisions: vi.fn(), publishMemoryRevision: vi.fn(), rejectMemoryRevision: vi.fn(),
    archiveMemory: vi.fn() }
})
const memory = (overrides: Partial<MemoryDocument> = {}): MemoryDocument => ({
  id: 'mem1', scopeType: 'personal', scopeId: 'wg', category: 'preference', state: 'active',
  currentPublishedRevisionId: 'rev1', createdBy: 'wg', createdAt: '2026-08-18T08:00:00',
  updatedAt: '2026-08-18T08:00:00', revision: {
    id: 'rev1', versionNo: 1, state: 'published', title: '晨跑偏好', content: '喜欢日出前跑步',
    authorType: 'human', createdBy: 'wg', createdAt: '2026-08-18T08:00:00', sources: [],
  }, ...overrides,
})

describe('MemoryView', () => {
  beforeEach(() => {
    localStorage.clear(); vi.clearAllMocks(); route.query = {}
    vi.mocked(fetchMemories).mockResolvedValue([])
  })

  it('默认突出个人与共享记忆，技术范围可展开', async () => {
    vi.mocked(fetchMemories).mockResolvedValue([memory()])
    const wrapper = mount(MemoryView); await flushPromises()
    expect(wrapper.text()).toContain('关于我')
    expect(wrapper.text()).toContain('组织记忆')
    expect(wrapper.text()).toContain('项目记忆')
    expect(wrapper.findAll('.scope-tabs button')).toHaveLength(3)
    await wrapper.get('.technical-toggle').trigger('click')
    expect(wrapper.findAll('.scope-tabs button')).toHaveLength(5)
    expect(wrapper.text()).toContain('机器记忆')
    expect(wrapper.text()).toContain('应用记忆')
    expect(wrapper.text()).toContain('晨跑偏好')
    expect(wrapper.text()).toContain('已生效 · v1')
  })

  it('技术范围深链保持可见并保留原查询范围', async () => {
    route.query = { scope: 'machine', machine: 'rn1' }
    const wrapper = mount(MemoryView); await flushPromises()
    expect(wrapper.findAll('.scope-tabs button')).toHaveLength(5)
    expect(wrapper.get('.technical-toggle').attributes('disabled')).toBeDefined()
    expect(fetchMemories).toHaveBeenLastCalledWith(expect.objectContaining({ scopeType: 'machine', scopeId: 'rn1' }))
  })

  it('共享记忆在提交前显示对象与审核边界，保留后端范围和补充来源', async () => {
    route.query = { scope: 'organization' }
    vi.mocked(createMemory).mockResolvedValue(memory({ scopeType: 'organization', scopeId: 'o1' }))
    const wrapper = mount(MemoryView); await flushPromises()
    await wrapper.get('.hero .primary').trigger('click')
    expect(wrapper.get('.editor header').text()).toContain('组织记忆 · WAO')
    expect(wrapper.get('.editor header').text()).toContain('管理员审核后生效')
    expect(wrapper.get('.editor footer .primary').text()).toBe('提交审核')
    expect(wrapper.get('.optional-fields').attributes('open')).toBeUndefined()
    await wrapper.get('input[maxlength="200"]').setValue('团队约定')
    await wrapper.get('textarea[maxlength="20000"]').setValue('变更先记录验收结果')
    await wrapper.get('.optional-fields textarea').setValue('https://example.com/evidence')
    await wrapper.get('.editor footer .primary').trigger('click'); await flushPromises()
    expect(createMemory).toHaveBeenCalledWith(expect.objectContaining({ scopeType: 'organization', scopeId: 'o1', evidenceLinks: ['https://example.com/evidence'] }))
  })

  it('在记忆中心内打开 Codex 连接，不再跳到设置页', async () => {
    const wrapper = mount(MemoryView, { global: { stubs: {
      MemoryMcpPanel: { template: '<div data-testid="mcp-panel">设备令牌与接入说明</div>' },
    } } })
    await flushPromises()
    await wrapper.get('.scope-note button').trigger('click')
    expect(replace).toHaveBeenLastCalledWith({ query: { section: 'mcp' } })
    expect(wrapper.get('[data-testid="mcp-panel"]').text()).toBe('设备令牌与接入说明')
    expect(wrapper.find('.scope-tabs').exists()).toBe(false)
  })

  it('Codex 连接深链不发起无关的记忆列表请求', async () => {
    route.query = { section: 'mcp' }
    const wrapper = mount(MemoryView, { global: { stubs: {
      MemoryMcpPanel: { template: '<div data-testid="mcp-panel">设备令牌与接入说明</div>' },
    } } })
    await flushPromises()
    expect(wrapper.find('[data-testid="mcp-panel"]').exists()).toBe(true)
    expect(fetchMemories).not.toHaveBeenCalled()
  })

  it('恢复本机草稿并把个人记忆立即发布', async () => {
    localStorage.setItem('wao:unified-memory-draft:personal:wg:new', JSON.stringify({
      category: 'experience', title: '未完成的经历', content: '稍后继续写', changeSummary: '', evidence: '', expiresAt: '',
    }))
    vi.mocked(createMemory).mockResolvedValue(memory())
    const wrapper = mount(MemoryView); await flushPromises()
    await wrapper.get('.hero .primary').trigger('click')
    expect(wrapper.text()).toContain('已恢复本机未保存草稿')
    await wrapper.get('.editor footer .primary').trigger('click'); await flushPromises()
    expect(createMemory).toHaveBeenCalledWith(expect.objectContaining({
      scopeType: 'personal', scopeId: 'wg', category: 'experience', title: '未完成的经历', content: '稍后继续写',
    }))
    expect(localStorage.getItem('wao:unified-memory-draft:personal:wg:new')).toBeNull()
  })

  it('另一条记忆历史读取失败时不保留旧内容，支持原位重试', async () => {
    const first = memory()
    const second = memory({ id: 'mem2', revision: { ...first.revision, id: 'rev2', title: '第二条记忆' } })
    vi.mocked(fetchMemories).mockResolvedValue([first, second])
    vi.mocked(fetchMemoryRevisions).mockResolvedValueOnce([first.revision])
      .mockRejectedValueOnce(new Error('版本服务暂不可用')).mockResolvedValueOnce([second.revision])
    const wrapper = mount(MemoryView); await flushPromises()
    await wrapper.findAll('button[title="版本历史"]')[0]!.trigger('click'); await flushPromises()
    expect(wrapper.get('.versions').text()).toContain('已发布')
    await wrapper.get('button[aria-label="关闭版本历史"]').trigger('click')
    await wrapper.findAll('button[title="版本历史"]')[1]!.trigger('click'); await flushPromises()
    expect(wrapper.get('.versions').text()).toContain('版本服务暂不可用')
    expect(wrapper.find('.versions .timeline').exists()).toBe(false)
    expect(wrapper.get('.versions').text()).not.toContain(first.revision.content)
    await wrapper.get('.version-state button').trigger('click'); await flushPromises()
    expect(fetchMemoryRevisions).toHaveBeenLastCalledWith('mem2')
    expect(wrapper.get('.versions').text()).toContain('第二条记忆')
    expect(wrapper.find('.version-state.error').exists()).toBe(false)
  })

  it('关闭再打开另一条记忆时，迟到的旧响应不能覆盖当前历史', async () => {
    let resolveOld!: (value: MemoryRevision[]) => void
    const first = memory()
    const second = memory({ id: 'mem2', revision: { ...first.revision, id: 'rev2', title: '第二条', content: '当前记录正文' } })
    vi.mocked(fetchMemories).mockResolvedValue([first, second])
    vi.mocked(fetchMemoryRevisions).mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
      .mockResolvedValueOnce([second.revision])
    const wrapper = mount(MemoryView); await flushPromises()
    await wrapper.findAll('button[title="版本历史"]')[0]!.trigger('click')
    expect(wrapper.get('.version-state').text()).toContain('正在读取')
    await wrapper.get('button[aria-label="关闭版本历史"]').trigger('click')
    await wrapper.findAll('button[title="版本历史"]')[1]!.trigger('click'); await flushPromises()
    resolveOld([first.revision]); await flushPromises()
    expect(wrapper.get('.versions').text()).toContain('当前记录正文')
    expect(wrapper.get('.versions').text()).not.toContain(first.revision.content)
  })

  it('历史成功返回空数组时展示空状态', async () => {
    vi.mocked(fetchMemories).mockResolvedValue([memory()])
    vi.mocked(fetchMemoryRevisions).mockResolvedValueOnce([])
    const wrapper = mount(MemoryView); await flushPromises()
    await wrapper.get('button[title="版本历史"]').trigger('click'); await flushPromises()
    expect(wrapper.get('.version-state').text()).toBe('暂无版本记录')
    expect(wrapper.find('.timeline').exists()).toBe(false)
  })

  it('读取失败时保留重试入口', async () => {
    vi.mocked(fetchMemories).mockRejectedValueOnce(new Error('网络暂不可用')).mockResolvedValueOnce([])
    const wrapper = mount(MemoryView); await flushPromises()
    expect(wrapper.text()).toContain('网络暂不可用')
    await wrapper.get('.state-card.error button').trigger('click'); await flushPromises()
    expect(fetchMemories).toHaveBeenCalledTimes(2)
  })

  it('409 时保留正在编辑的草稿', async () => {
    vi.mocked(fetchMemories).mockResolvedValue([memory()])
    vi.mocked(reviseMemory).mockRejectedValue(new Error('记忆已在其他设备更新（409）'))
    const wrapper = mount(MemoryView); await flushPromises()
    await wrapper.get('.memory-list article footer button').trigger('click')
    await wrapper.get('.editor textarea').setValue('本机尚未合并的正文')
    await wrapper.get('.editor footer .primary').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('记忆已在其他设备更新（409）')
    expect((wrapper.get('.editor textarea').element as HTMLTextAreaElement).value).toBe('本机尚未合并的正文')
  })
})
