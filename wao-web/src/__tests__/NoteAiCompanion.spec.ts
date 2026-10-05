import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import NoteAiCompanion from '@/components/notes/NoteAiCompanion.vue'
import {
  fetchCompanion,
  fetchAiResults,
  actOnArtifact,
  fetchNoteChat,
  askNoteChat,
  cancelNoteAi,
  saveCompanionMode,
} from '@/api/noteAiCompanion'
import type { Doc } from '@/types'
const ui = vi.hoisted(() => ({
  modal: null as string | null,
  modalTaskSeed: null as unknown,
  openModal: vi.fn<() => void>(),
}))
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ currentUser: { id: 'm' } }) }))
vi.mock('@/stores/ui', () => ({ useUiStore: () => ui }))
vi.mock('@/api/noteAiCompanion', () => ({
  fetchCompanion: vi.fn<(typeof import('@/api/noteAiCompanion'))['fetchCompanion']>(),
  fetchAiResults: vi.fn<(typeof import('@/api/noteAiCompanion'))['fetchAiResults']>(),
  actOnArtifact: vi.fn<(typeof import('@/api/noteAiCompanion'))['actOnArtifact']>(),
  fetchNoteChat: vi.fn<(typeof import('@/api/noteAiCompanion'))['fetchNoteChat']>(),
  askNoteChat: vi.fn<(typeof import('@/api/noteAiCompanion'))['askNoteChat']>(),
  cancelNoteAi: vi.fn<(typeof import('@/api/noteAiCompanion'))['cancelNoteAi']>(),
  saveCompanionMode: vi.fn<(typeof import('@/api/noteAiCompanion'))['saveCompanionMode']>(),
  companionError: () => '请求失败，输入保留',
}))
vi.mock('@/api/noteDiscoveries', () => ({
  fetchDiscoveryCurrent: vi
    .fn<
      () => Promise<{
        discovery: null
        automationState: 'OFF'
        lastOutcome: null
        revision: string
      }>
    >()
    .mockResolvedValue({
      discovery: null,
      automationState: 'OFF',
      lastOutcome: null,
      revision: '',
    }),
}))
const doc = {
  id: 'n',
  title: '测试小记',
  author: 'm',
  rev: 4,
  blocks: [],
  links: [],
  kbId: 'kb-notes',
} as unknown as Doc
const candidate = {
  id: 'a',
  stage: 'polish',
  sourceRev: 4,
  sourceTitle: '测试小记',
  sourceText: '原稿',
  resultText: 'AI建议',
  application: 'PENDING',
  validity: 'CURRENT',
  seen: false,
  createdAt: '2026-10-05',
  removedChars: 2,
  addedChars: 4,
}
const wrappers: ReturnType<typeof mount>[] = []
function render(activeId = 'n', blocked = false) {
  const w = mount(NoteAiCompanion, {
    props: { notes: [doc], activeId, blocked },
    global: { stubs: { NoteDiscoveryInline: true, NoteDiscoveryPreference: true } },
  })
  wrappers.push(w)
  return w
}
beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  ui.modal = null
  ui.modalTaskSeed = null
  ui.openModal.mockImplementation(() => {
    ui.modal = 'task'
  })
  vi.mocked(fetchCompanion).mockResolvedValue({
    available: true,
    message: '真实执行器就绪',
    mode: 'AUTO',
    version: 0,
    queued: 0,
    running: 0,
    unread: 1,
    capabilityVersion: 1,
  })
  vi.mocked(fetchAiResults).mockResolvedValue({
    items: [{ ...candidate } as never],
    nextCursor: '',
    attempts: [],
  })
  vi.mocked(actOnArtifact).mockResolvedValue(doc)
  vi.mocked(fetchNoteChat).mockResolvedValue({ messages: [], sources: [], runs: [] })
  vi.mocked(saveCompanionMode).mockResolvedValue({ mode: 'REVIEW', version: 1 })
})
afterEach(() => {
  wrappers.forEach((w) => w.unmount())
  wrappers.length = 0
})

it('折叠不消费未读，展开保留原问题和栏目，后台新成果不自动展开', async () => {
  const w = mount(NoteAiCompanion, { props: { notes: [doc], activeId: 'n', blocked: false, explicitlySelected: true, collapsed: true } })
  wrappers.push(w)
  await flushPromises()
  expect(actOnArtifact).not.toHaveBeenCalled()
  expect(w.find('.companion-dock').text()).toContain('1 项新成果')
  await w.get('[aria-label="展开 AI 伴读"]').trigger('click')
  expect(w.emitted('collapse')?.[0]).toEqual([false])
  await w.setProps({ collapsed: false }); await flushPromises()
  expect(actOnArtifact).toHaveBeenCalledWith('a', 'seen')
  await w.findAll('nav button')[2]!.trigger('click')
  await flushPromises()
  const input = w.get('textarea')
  await input.setValue('我的问题草稿')
  await w.setProps({ collapsed: true })
  expect(input.isVisible()).toBe(false)
  await w.setProps({ collapsed: false })
  expect(w.get('textarea').element).toBe(input.element)
  expect((input.element as HTMLTextAreaElement).value).toBe('我的问题草稿')
  expect(w.findAll('nav button')[2]!.attributes('aria-pressed')).toBe('true')
})

it('阅读浮层独立展开，不覆盖桌面折叠偏好', async () => {
  const w = mount(NoteAiCompanion, { props: { notes: [doc], activeId: 'n', blocked: false }, global: { stubs: { Teleport: true } } })
  wrappers.push(w)
  await w.setProps({ collapsed: true }); await flushPromises()
  await w.setProps({ overlayOpen: true }); await flushPromises()
  expect(w.find('.reader-overlay').exists()).toBe(true)
  expect(w.find('.companion-dock').exists()).toBe(false)
  expect(w.find('.companion-body').isVisible()).toBe(true)
  await w.setProps({ overlayOpen: false })
  expect(w.find('.companion-dock').exists()).toBe(true)
  expect(w.emitted('collapse')).toBeUndefined()
})
async function button(w: ReturnType<typeof render>, text: string) {
  const b = w.findAll('button').find((b) => b.text() === text)
  expect(b).toBeDefined()
  await b!.trigger('click')
  await flushPromises()
}
describe('私人小记伴读', () => {
  it('进入页面没有选择小记，不消费未读成果', async () => {
    const w = render('')
    await flushPromises()
    expect(fetchAiResults).not.toHaveBeenCalled()
    expect(actOnArtifact).not.toHaveBeenCalled()
    expect(w.text()).toContain('从右侧选一篇')
  })
  it('打开真实对照才标为已读，采用携带当前文档版本', async () => {
    const w = render()
    await flushPromises()
    expect(w.text()).toContain('原稿')
    expect(w.text()).toContain('AI建议')
    await button(w, '采用正文')
    expect(actOnArtifact).toHaveBeenCalledWith('a', 'apply', 4)
    expect(w.emitted('updated')?.[0]).toEqual([doc])
  })
  it('草稿阻止采用、重做与提问，不覆盖未保存内容', async () => {
    const w = render('n', true)
    await flushPromises()
    expect(
      w
        .findAll('button')
        .find((b) => b.text() === '采用正文')
        ?.attributes('disabled'),
    ).toBeDefined()
    await button(w, '继续聊')
    await w.get('textarea').setValue('新问题')
    expect(
      w
        .findAll('button')
        .find((b) => b.text() === '发送')
        ?.attributes('disabled'),
    ).toBeDefined()
    expect(askNoteChat).not.toHaveBeenCalled()
  })
  it('来源过期不能采用，仍能看到历史成果', async () => {
    vi.mocked(fetchAiResults).mockResolvedValue({
      items: [{ ...candidate, validity: 'STALE' } as never],
      nextCursor: '',
      attempts: [],
    })
    const w = render()
    await flushPromises()
    expect(w.text()).toContain('来源已更新')
    expect(
      w
        .findAll('button')
        .find((b) => b.text() === '采用正文')
        ?.attributes('disabled'),
    ).toBeDefined()
  })
  it('取消正在运行的阶段不假装已完成', async () => {
    vi.mocked(fetchAiResults).mockResolvedValue({
      items: [],
      nextCursor: '',
      attempts: [
        {
          id: 'x',
          stage: 'image',
          state: 'RUNNING',
          message: '执行中',
          cancelling: false,
          reasonCode: '',
          createdAt: '',
          updatedAt: '',
        },
      ],
    })
    const w = render()
    await flushPromises()
    await button(w, '停止本篇整理')
    expect(cancelNoteAi).toHaveBeenCalledWith('n', 4)
    expect(w.text()).toContain('任务正在队列中推进')
  })
  it('失败时问题保护到成员和篇专属草稿；重试沿用请求号', async () => {
    vi.mocked(askNoteChat).mockRejectedValue(new Error('offline'))
    const w = render()
    await flushPromises()
    await button(w, '继续聊')
    await w.get('textarea').setValue('这个数字准确吗？')
    await w.get('form').trigger('submit')
    await flushPromises()
    const first = vi.mocked(askNoteChat).mock.calls[0]
    await w.get('form').trigger('submit')
    await flushPromises()
    expect(vi.mocked(askNoteChat).mock.calls[1]?.[3]).toBe(first?.[3])
    expect(localStorage.getItem('wao-note-companion-question:m:n')).toBe('这个数字准确吗？')
    expect(w.get('textarea').element.value).toBe('这个数字准确吗？')
  })
  it('先看建议需要显式点击并使用设置 CAS', async () => {
    const w = render()
    await flushPromises()
    await w.get('[aria-label="伴读设置"]').trigger('click')
    await button(w, '先看建议')
    expect(saveCompanionMode).toHaveBeenCalledWith('REVIEW', 0)
  })
  it('提交结果不明时重开仍复用请求号与所选来源', async () => {
    vi.mocked(askNoteChat).mockRejectedValue(new Error('offline'))
    const w = render()
    await flushPromises()
    await button(w, '继续聊')
    await w.get('textarea').setValue('关闭后重试')
    await w.get('form').trigger('submit')
    await flushPromises()
    const id = vi.mocked(askNoteChat).mock.calls[0]?.[3]
    w.unmount()
    const reopened = render()
    await flushPromises()
    await button(reopened, '继续聊')
    expect(reopened.get('textarea').element.value).toBe('关闭后重试')
    await reopened.get('form').trigger('submit')
    await flushPromises()
    expect(vi.mocked(askNoteChat).mock.calls[1]?.[3]).toBe(id)
  })
  it('自动选中新建小记不消费成果，明确打开才标记', async () => {
    const w = render()
    await w.setProps({ explicitlySelected: false })
    await flushPromises()
    vi.mocked(actOnArtifact).mockClear()
    await w.setProps({ activeId: '' })
    await w.setProps({ activeId: 'n' })
    await flushPromises()
    expect(actOnArtifact).not.toHaveBeenCalled()
    await w.setProps({ explicitlySelected: true })
    await flushPromises()
    expect(actOnArtifact).toHaveBeenCalledWith('a', 'seen')
  })
  it('回答转待办打开已有草稿表单，不静默创建任务或截止日', async () => {
    vi.mocked(fetchNoteChat).mockResolvedValue({
      messages: [
        { id: 'message', role: 'assistant', content: '建议核对数字 [来源1]', createdAt: '' },
      ],
      sources: [{ id: 'n', title: '测试小记', rev: 4 }],
      runs: [],
    })
    const w = render()
    await flushPromises()
    await button(w, '继续聊')
    await button(w, '整理成待办草稿')
    await w.get('[aria-label="待办草稿标题"]').setValue('核对数字')
    await button(w, '打开待办草稿')
    expect(ui.openModal).toHaveBeenCalledWith('task')
    expect(ui.modalTaskSeed).toMatchObject({
      title: '核对数字',
      sourceDocId: 'n',
      desc: '建议核对数字 [来源1]',
    })
  })
})
