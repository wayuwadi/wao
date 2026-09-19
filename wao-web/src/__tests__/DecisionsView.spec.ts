import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSessionStore } from '@/stores/session'
import DecisionsView from '@/views/DecisionsView.vue'
import {
  archiveMemory,
  createMemory,
  fetchMemories,
  reviseMemory,
  type MemoryDocument,
} from '@/api/unifiedMemories'
import { formatDecisionContent, parseDecisionContent } from '@/utils/decisionContent'

vi.mock('@/stores/session', async () => { const { reactive } = await import('vue'); const session = reactive({ userId: 'member-1' }); return { useSessionStore: () => session } })
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

const mounted: ReturnType<typeof mount>[] = []
function view() {
  const wrapper = mount(DecisionsView, { global: { stubs: { RouterLink: true } } })
  mounted.push(wrapper)
  return wrapper
}
afterEach(() => { mounted.splice(0).forEach(wrapper => wrapper.unmount()); vi.restoreAllMocks() })

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
    Object.assign(useSessionStore(), { userId: 'member-1' })
    localStorage.clear()
    vi.clearAllMocks()
    vi.mocked(fetchMemories).mockResolvedValue([])
  })

  it('只读取当前账号已发布的个人决策并展示情境、选择与原因', async () => {
    vi.mocked(fetchMemories).mockResolvedValue([decision()])
    const wrapper = view()
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
    const wrapper = view()
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
    const wrapper = view()
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
    const wrapper = view()
    await flushPromises()
    await wrapper.get('.decision-card .danger').trigger('click')
    await flushPromises()

    expect(archiveMemory).toHaveBeenCalledWith('decision-1')
    expect(fetchMemories).toHaveBeenCalledTimes(2)
  })

  it('读取失败和空数据都有可恢复状态', async () => {
    vi.mocked(fetchMemories).mockRejectedValueOnce(new Error('网络不可用')).mockResolvedValueOnce([])
    const wrapper = view()
    await flushPromises()
    expect(wrapper.text()).toContain('网络不可用')
    await wrapper.get('.state-card.error button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('还没有沉淀决策')
  })

  it('新建保存期间继续输入，成功后保留新增内容并更新同一条决策', async () => {
    let finish!: (value: MemoryDocument) => void
    vi.mocked(createMemory).mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    vi.mocked(reviseMemory).mockResolvedValue(decision({ currentPublishedRevisionId: 'revision-2' }))
    const wrapper = view(); await flushPromises()
    await wrapper.get('.hero .primary').trigger('click')
    await wrapper.get('.editor input').setValue('第一版')
    await wrapper.findAll('.editor textarea')[1]!.setValue('原先的决定')
    await wrapper.get('.editor footer .primary').trigger('click')
    await wrapper.findAll('.editor textarea')[1]!.setValue('保存中补充的决定')
    expect(localStorage.getItem('wao:decision-draft:member-1:new')).toContain('保存中补充的决定')
    finish(decision()); await flushPromises()
    expect(wrapper.get('.editor').text()).toContain('上一版已保存')
    expect(localStorage.getItem('wao:decision-draft:member-1:new')).toBeNull()
    expect(localStorage.getItem('wao:decision-draft:member-1:decision-1')).toContain('保存中补充的决定')
    await wrapper.get('.editor footer .primary').trigger('click'); await flushPromises()
    expect(createMemory).toHaveBeenCalledTimes(1)
    expect(reviseMemory).toHaveBeenCalledWith('decision-1', expect.objectContaining({ baseRevisionId: 'revision-1', content: expect.stringContaining('保存中补充的决定') }))
  })

  it('保存失败仍保留请求发出后新增的输入和草稿', async () => {
    let fail!: (error: Error) => void
    vi.mocked(createMemory).mockReturnValueOnce(new Promise((_, reject) => { fail = reject }))
    const wrapper = view(); await flushPromises()
    await wrapper.get('.hero .primary').trigger('click')
    await wrapper.get('.editor input').setValue('待保存')
    await wrapper.findAll('.editor textarea')[1]!.setValue('旧内容')
    await wrapper.get('.editor footer .primary').trigger('click')
    await wrapper.findAll('.editor textarea')[1]!.setValue('最新内容')
    fail(new Error('断开连接')); await flushPromises()
    expect(wrapper.get('.save-error').text()).toContain('断开连接')
    expect(localStorage.getItem('wao:decision-draft:member-1:new')).toContain('最新内容')
  })

  it('账号切换后旧保存不清理新账号草稿，也不刷新新账号列表', async () => {
    let finish!: (value: MemoryDocument) => void
    vi.mocked(createMemory).mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    const wrapper = view(); await flushPromises()
    await wrapper.get('.hero .primary').trigger('click')
    await wrapper.get('.editor input').setValue('旧账号')
    await wrapper.findAll('.editor textarea')[1]!.setValue('旧账号决定')
    await wrapper.get('.editor footer .primary').trigger('click')
    Object.assign(useSessionStore(), { userId: 'member-2' }); await flushPromises()
    await wrapper.get('.hero .primary').trigger('click')
    await wrapper.get('.editor input').setValue('新账号输入')
    const count = vi.mocked(fetchMemories).mock.calls.length
    finish(decision()); await flushPromises()
    expect(wrapper.get('.editor input').element).toHaveProperty('value', '新账号输入')
    expect(localStorage.getItem('wao:decision-draft:member-2:new')).toContain('新账号输入')
    expect(localStorage.getItem('wao:decision-draft:member-1:new')).toContain('旧账号')
    expect(fetchMemories).toHaveBeenCalledTimes(count)
  })

  it('草稿写入失败不冒充本机已保存，可以重试', async () => {
    const wrapper = view(); await flushPromises()
    await wrapper.get('.hero .primary').trigger('click')
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    await wrapper.get('.editor input').setValue('页面中的输入')
    expect(wrapper.get('[role="alert"]').text()).toContain('本机草稿保存失败')
    storage.mockRestore()
    await wrapper.get('[role="alert"] button').trigger('click')
    expect(localStorage.getItem('wao:decision-draft:member-1:new')).toContain('页面中的输入')
  })


  it('旧账号列表回包不会显示到新账号', async () => {
    let finish!: (value: MemoryDocument[]) => void
    vi.mocked(fetchMemories).mockReturnValueOnce(new Promise(resolve => { finish = resolve })).mockResolvedValue([])
    const wrapper = view()
    Object.assign(useSessionStore(), { userId: 'member-2' }); await flushPromises()
    finish([decision()]); await flushPromises()
    expect(wrapper.text()).not.toContain('去武汉的出行方式')
  })

  it('服务端已成功但清理本机草稿失败，不误报保存失败或重复新建', async () => {
    vi.mocked(createMemory).mockResolvedValue(decision())
    const wrapper = view(); await flushPromises()
    await wrapper.get('.hero .primary').trigger('click')
    await wrapper.get('.editor input').setValue('已提交')
    await wrapper.findAll('.editor textarea')[1]!.setValue('明确决定')
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('blocked') })
    await wrapper.get('.editor footer .primary').trigger('click'); await flushPromises()
    expect(wrapper.get('.editor').text()).toContain('决策已保存')
    expect(wrapper.find('.save-error').exists()).toBe(false)
    expect(wrapper.get('.editor footer .primary').text()).toBe('保存新版本')
  })

})
