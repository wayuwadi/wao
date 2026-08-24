import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MemoryView from '@/views/MemoryView.vue'
import { createMemory, fetchMemories, reviseMemory, type MemoryDocument } from '@/api/unifiedMemories'

const route = { query: {} as Record<string, string> }
const replace = vi.fn()
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ replace }) }))
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({
  userId: 'wg', org: { id: 'o1', name: 'WAO' }, currentUser: { role: '超级管理员' },
}) }))
vi.mock('@/api/board', () => ({ fetchProjects: vi.fn().mockResolvedValue([]) }))
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

  it('展示三种隔离范围和正式个人记忆', async () => {
    vi.mocked(fetchMemories).mockResolvedValue([memory()])
    const wrapper = mount(MemoryView); await flushPromises()
    expect(wrapper.text()).toContain('个人记忆')
    expect(wrapper.text()).toContain('组织记忆')
    expect(wrapper.text()).toContain('项目记忆')
    expect(wrapper.text()).toContain('晨跑偏好')
    expect(wrapper.text()).toContain('正式版 v1')
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
