import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DecisionsView from '@/views/DecisionsView.vue'
import {
  archiveMemory,
  createMemory,
  fetchMemories,
  reviseMemory,
  type MemoryDocument,
} from '@/api/unifiedMemories'
import { formatDecisionContent, parseDecisionContent } from '@/utils/decisionContent'

vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ userId: 'member-1' }) }))
vi.mock('@/api/unifiedMemories', async () => {
  const actual = await vi.importActual<typeof import('@/api/unifiedMemories')>('@/api/unifiedMemories')
  return {
    ...actual,
    fetchMemories: vi.fn<typeof actual.fetchMemories>(),
    createMemory: vi.fn<typeof actual.createMemory>(),
    reviseMemory: vi.fn<typeof actual.reviseMemory>(),
    archiveMemory: vi.fn<typeof actual.archiveMemory>(),
  }
})

const decision = (overrides: Partial<MemoryDocument> = {}): MemoryDocument => ({
  id: 'decision-1', scopeType: 'personal', scopeId: 'member-1', category: 'decision', state: 'active',
  currentPublishedRevisionId: 'revision-1', createdBy: 'member-1', createdAt: '2026-08-31T08:00:00',
  updatedAt: '2026-08-31T08:00:00', revision: {
    id: 'revision-1', versionNo: 1, state: 'published', title: '去武汉的出行方式',
    content: formatDecisionContent({
      situation: '从当前城市去武汉，行程可安排在傍晚时',
      choice: '优先坐傍晚的高铁，到站后再坐地铁',
      reason: '全程等待更少，避免消耗一整晚和第二天的精力',
    }),
    authorType: 'human', createdBy: 'member-1', createdAt: '2026-08-31T08:00:00', sources: [],
  }, ...overrides,
})

describe('decision content', () => {
  it('把结构化表单保存为记忆中心和 MCP 可读的 Markdown', () => {
    const content = formatDecisionContent({ situation: '出差去武汉', choice: '坐傍晚高铁', reason: '减少等待' })
    expect(content).toContain('## 适用情境\n出差去武汉')
    expect(parseDecisionContent(content)).toEqual({ situation: '出差去武汉', choice: '坐傍晚高铁', reason: '减少等待' })
  })

  it('兼容从记忆中心写入的自由正文', () => {
    expect(parseDecisionContent('下次直接坐高铁')).toEqual({ situation: '', choice: '下次直接坐高铁', reason: '' })
  })
})

describe('DecisionsView', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    vi.mocked(fetchMemories).mockResolvedValue([])
  })

  it('只读取当前账号已发布的个人决策并展示情境、选择与原因', async () => {
    vi.mocked(fetchMemories).mockResolvedValue([decision()])
    const wrapper = mount(DecisionsView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()

    expect(fetchMemories).toHaveBeenCalledWith({
      scopeType: 'personal', scopeId: 'member-1', category: 'decision', query: undefined,
    })
    expect(wrapper.text()).toContain('去武汉的出行方式')
    expect(wrapper.text()).toContain('优先坐傍晚的高铁')
    expect(wrapper.text()).toContain('避免消耗一整晚')
  })

  it('新增决策时写入统一记忆并在成功后清掉草稿', async () => {
    vi.mocked(createMemory).mockResolvedValue(decision())
    const wrapper = mount(DecisionsView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()
    await wrapper.get('.hero .primary').trigger('click')
    const fields = wrapper.findAll('.editor textarea')
    await wrapper.get('.editor input').setValue('去武汉的出行方式')
    await fields[0]!.setValue('傍晚可以出发时')
    await fields[1]!.setValue('优先坐高铁')
    await fields[2]!.setValue('减少等待和能量消耗')
    expect(localStorage.getItem('wao:decision-draft:member-1:new')).not.toBeNull()

    await wrapper.get('.editor footer .primary').trigger('click')
    await flushPromises()

    expect(createMemory).toHaveBeenCalledWith(expect.objectContaining({
      scopeType: 'personal', scopeId: 'member-1', category: 'decision', title: '去武汉的出行方式',
      content: expect.stringContaining('## 决定\n优先坐高铁'),
    }))
    expect(localStorage.getItem('wao:decision-draft:member-1:new')).toBeNull()
  })

  it('编辑冲突时保留表单和本机草稿，允许直接重试', async () => {
    vi.mocked(fetchMemories).mockResolvedValue([decision()])
    vi.mocked(reviseMemory).mockRejectedValue(new Error('记忆已在其他设备更新（409）'))
    const wrapper = mount(DecisionsView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()
    await wrapper.get('.decision-card footer button').trigger('click')
    const choice = wrapper.findAll('.editor textarea')[1]!
    await choice.setValue('改坐下午五点的高铁')
    await wrapper.get('.editor footer .primary').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('记忆已在其他设备更新（409）')
    expect((choice.element as HTMLTextAreaElement).value).toBe('改坐下午五点的高铁')
    expect(localStorage.getItem('wao:decision-draft:member-1:decision-1')).not.toBeNull()
  })

  it('明确确认后废止决策并重新读取列表', async () => {
    vi.mocked(fetchMemories).mockResolvedValue([decision()])
    vi.mocked(archiveMemory).mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mount(DecisionsView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()
    await wrapper.get('.decision-card .danger').trigger('click')
    await flushPromises()

    expect(archiveMemory).toHaveBeenCalledWith('decision-1')
    expect(fetchMemories).toHaveBeenCalledTimes(2)
  })

  it('读取失败和空数据都有可恢复状态', async () => {
    vi.mocked(fetchMemories).mockRejectedValueOnce(new Error('网络不可用')).mockResolvedValueOnce([])
    const wrapper = mount(DecisionsView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('网络不可用')
    await wrapper.get('.state-card.error button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('还没有沉淀决策')
  })
})
