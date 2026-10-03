import { readNoteDraft } from '@/utils/noteDraftStorage'
import { defineComponent } from 'vue'
import { DOMWrapper, flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import NotesView from '@/views/NotesView.vue'
import NoteComposer from '@/components/notes/NoteComposer.vue'
import MarkdownEditor from '@/components/ui/MarkdownEditor.vue'
import { polishDocumentText } from '@/api/assistant'
import type { Editor as TiptapEditor } from '@tiptap/core'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { usePreferenceStore } from '@/stores/preferences'
import { useUiStore } from '@/stores/ui'
import { apiCreateNoteTag, fetchNoteTags, apiPatchDoc, fetchDoc, fetchKbs } from '@/api/board'
import type { Doc } from '@/types'
import { fetchPreference, savePreference } from '@/api/favorites'
import { copyText } from '@/utils/clipboard'
import { fetchNotePolishVersions } from '@/api/notePolishVersions'
import { notePolishVersions, noteEnrichmentStatus, activateNoteWorkflowMember } from '@/utils/autoNoteEnrichment'
import { fetchNoteAiWorkflows } from '@/api/noteAiWorkflows'
vi.mock('@/api/noteAiWorkflows', () => ({ fetchNoteAiWorkflows: vi.fn().mockResolvedValue([]), retryNoteAiWorkflow: vi.fn() }))
vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn().mockResolvedValue('[]'), savePreference: vi.fn().mockResolvedValue(undefined) }))
vi.mock('@/utils/clipboard', () => ({ copyText: vi.fn().mockResolvedValue(undefined) }))
vi.mock('@/api/notePolishVersions', () => ({ fetchNotePolishVersions: vi.fn().mockResolvedValue([]), saveNotePolishVersion: vi.fn() }))

vi.mock('@/api/assistant', () => ({ polishDocumentText: vi.fn() }))
vi.mock('@/api/board', async importOriginal => ({ ...await importOriginal<object>(), apiPatchDoc: vi.fn(), fetchDoc: vi.fn(), fetchNoteTags: vi.fn().mockResolvedValue([]), apiCreateNoteTag: vi.fn(), fetchKbs: vi.fn() }))
const Editor = defineComponent({
  props: ['modelValue', 'readonly'], emits: ['update:modelValue'],
  template: '<div><slot name="toolbar-options" /><textarea :value="modelValue" :readonly="readonly" @input="$emit(\'update:modelValue\', $event.target.value)" /><slot name="footer-tags" /></div>',
})
function doc(id: string, text: string, created = '2026-09-07T08:00:00', author = 'wg'): Doc {
  return { id, title: text, author, created, updated: created, kbId: 'kb-notes', rev: 3,
    folderId: 'f', blocks: [{ p: text }], links: [], backlinks: [] }
}
async function openFilters(wrapper: Awaited<ReturnType<typeof view>>) {
  await wrapper.get('.filter-toggle').trigger('click')
}
function deletePanel(id = 'one') { return new DOMWrapper(document.body).find(`[data-delete-note-id="${id}"]`) }
async function requestDelete(wrapper: Awaited<ReturnType<typeof view>>, id: string) {
  await wrapper.get(`[data-note-id="${id}"]`).trigger('contextmenu')
  await flushPromises()
  const action = [...document.body.querySelectorAll('[role="menu"] button')].find(button => button.textContent?.includes('移入回收站'))
  action?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flushPromises()
}
async function view(attachTo?: HTMLElement) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/notes', component: NotesView }, { path: '/kb/:id', component: { template: '<div />' } }] })
  await router.push('/notes')
  return mount(NotesView, { attachTo, global: { plugins: [router], stubs: { MarkdownEditor: Editor, NoteMiningPanel: true } } })
}
async function setNoteBodyHeight(wrapper: Awaited<ReturnType<typeof view>>, id: string, height: number) {
  const body = wrapper.get(`[data-note-id="${id}"] .note-body`).element
  Object.defineProperty(body, 'scrollHeight', { configurable: true, value: height })
  window.dispatchEvent(new Event('resize'))
  await flushPromises()
}
function composer(note?: Doc) {
  return mount(NoteComposer, { props: { note }, global: { stubs: { MarkdownEditor: Editor } } })
}
describe('小记集中记录空间', () => {
  it('收起按钮紧随今日统计，宽度键盘调整后重新打开仍恢复且不丢草稿', async () => {
    const wrapper = await view(document.body)
    expect(wrapper.get('.daily-note-count').element.nextElementSibling).toBe(wrapper.get('.writing-toggle').element)
    const editor = wrapper.get('textarea')
    await editor.setValue('调整宽度时保护输入')
    const divider = wrapper.get('.writing-resizer')
    expect(divider.attributes('aria-valuenow')).toBe('33')
    await divider.trigger('keydown', { key: 'ArrowRight', shiftKey: true })
    expect(divider.attributes('aria-valuenow')).toBe('38')
    expect(wrapper.get('textarea').element).toBe(editor.element)
    wrapper.unmount()
    const restored = await view(document.body)
    expect(restored.get('.writing-resizer').attributes('aria-valuenow')).toBe('38')
    expect((restored.get('textarea').element as HTMLTextAreaElement).value).toBe('调整宽度时保护输入')
    await restored.get('.writing-resizer').trigger('dblclick')
    expect(restored.get('.writing-resizer').attributes('aria-valuenow')).toBe('33')
    restored.unmount()
  })
  it('拖拽结束保存比例，收起终止拖拽，异常存储回到默认宽度', async () => {
    localStorage.setItem('wao-notes-writing-width-v1', 'broken')
    vi.spyOn(window, 'innerWidth', 'get').mockReturnValue(1600)
    const wrapper = await view(document.body)
    vi.spyOn(wrapper.get('.notes-workspace').element, 'getBoundingClientRect').mockReturnValue({ left: 100, width: 1000 } as DOMRect)
    expect(wrapper.get('.writing-resizer').attributes('aria-valuenow')).toBe('33')
    const down = new MouseEvent('pointerdown', { button: 0, clientX: 443, bubbles: true, cancelable: true })
    Object.defineProperty(down, 'pointerId', { value: 7 })
    wrapper.get('.writing-resizer').element.dispatchEvent(down)
    const move = new Event('pointermove')
    Object.assign(move, { pointerId: 7, clientX: 610 })
    window.dispatchEvent(move)
    await flushPromises()
    expect(wrapper.get('.writing-resizer').attributes('aria-valuenow')).toBe('50')
    expect(localStorage.getItem('wao-notes-writing-width-v1')).toBe('broken')
    await wrapper.get('.writing-toggle').trigger('click')
    expect(localStorage.getItem('wao-notes-writing-width-v1')).toBe('0.5')
    Object.assign(move, { clientX: 990 })
    window.dispatchEvent(move)
    await flushPromises()
    expect(wrapper.get('.writing-resizer').attributes('aria-valuenow')).toBe('50')
    wrapper.unmount()
  })
  it('收起记录区保留编辑器和输入，恢复后继续原草稿', async () => {
    const wrapper = await view(document.body)
    const editor = wrapper.find('textarea')
    await editor.setValue('空间调整验收：收起后继续写')
    await wrapper.get('.writing-toggle').trigger('click')
    expect(wrapper.get('#notes-writing-pane').isVisible()).toBe(false)
    expect(wrapper.get('.writing-toggle').attributes('aria-expanded')).toBe('false')
    await wrapper.get('.writing-toggle').trigger('click')
    expect(wrapper.find('textarea').element).toBe(editor.element)
    expect((editor.element as HTMLTextAreaElement).value).toBe('空间调整验收：收起后继续写')
    expect(wrapper.get('#notes-writing-pane').isVisible()).toBe(true)
    wrapper.unmount()
  })
  it.each(['server', 'browser'] as const)('%s 历史失败保留超时原因并指向本机 Codex', async source => {
    useDataStore().docs.one = doc('one', '合成历史错误验收')
    const message = '只读 AI 任务排队超时，请检查本地 Runner 后重试'
    if (source === 'server') vi.mocked(fetchNoteAiWorkflows).mockResolvedValue([
      { docId: 'one', title: 'FAILED', image: 'SKIPPED', polish: 'SKIPPED', message },
    ])
    const wrapper = await view()
    await flushPromises()
    if (source === 'browser') { noteEnrichmentStatus.one = message; await flushPromises() }
    const status = wrapper.get('[data-note-id="one"] .workflow-status').text()
    expect(status).toContain('排队超时')
    expect(status).toContain('本机 Codex')
    expect(status).not.toContain('Runner')
    delete noteEnrichmentStatus.one
    wrapper.unmount()
  })
  it('旧浏览器完成记录不能覆盖服务端的真实跳过状态', async () => {
    useDataStore().docs.one = doc('one', '合成手动生成验收')
    localStorage.setItem('wao-note-enrichment-complete-v1:wg:one', JSON.stringify({ title: 'done', image: 'done', polish: 'done', tags: 'skipped' }))
    vi.mocked(fetchNoteAiWorkflows).mockResolvedValue([{ docId: 'one', title: 'SKIPPED', image: 'SKIPPED', polish: 'SKIPPED', message: null }])
    const wrapper = await view()
    await flushPromises()
    for (const label of ['标题', '配图', '润色']) {
      expect(wrapper.find(`[data-note-id="one"] button[aria-label="${label}未生成，点击开始"]`).exists()).toBe(true)
    }
    wrapper.unmount()
  })
  it('旧完成标记不能掩盖服务端新一轮等待、执行或失败', async () => {
    useDataStore().docs.one = doc('one', '合成新一轮验收')
    localStorage.setItem('wao-note-enrichment-complete-v1:wg:one', JSON.stringify({ title: 'done', image: 'done', polish: 'done', tags: 'skipped' }))
    vi.mocked(fetchNoteAiWorkflows).mockResolvedValue([{ docId: 'one', title: 'PENDING', image: 'RUNNING', polish: 'FAILED', message: '润色失败，请重试' }])
    const wrapper = await view()
    await flushPromises()
    expect(wrapper.find('[data-note-id="one"] button[aria-label="标题等待后台处理"]').exists()).toBe(true)
    expect(wrapper.find('[data-note-id="one"] button[aria-label="配图正在处理"]').exists()).toBe(true)
    expect(wrapper.find('[data-note-id="one"] button[aria-label="润色失败，点击重试"]').exists()).toBe(true)
    wrapper.unmount()
  })
  it('正文片段定位保留新建草稿，清除查找恢复分页和列表位置', async () => {
    const data = useDataStore()
    for (let i = 0; i < 16; i++) data.docs[`search-${i}`] = { ...doc(`search-${i}`, `独特正文桂花${i}`), title: `独立标题${i}` }
    const w = await view()
    await w.find('textarea').setValue('正在写的草稿')
    await w.get('.load-more').trigger('click')
    const list = w.get('.notes-list').element as HTMLElement
    Object.defineProperty(list, 'scrollTop', { value: 550, writable: true })
    const scroll = vi.fn(); list.scrollTo = scroll
    await w.get('[aria-label="搜索小记内容"]').setValue('独特正文桂花7')
    expect(w.get('.note-search-hit').text()).toContain('正文命中')
    await w.get('.note-search-hit').trigger('click')
    expect((w.find('textarea').element as HTMLTextAreaElement).value).toBe('正在写的草稿')
    await w.get('[aria-label="清除小记查找"]').trigger('click'); await flushPromises()
    expect(w.findAll('.note-card')).toHaveLength(16)
    expect(scroll).toHaveBeenLastCalledWith({ top: 550 })
    w.unmount()
  })
  it('默认收起 AI 挖掘，并从阅读模式旁的按钮打开弹层', async () => {
    useDataStore().docs.one = doc('one', '给工具栏留出空间')
    const wrapper = await view()
    document.body.appendChild(wrapper.element)
    expect(document.body.querySelector('[role="dialog"][aria-label="AI 挖掘小记"]')).toBeNull()
    const readerButton = wrapper.get('.reader-toggle:not(.mining-toggle)')
    const miningButton = wrapper.get('.mining-toggle')
    expect(readerButton.element.nextElementSibling).toBe(miningButton.element)
    await miningButton.trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[role="dialog"][aria-label="AI 挖掘小记"]')).not.toBeNull()
    expect(document.activeElement).toBe(document.body.querySelector('[role="dialog"][aria-label="AI 挖掘小记"]'))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(document.body.querySelector('[role="dialog"][aria-label="AI 挖掘小记"]')).toBeNull()
    expect(document.activeElement).toBe(miningButton.element)
    wrapper.unmount()
  })

  it('卡片右键命中当前小记，删除仍需在原确认区确认', async () => {
    useDataStore().docs.one = doc('one', '右键目标小记')
    const wrapper = await view()
    await wrapper.get('[data-note-id="one"]').trigger('contextmenu', { clientX: 200, clientY: 120 })
    await flushPromises()
    const menu = document.body.querySelector<HTMLElement>('[role="menu"][aria-label="小记：右键目标小记的操作"]')
    expect(menu).not.toBeNull()
    const link = [...(menu?.querySelectorAll('button') ?? [])].find(button => button.textContent?.includes('复制分享链接'))
    link?.click()
    await flushPromises()
    expect(copyText).toHaveBeenCalledWith(`${window.location.origin}/s/d/one`, '公开分享链接已复制')
    await wrapper.get('[data-note-id="one"]').trigger('contextmenu')
    await flushPromises()
    const deleteAction = [...(document.body.querySelectorAll('[role="menu"] button'))].find(button => button.textContent?.includes('移入回收站'))
    deleteAction?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    expect(deletePanel().text()).toContain('7 天内可恢复')
    expect(useDataStore().docs.one).toBeDefined()
    wrapper.unmount()
  })
  it('小记页显示当前成员今天新建的小记正文字符数', async () => {
    const data = useDataStore()
    const created = new Date().toISOString()
    data.docs.today = doc('today', '正文 ABC', created)
    data.docs.other = doc('other', '不计入', created, 'other')
    const wrapper = await view()
    expect(wrapper.get('.daily-note-count').text()).toContain('今日写字 5 字')
    wrapper.unmount()
  })
  it('从小记卡片直接复制免登录只读分享页链接', async () => {
    useDataStore().docs.old = doc('old', '要分享的小记')
    const wrapper = await view()
    const share = wrapper.findAll('[data-note-id="old"] .card-quick-actions button').find(button => button.attributes('aria-label') === '复制分享链接')
    expect(share).toBeDefined()
    await share!.trigger('click')
    expect(copyText).toHaveBeenCalledWith(`${window.location.origin}/s/d/old`, '公开分享链接已复制')
    wrapper.unmount()
  })
  it('新建页面不显示逐篇 AI 选项，保存后按成员默认能力自动整理', async () => {
    const data = useDataStore()
    const preferences = usePreferenceStore()
    const add = vi.spyOn(data, 'addNote').mockImplementation(async (text, _title, id) => {
      data.docs[id!] = doc(id!, text)
      return id!
    })
    const first = composer()
    expect(first.find('.auto-enrichment-choices').exists()).toBe(false)
    expect(preferences.noteAiDefaults).toEqual({ title: true, polish: true, image: true, executionMode: 'parallel', maxConcurrent: 3 })
    await first.find('textarea').setValue('按默认 AI 能力整理')
    await first.get('.save-note').trigger('click')
    await flushPromises()
    expect(add).toHaveBeenCalledOnce()
    expect(add.mock.calls[0]![5]).toBeUndefined() // 保存策略由服务端读取已保存偏好
    expect(localStorage.getItem(`wao-note-auto-enrichment-v1:wg:${add.mock.calls[0]![2]}`)).toBeNull()
    expect(readNoteDraft('wao-quick-note-draft-v1:wg')).toBeNull()
    await first.find('textarea').setValue('下一篇沿用默认 AI 能力')
    const savedDraft = JSON.parse(readNoteDraft('wao-quick-note-draft-v1:wg')!)
    expect(savedDraft).not.toHaveProperty('autoTitle')
    expect(savedDraft).not.toHaveProperty('autoPolish')
    expect(savedDraft).not.toHaveProperty('autoImage')
    first.unmount()
  })
  it('分享图标右侧的红色删除入口需要二次确认，取消不会删除', async () => {
    const data = useDataStore()
    data.docs.one = doc('one', '待删除小记')
    data.apiLive = true
    const remove = vi.spyOn(data, 'deleteDoc').mockImplementation(async id => { delete data.docs[id] })
    const wrapper = await view()
    const actions = wrapper.get('[data-note-id="one"] .card-quick-actions')
    const buttons = actions.findAll('button').map(button => button.attributes('aria-label'))
    expect(buttons.indexOf('删除小记：待删除小记')).toBe(buttons.indexOf('复制分享链接') + 1)
    const trigger = actions.get('.card-delete-icon')
    await trigger.trigger('click')
    expect(remove).not.toHaveBeenCalled()
    expect(deletePanel().text()).toContain('7 天内可恢复')
    await deletePanel().get('button').trigger('click')
    expect(remove).not.toHaveBeenCalled()
    await trigger.trigger('click')
    await deletePanel().get('.card-delete-submit').trigger('click')
    await flushPromises()
    expect(remove).toHaveBeenCalledWith('one')
    expect(wrapper.find('[data-note-id="one"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('折叠卡片的删除确认锚定按钮并脱离卡片裁切，Esc 返回按钮焦点', async () => {
    useDataStore().docs.one = doc('one', '很长的小记')
    const wrapper = await view()
    document.body.appendChild(wrapper.element)
    await setNoteBodyHeight(wrapper, 'one', 400)
    const card = wrapper.get('[data-note-id="one"]')
    const trigger = card.get('.card-delete-icon')
    vi.spyOn(trigger.element, 'getBoundingClientRect').mockReturnValue({
      left: 800, right: 824, top: 100, bottom: 124, width: 24, height: 24, x: 800, y: 100, toJSON: () => ({}),
    })
    await trigger.trigger('click')
    await flushPromises()
    const panel = deletePanel()
    expect(card.classes()).toContain('fixed-height')
    expect(card.find('.card-delete-confirm').exists()).toBe(false)
    expect(panel.element.parentElement).toBe(document.body)
    expect(panel.attributes('style')).toContain('left: 504px')
    expect(panel.attributes('style')).toContain('top: 132px')
    expect(panel.attributes('style')).not.toContain('visibility: hidden')
    expect(panel.text()).toContain('7 天内可恢复')
    vi.spyOn(panel.element, 'getBoundingClientRect').mockReturnValue({
      left: 504, right: 824, top: 132, bottom: 312, width: 320, height: 180, x: 504, y: 132, toJSON: () => ({}),
    })
    vi.mocked(trigger.element.getBoundingClientRect).mockReturnValue({
      left: 800, right: 824, top: 680, bottom: 704, width: 24, height: 24, x: 800, y: 680, toJSON: () => ({}),
    })
    window.dispatchEvent(new Event('resize'))
    await flushPromises()
    expect(panel.attributes('style')).toContain('top: 492px')
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(deletePanel().exists()).toBe(false)
    expect(document.activeElement).toBe(trigger.element)
    wrapper.unmount()
  })
  it('置顶跨次加载恢复，保存失败保留原排序和编辑草稿', async () => {
    const data = useDataStore()
    data.docs.old = doc('old', '较早小记', '2026-09-01T08:00:00')
    data.docs.new = doc('new', '较新小记', '2026-09-02T08:00:00')
    vi.mocked(fetchPreference).mockResolvedValueOnce('["old"]')
    const wrapper = await view()
    await flushPromises()
    expect(wrapper.find('.note-card').attributes('data-note-id')).toBe('old')
    await wrapper.find('textarea').setValue('未保存草稿')
    vi.mocked(savePreference).mockRejectedValueOnce(new Error('网络错误'))
    await wrapper.find('[data-note-id="old"] .card-quick-actions button[aria-pressed]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('置顶保存失败')
    expect(wrapper.find('.note-card').attributes('data-note-id')).toBe('old')
    expect(wrapper.find<HTMLTextAreaElement>('textarea').element.value).toBe('未保存草稿')
    await wrapper.find('[data-note-id="old"] .card-quick-actions button[aria-pressed]').trigger('click')
    await flushPromises()
    expect(savePreference).toHaveBeenLastCalledWith('note-pins-v1', '[]')
    expect(wrapper.find('.note-card').attributes('data-note-id')).toBe('new')
    wrapper.unmount()
  })
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.mocked(fetchNoteAiWorkflows).mockResolvedValue([])
    activateNoteWorkflowMember('reset')
    activateNoteWorkflowMember('wg')
    vi.mocked(copyText).mockClear()
    vi.mocked(apiPatchDoc).mockReset()
    vi.mocked(fetchDoc).mockImplementation(async id => useDataStore().docById(id)!)
    vi.mocked(fetchNoteTags).mockResolvedValue([])
    vi.mocked(fetchKbs).mockReset()
    vi.mocked(apiCreateNoteTag).mockReset()
    vi.mocked(fetchNotePolishVersions).mockResolvedValue([])
    localStorage.clear()
    sessionStorage.clear()
    setActivePinia(createPinia())
    useSessionStore().accept({ member: { id: 'wg', name: '娃哥', initial: '哥', color: '#4358d6', role: '成员' } } as never)
    const data = useDataStore()
    data.apiLive = true
    data.loadStates.docs = 'loaded'
    data.loadStates.knowledge = 'loaded'
    data.kbs = [{ id: 'kb-notes', name: '小记', notes: true, icon: '', color: '', desc: '', project: null, folders: [{ id: 'f', name: '小记', docs: [] }] }]
    vi.mocked(fetchKbs).mockResolvedValue(data.kbs)
  })

  it('刷新清单时只有刷新按钮转圈，正文和草稿保持可见', async () => {
    const data = useDataStore()
    data.docs.one = doc('one', '已有小记')
    let finish!: (value: boolean) => void
    vi.spyOn(data, 'refreshDocs').mockImplementation(() => {
      data.loadStates.docs = 'loading'
      return new Promise(resolve => { finish = resolve })
    })
    const wrapper = await view()
    await wrapper.find('textarea').setValue('未保存的输入')
    await wrapper.get('[aria-label="刷新小记清单"]').trigger('click')
    expect(wrapper.get('[aria-label="刷新小记清单"]').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('[aria-label="刷新小记清单"] i').classes()).toContain('spin')
    expect(wrapper.find('[role="status"].empty').exists()).toBe(false)
    expect(wrapper.get('[data-note-id="one"]').text()).toContain('已有小记')
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('未保存的输入')
    data.loadStates.docs = 'loaded'
    finish(true)
    await flushPromises()
    expect(wrapper.get('[aria-label="刷新小记清单"]').attributes('aria-busy')).toBe('false')
    wrapper.unmount()
  })

  it('清单删除失败保留小记和新建草稿，重试成功后移出清单', async () => {
    const data = useDataStore()
    data.docs.one = doc('one', '待删小记')
    const remove = vi.spyOn(data, 'deleteDoc').mockRejectedValueOnce(new Error('暂时失败'))
      .mockImplementationOnce(async id => { delete data.docs[id] })
    const wrapper = await view()
    await wrapper.find('textarea').setValue('正在写的新小记')
    const browserConfirm = vi.spyOn(window, 'confirm')
    await requestDelete(wrapper, 'one')
    expect(remove).not.toHaveBeenCalled()
    expect(deletePanel().text()).toContain('7 天内可恢复')
    await deletePanel().get('button').trigger('click')
    expect(deletePanel().exists()).toBe(false)
    expect(remove).not.toHaveBeenCalled()
    await requestDelete(wrapper, 'one')
    await deletePanel().get('.card-delete-submit').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-note-id="one"]').exists()).toBe(true)
    expect(deletePanel().get('[role="alert"]').text()).toContain('小记未改变')
    expect(deletePanel().exists()).toBe(true)
    await deletePanel().get('.card-delete-submit').trigger('click')
    await flushPromises()
    expect(remove).toHaveBeenCalledTimes(2)
    expect(browserConfirm).not.toHaveBeenCalled()
    expect(wrapper.find('[data-note-id="one"]').exists()).toBe(false)
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('正在写的新小记')
    wrapper.unmount()
  })

  it('每批展示十条，筛选和保存恢复首批且保留编辑草稿', async () => {
    const data = useDataStore()
    for (let i = 0; i < 24; i++) {
      const id = String(i).padStart(2, '0')
      data.docs[id] = doc(id, `记录 ${id}`)
    }
    const wrapper = await view()
    await wrapper.find('textarea').setValue('翻页不丢的草稿')
    expect(wrapper.findAll('.note-card')).toHaveLength(10)
    expect(wrapper.find('.note-card').attributes('data-note-id')).toBe('23')
    await wrapper.find('.load-more').trigger('click')
    expect(wrapper.findAll('.note-card')).toHaveLength(20)
    await wrapper.find('.load-more').trigger('click')
    expect(wrapper.findAll('.note-card')).toHaveLength(24)
    expect(wrapper.find('.load-more').exists()).toBe(false)
    await openFilters(wrapper)
    await wrapper.find('[aria-label="小记排序"]').setValue('createdDesc')
    expect(wrapper.findAll('.note-card')).toHaveLength(10)
    await wrapper.find('input[type="search"]').setValue('记录 00')
    expect(wrapper.findAll('.note-card')).toHaveLength(1)
    expect(wrapper.find('.load-more').exists()).toBe(false)
    await wrapper.find('input[type="search"]').setValue('')
    expect(wrapper.findAll('.note-card')).toHaveLength(10)
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('翻页不丢的草稿')
    wrapper.unmount()
  })

  it('编辑区新增标签失败保留输入，成功选中并同步字典和草稿', async () => {
    const wrapper = composer()
    await wrapper.find('textarea').setValue('正文保留')
    await wrapper.find('.add-tag').trigger('click')
    await wrapper.find('[aria-label="新标签名称"]').setValue('新分类')
    vi.mocked(apiCreateNoteTag).mockRejectedValueOnce(new Error('暂时离线')).mockResolvedValueOnce('新分类')
    await wrapper.find('.inline-tag-create button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.inline-tag-create [role="alert"]').text()).toContain('暂时离线')
    expect(wrapper.find('.tag-chip').exists()).toBe(false)
    await wrapper.find('.inline-tag-create button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.tag-chip').text()).toContain('新分类')
    expect(wrapper.emitted('tag-created')).toEqual([['新分类']])
    expect(JSON.parse(readNoteDraft('wao-quick-note-draft-v1:wg')!).tags).toEqual(['新分类'])
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('正文保留')
    wrapper.unmount()
  })

  it('清单直接使用富文本结构，嵌套列表不被有损 Markdown 投影压平', async () => {
    const note = doc('nested', '1. 父项\n2. 子项')
    note.content = { version: 1, markdown: note.title, document: { type: 'doc', content: [{ type: 'orderedList', content: [{ type: 'listItem', content: [{ type: 'paragraph', content: [{ type: 'text', text: '父项' }] }, { type: 'orderedList', content: [{ type: 'listItem', content: [{ type: 'paragraph', content: [{ type: 'text', text: '子项' }] }] }] }] }] }] } }
    useDataStore().docs.nested = note
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/notes', component: NotesView }] })
    await router.push('/notes')
    const wrapper = mount(NotesView, { global: { plugins: [router] } })
    await vi.dynamicImportSettled()
    await flushPromises()
    expect(wrapper.find('.note-body ol ol li').text()).toBe('子项')
    expect(wrapper.find('.note-body .tiptap').attributes('contenteditable')).toBe('false')
    wrapper.unmount()
  })

  it('阅读态把不含图片地址的字数放到卡片顶部，编辑态保留编辑器字数', async () => {
    const note = doc('count', '正文ABC!')
    note.content = { version: 1, markdown: '![图片](/api/images/one/raw)\n\n正文ABC!', document: {
      type: 'doc', content: [
        { type: 'image', attrs: { src: '/api/images/one/raw', alt: '图片' } },
        { type: 'paragraph', content: [{ type: 'text', text: '正文ABC!' }] },
      ],
    } }
    useDataStore().docs.count = note
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/notes', component: NotesView }] })
    await router.push('/notes')
    const wrapper = mount(NotesView, { global: { plugins: [router] } })
    await vi.dynamicImportSettled()
    await flushPromises()
    expect(wrapper.find('.note-meta .note-count').text()).toBe('6 字')
    expect(wrapper.find('.note-body [aria-label="当前正文字数"]').exists()).toBe(true)
    await wrapper.find('.note-card').trigger('dblclick')
    await flushPromises()
    expect(wrapper.find('.note-meta .note-count').exists()).toBe(false)
    expect(wrapper.find('[aria-label="当前正文字数"]').exists()).toBe(true)
    expect(wrapper.find('.composer-editor-main img.md-img').attributes('src')).toBe('/api/images/one/preview')
    expect(wrapper.find('.composer-image-rail').exists()).toBe(false)
    wrapper.unmount()
  })

  it('小记卡片将 AI 与用户正文图片放到侧栏，阅读模式恢复原位置并可查看大图', async () => {
    const note = doc('images', '有图小记')
    note.content = { version: 1, markdown: '正文\n\n![用户配图](/api/images/one/raw)\n\n![AI 自动生成配图](/api/images/two/raw)',
      document: { type: 'doc', content: [
        { type: 'paragraph', content: [{ type: 'text', text: '正文' }] },
        { type: 'image', attrs: { src: '/api/images/one/raw', alt: '用户配图' } },
        { type: 'image', attrs: { src: '/api/images/two/raw', alt: 'AI 自动生成配图' } },
      ] } }
    useDataStore().docs.images = note
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/notes', component: NotesView }] })
    await router.push('/notes')
    const wrapper = mount(NotesView, { global: { plugins: [router], stubs: { NoteMiningPanel: true } } })
    await vi.dynamicImportSettled()
    await flushPromises()
    const card = wrapper.get('[data-note-id="images"]')
    expect(card.find('.note-body').text()).toContain('正文')
    const thumbnails = card.findAll('.note-images img')
    expect(thumbnails).toHaveLength(2)
    expect(thumbnails.map(image => image.attributes('src'))).toEqual(['/api/images/one/preview', '/api/images/two/preview'])
    await card.find('.note-images button').trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[role="dialog"][aria-label="小记阅读模式"]')).not.toBeNull()
    const readerImages = document.body.querySelectorAll<HTMLImageElement>('.reader-body img.md-img')
    expect(readerImages).toHaveLength(2)
    expect([...readerImages].map(image => image.getAttribute('src'))).toEqual(['/api/images/one/preview', '/api/images/two/preview'])
    readerImages[0]!.click()
    await flushPromises()
    expect(document.body.querySelector('.note-image-lightbox img')?.getAttribute('src')).toBe('/api/images/one/raw')
    document.body.querySelector('.note-image-lightbox img')?.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: 80, clientY: 80 }))
    await flushPromises()
    expect(document.body.querySelector('[role="menu"][aria-label="图片操作"]')?.textContent).toContain('复制图片地址')
    expect(document.body.querySelector('[role="menu"][aria-label^="小记："]')).toBeNull()
    document.body.querySelectorAll<HTMLButtonElement>('[role="menu"][aria-label="图片操作"] button')[1]?.click()
    await flushPromises()
    expect(copyText).toHaveBeenCalledWith(`${window.location.origin}/api/images/one/raw`, '图片地址已复制')
    document.body.querySelector<HTMLButtonElement>('.note-image-close')?.click()
    await flushPromises()
    readerImages[1]!.click()
    await flushPromises()
    expect(document.body.querySelector('.note-image-lightbox img')?.getAttribute('src')).toBe('/api/images/two/raw')
    document.body.querySelector('.note-image-lightbox img')?.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true }))
    await flushPromises()
    expect(document.body.querySelector('[role="menu"][aria-label="图片操作"]')?.textContent).toContain('复制图片')
    document.body.querySelectorAll<HTMLButtonElement>('[role="menu"][aria-label="图片操作"] button')[1]?.click()
    await flushPromises()
    expect(copyText).toHaveBeenLastCalledWith(`${window.location.origin}/api/images/two/raw`, '图片地址已复制')
    document.body.querySelector<HTMLButtonElement>('.note-image-close')?.click()
    await flushPromises()
    expect(document.body.querySelector('.note-image-lightbox')).toBeNull()
    wrapper.unmount()
  })

  it('展开全文时同步展开右侧图片栏，收起后恢复图片栏滚动限制', async () => {
    const note = doc('expanded-images', '长文小记')
    note.content = { version: 1, markdown: '正文\n\n![配图](/api/images/one/raw)', document: {
      type: 'doc', content: [
        { type: 'paragraph', content: [{ type: 'text', text: '正文' }] },
        { type: 'image', attrs: { src: '/api/images/one/raw', alt: '配图' } },
      ],
    } }
    useDataStore().docs[note.id] = note
    const wrapper = await view()
    const card = wrapper.get(`[data-note-id="${note.id}"]`)
    await setNoteBodyHeight(wrapper, note.id, 220)
    const imageRail = card.get('.note-images')

    expect(card.classes()).toContain('fixed-height')
    expect(imageRail.classes()).not.toContain('expanded')
    await card.get('.note-read-action').trigger('click')
    expect(card.classes()).toContain('expanded')
    expect(imageRail.classes()).toContain('expanded')

    await card.get('.note-read-action').trigger('click')
    expect(card.classes()).toContain('fixed-height')
    expect(imageRail.classes()).not.toContain('expanded')
    wrapper.unmount()
  })

  it('短小记自然收高且不显示展开入口，正文超出后才启用折叠', async () => {
    const note = doc('short-preview', '先写小记再复制到飞书聊天')
    note.content = { version: 1, markdown: '你现在写东西时，会先把想说的话记在小记上，再发到飞书吗？\n\n我想试试每天先把要聊的内容写在小记上，再复制到飞书上。', document: {
      type: 'doc', content: [
        { type: 'paragraph', content: [{ type: 'text', text: '你现在写东西时，会先把想说的话记在小记上，再发到飞书吗？' }] },
        { type: 'paragraph', content: [{ type: 'text', text: '我想试试每天先把要聊的内容写在小记上，再复制到飞书上。' }] },
      ],
    } }
    useDataStore().docs[note.id] = note
    const wrapper = await view()
    const card = wrapper.get(`[data-note-id="${note.id}"]`)
    await flushPromises()

    expect(card.classes()).not.toContain('fixed-height')
    expect(card.find('.note-read-action').exists()).toBe(false)
    await card.trigger('click')
    expect(card.classes()).not.toContain('expanded')

    await setNoteBodyHeight(wrapper, note.id, 220)
    expect(card.classes()).toContain('fixed-height')
    expect(card.get('.note-read-action').text()).toContain('展开全文')
    await card.get('.note-read-action').trigger('click')
    expect(card.classes()).toContain('expanded')

    await setNoteBodyHeight(wrapper, note.id, 80)
    expect(card.classes()).not.toContain('fixed-height')
    expect(card.classes()).not.toContain('expanded')
    expect(card.find('.note-read-action').exists()).toBe(false)
    wrapper.unmount()
  })

  it('聚合历史小记并按时间倒序，正文搜索和标签筛选可以组合', async () => {
    const data = useDataStore()
    data.docs.a = { ...doc('a', '旧想法', '2026-09-06T08:00:00'), tags: ['历史'] }
    data.docs.b = doc('b', '发布清单', '2026-09-07T08:00:00', 'wd')
    data.docs.c = { ...doc('c', '普通文档'), kbId: 'other' }
    const wrapper = await view()
    expect(wrapper.findAll('.note-card').map(card => card.attributes('data-note-id'))).toEqual(['b', 'a'])
    await wrapper.find('input[type="search"]').setValue('发布')
    expect(wrapper.findAll('.note-card')).toHaveLength(1)
    await openFilters(wrapper)
    await wrapper.findAll('.filter-options button').find(button => button.text().includes('历史'))!.trigger('click')
    expect(wrapper.text()).toContain('没有找到相关小记')
    wrapper.unmount()
  })

  it('保存即显示在原页面，清除旧筛选，并为下一条生成新的草稿 id', async () => {
    const data = useDataStore()
    vi.spyOn(data, 'addNote').mockImplementation(async (text, _title, id) => { data.docs[id!] = doc(id!, text); return id! })
    const wrapper = await view()
    await wrapper.find('input[type="search"]').setValue('找不到的词')
    await wrapper.find('textarea').setValue('即时显示的想法')
    const original = JSON.parse(readNoteDraft('wao-quick-note-draft-v1:wg')!).id
    await wrapper.find('.save-note').trigger('click')
    await flushPromises()
    expect((wrapper.find('.note-card textarea').element as HTMLTextAreaElement).value).toBe('即时显示的想法')
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('')
    expect(readNoteDraft('wao-quick-note-draft-v1:wg')).toBeNull()
    await wrapper.find('textarea').setValue('第二条')
    expect(JSON.parse(readNoteDraft('wao-quick-note-draft-v1:wg')!).id).not.toBe(original)
    wrapper.unmount()
  })

  it('兼容旧草稿，保存失败保留正文，同 id 重试；断线拒绝内存假保存', async () => {
    localStorage.setItem('wao-quick-note-draft-v1:wg', JSON.stringify({ id: 'legacy-note', text: '不能丢' }))
    const data = useDataStore()
    const add = vi.spyOn(data, 'addNote').mockRejectedValueOnce(new Error('网关错误')).mockResolvedValueOnce('legacy-note')
    const wrapper = composer()
    await wrapper.find('.fullscreen-toggle').trigger('click')
    await wrapper.find('.save-note').trigger('click')
    await flushPromises()
    expect(wrapper.classes()).toContain('fullscreen')
    expect(wrapper.find('[role="alert"]').text()).toContain('网关错误')
    expect(readNoteDraft('wao-quick-note-draft-v1:wg')).toContain('不能丢')
    await wrapper.find('.save-note').trigger('click')
    await flushPromises()
    expect(wrapper.classes()).not.toContain('fullscreen')
    expect(add.mock.calls.map(call => call[2])).toEqual(['legacy-note', 'legacy-note'])
    data.apiLive = false
    await wrapper.find('textarea').setValue('服务尚未连接')
    await wrapper.find('.save-note').trigger('click')
    await flushPromises()
    expect(add).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="alert"]').text()).toContain('服务尚未连接')
    wrapper.unmount()
  })

  it('恢复草稿自动保存本篇，并能继续编辑反复保存', async () => {
    const note = doc('edit-note', '原文')
    const data = useDataStore()
    data.docs[note.id] = note
    const first = composer(note)
    await first.find('textarea').setValue('修改后的正文')
    first.unmount()
    data.docs[note.id] = { ...doc(note.id, '另一窗口正文'), rev: 5 }
    const second = composer(data.docs[note.id])
    vi.mocked(apiPatchDoc).mockResolvedValueOnce({ ...note, blocks: [{ p: '修改后的正文' }], rev: 6 })
    await second.find('.save-note').trigger('click')
    await flushPromises()
    expect(apiPatchDoc).toHaveBeenCalledWith(note.id, expect.objectContaining({ expectedRev: 5 }))
    expect(second.find('.save-conflict-resolution').exists()).toBe(false)
    expect(readNoteDraft('wao-note-edit-v1:wg:edit-note')).toBeNull()
    await second.find('textarea').setValue('保存后再继续写')
    vi.mocked(apiPatchDoc).mockResolvedValueOnce({ ...note, blocks: [{ p: '保存后再继续写' }], rev: 7 })
    await second.find('.save-note').trigger('click')
    await flushPromises()
    expect(apiPatchDoc).toHaveBeenLastCalledWith(note.id, expect.objectContaining({ expectedRev: 6,
      content: expect.objectContaining({ markdown: '保存后再继续写' }) }))
    expect(second.find('[role="alert"]').exists()).toBe(false)
    second.unmount()
  })

  it('另一页签覆盖旧共享键后，本页签收起重开仍恢复自己的输入', async () => {
    const note = doc('same-origin', '已保存基线')
    useDataStore().docs[note.id] = note
    const first = composer(note)
    await first.find('textarea').setValue('第一页独有输入 A')
    // 兼容仍打开的旧客户端：它会写入之前共用的 localStorage 键。
    localStorage.setItem('wao-note-edit-v1:wg:same-origin', JSON.stringify({ id: note.id, text: '另一页签输入 B' }))
    first.unmount()
    const restored = composer(note)
    expect(restored.get<HTMLTextAreaElement>('textarea').element.value).toBe('第一页独有输入 A')
    restored.unmount()
  })

  it('没有版本号的旧浏览器草稿可自动按最新版本保存，竞争写入自动重试', async () => {
    const note = doc('legacy-draft', '服务器正文')
    useDataStore().docs[note.id] = note
    localStorage.setItem('wao-note-edit-v1:wg:legacy-draft', JSON.stringify({ id: note.id, text: '旧草稿新输入' }))
    const wrapper = composer(note)
    vi.mocked(apiPatchDoc).mockImplementationOnce(async () => {
      useDataStore().docs[note.id] = { ...note, rev: 4 }
      throw new Error('文档已在其他窗口更新')
    }).mockResolvedValueOnce({ ...note, blocks: [{ p: '旧草稿新输入' }], rev: 5 })
    await wrapper.find('.save-note').trigger('click')
    await flushPromises()
    expect(apiPatchDoc).toHaveBeenCalledTimes(2)
    expect(apiPatchDoc).toHaveBeenLastCalledWith(note.id, expect.objectContaining({ expectedRev: 4,
      content: expect.objectContaining({ markdown: '旧草稿新输入' }) }))
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('恢复的草稿携带原始快照时能合入后台 AI 标题并正常保存', async () => {
    const note = doc('restored-ai', '原文')
    useDataStore().docs[note.id] = note
    const first = composer(note)
    await first.find('textarea').setValue('原文，继续输入')
    first.unmount()
    const latest = { ...note, title: 'AI 新标题', rev: 5 }
    useDataStore().docs[note.id] = latest
    vi.mocked(apiPatchDoc).mockResolvedValueOnce({ ...latest, blocks: [{ p: '原文，继续输入' }], rev: 6 })
    const restored = composer(latest)
    await restored.find('.save-note').trigger('click')
    await flushPromises()
    expect(apiPatchDoc).toHaveBeenCalledWith(note.id, expect.objectContaining({ title: 'AI 新标题', expectedRev: 5,
      content: expect.objectContaining({ markdown: '原文，继续输入' }) }))
    expect(restored.find('[role="alert"]').exists()).toBe(false)
    restored.unmount()
  })

  it('续写期间 AI 到达保持唯一编辑正文，保存时合入标题和配图', async () => {
    const base = doc('ai-note', '原文')
    base.content = { version: 1, markdown: '原文', document: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '原文' }] }] } }
    const remote: Doc = { ...base, rev: 5, title: 'AI 标题', content: {
      version: 1, markdown: '![文章总览图](/api/images/a/raw "wao-image-size:50%xauto")\n\n原文',
      document: { type: 'doc', content: [
        { type: 'image', attrs: { src: '/api/images/a/raw', alt: '文章总览图' } },
        ...base.content.document!.content!,
      ] },
    } }
    const data = useDataStore()
    data.docs[base.id] = base
    const wrapper = mount(NoteComposer, { props: { note: base, inline: true }, global: { stubs: { MarkdownEditor: Editor } } })
    await wrapper.find('textarea').setValue('原文，继续写')
    data.docs[base.id] = remote
    await wrapper.setProps({ note: remote })
    expect(wrapper.find<HTMLTextAreaElement>('textarea').element.value).toBe('原文，继续写')
    expect(wrapper.find('.saved-preview').exists()).toBe(false)
    expect(wrapper.findAll('textarea')).toHaveLength(1)
    notePolishVersions[base.id] = { docId: base.id, sourceMarkdown: remote.content!.markdown, polishedMarkdown: 'AI 润色版' }
    await flushPromises()
    expect(wrapper.find<HTMLTextAreaElement>('textarea').element.value).toBe('原文，继续写')
    vi.mocked(fetchDoc).mockResolvedValue(remote)
    vi.mocked(apiPatchDoc).mockImplementation(async (_id, payload) => ({ ...remote, rev: 6,
      title: payload.title!, content: payload.content!, tags: payload.tags! }))
    await wrapper.find('.save-note').trigger('click')
    await flushPromises()
    expect(apiPatchDoc).toHaveBeenCalledWith(base.id, expect.objectContaining({
      title: 'AI 标题', expectedRev: 5,
      content: expect.objectContaining({ markdown: expect.stringContaining('原文，继续写') }),
    }))
    expect(data.docs[base.id]!.content?.markdown).toContain('原文，继续写')
    expect(data.docs[base.id]!.content?.markdown).toContain('![文章总览图]')
    wrapper.unmount()
  })

  it('没有续写时自动同步最新 AI 正文，有续写时保持输入与唯一编辑区', async () => {
    const note = doc('ai-sync', '原始正文')
    const wrapper = composer(note)
    notePolishVersions[note.id] = { docId: note.id, sourceMarkdown: '原始正文', polishedMarkdown: '最新润色正文' }
    await flushPromises()
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('最新润色正文')
    await wrapper.find('textarea').setValue('最新润色正文，再继续写')
    await wrapper.setProps({ note: { ...doc(note.id, '迟到的 AI 正文'), rev: 8 } })
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('最新润色正文，再继续写')
    expect(wrapper.findAll('textarea')).toHaveLength(1)
    wrapper.unmount()
  })

  it('连续版本竞争有限重试，失败仍保留最新草稿', async () => {
    const note = doc('busy-note', '原始正文')
    useDataStore().docs[note.id] = note
    const wrapper = composer(note)
    await wrapper.find('textarea').setValue('最新续写正文')
    vi.mocked(apiPatchDoc).mockRejectedValue(new Error('文档已在其他窗口更新'))
    await wrapper.find('.save-note').trigger('click')
    await flushPromises()
    expect(apiPatchDoc).toHaveBeenCalledTimes(3)
    expect(wrapper.get('[role="alert"]').text()).toContain('最新输入已保留')
    expect(readNoteDraft('wao-note-edit-v1:wg:busy-note')).toContain('最新续写正文')
    expect(wrapper.find('.saved-preview').exists()).toBe(false)
    wrapper.unmount()
  })

  it('收起后的预览仍显示最新草稿，后台刷新和重新编辑不丢续写', async () => {
    const data = useDataStore()
    data.docs.one = doc('one', '保存的正文')
    const wrapper = await view()
    await wrapper.get('[data-note-id="one"]').trigger('dblclick')
    await wrapper.get('[data-note-id="one"] .note-composer textarea').setValue('当前最新续写')
    await wrapper.get('[data-note-id="one"] .note-composer').findAll('button').find(button => button.text() === '收起编辑')!.trigger('click')
    expect(wrapper.get('[data-note-id="one"] .note-body textarea').attributes('value')).toBe('当前最新续写')
    data.docs.one = { ...doc('one', '后台旧 AI 正文'), rev: 6 }
    await flushPromises()
    expect(wrapper.get('[data-note-id="one"] .note-body textarea').attributes('value')).toBe('当前最新续写')
    expect(wrapper.get('[data-note-id="one"] .workflow-status').text()).toContain('有修改待保存')
    await wrapper.get('[data-note-id="one"]').trigger('dblclick')
    expect(wrapper.get<HTMLTextAreaElement>('[data-note-id="one"] .note-composer textarea').element.value).toBe('当前最新续写')
    wrapper.unmount()
  })

  it('草稿存储异常明确提示，请求期间禁止重复提交，旧请求不清理新草稿', async () => {
    const data = useDataStore()
    let finish!: (id: string) => void
    vi.spyOn(data, 'addNote').mockReturnValue(new Promise(resolve => { finish = resolve }))
    const wrapper = composer()
    const store = vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => { throw new Error('quota') })
    await wrapper.find('textarea').setValue('仍在内存')
    expect(wrapper.text()).toContain('浏览器草稿保存失败')
    store.mockRestore()
    await wrapper.find('.save-note').trigger('click')
    expect(wrapper.find('textarea').attributes('readonly')).toBeDefined()
    expect(wrapper.find('.save-note').attributes('disabled')).toBeDefined()
    localStorage.setItem('wao-quick-note-draft-v1:wg', JSON.stringify({ id: 'newer-draft', text: '新页面继续写的内容' }))
    finish('saved')
    await flushPromises()
    expect(localStorage.getItem('wao-quick-note-draft-v1:wg')).toContain('新页面继续写的内容')
    wrapper.unmount()
  })

  it('真实高级编辑器保存后仍在原页面，可立即输入下一条且没有残留草稿', async () => {
    localStorage.setItem('wao-quick-note-draft-v1:wg', JSON.stringify({ id: 'real-editor', text: '1. 第一步\n2. 第二步' }))
    vi.spyOn(useDataStore(), 'addNote').mockResolvedValue('real-editor')
    const wrapper = mount(NoteComposer, { attachTo: document.body })
    await vi.dynamicImportSettled()
    await flushPromises()
    expect(wrapper.findAll('.tiptap ol li')).toHaveLength(2)
    await wrapper.find('.save-note').trigger('click')
    await flushPromises()
    expect(wrapper.find('.toolbar-toggle').exists()).toBe(false)
    expect(wrapper.find('.tiptap').attributes('contenteditable')).toBe('true')
    expect(wrapper.find('.tiptap').text()).toBe('')
    expect(wrapper.find('.save-note').attributes('disabled')).toBeDefined()
    expect(readNoteDraft('wao-quick-note-draft-v1:wg')).toBeNull()
    expect(wrapper.emitted('saved')).toEqual([['real-editor']])
    wrapper.unmount()
  })
  it('小记润色确认后进入富文本草稿，保存并重新编辑仍保留结果', async () => {
    const source = '这句话写得不是特别顺。'
    localStorage.setItem('wao-quick-note-draft-v1:wg', JSON.stringify({ id: 'polish-note', text: source }))
    vi.mocked(polishDocumentText).mockResolvedValue({ polished: '这句话表达得更加自然。' })
    const add = vi.spyOn(useDataStore(), 'addNote').mockResolvedValue('polish-note')
    const wrapper = mount(NoteComposer, { attachTo: document.body })
    await vi.dynamicImportSettled()
    await flushPromises()
    const editor = wrapper.findComponent(MarkdownEditor)
    expect(editor.props()).toMatchObject({ advanced: true, compact: false, allowH1: true, polishable: true, linkControls: true, imageControls: true })
    expect(editor.find('button[title="表格"]').exists()).toBe(false)
    const instance = editor.vm as unknown as { editor: TiptapEditor; startPolish: () => Promise<void> }
    instance.editor.commands.setTextSelection({ from: 1, to: source.length + 1 })
    await instance.startPolish()
    await flushPromises()
    expect(polishDocumentText).toHaveBeenCalledWith(source, expect.objectContaining({
      requestId: expect.any(String),
      isActive: expect.any(Function),
      onProgress: expect.any(Function),
    }))
    expect(wrapper.find('.tiptap').text()).toBe(source)
    await wrapper.find('.polish-apply').trigger('click')
    expect(JSON.parse(readNoteDraft('wao-quick-note-draft-v1:wg')!).text).toContain('更加自然')
    await wrapper.find('.save-note').trigger('click')
    await flushPromises()
    const rich = add.mock.calls[0]![3]!
    expect(rich.markdown).toContain('更加自然')
    expect(rich.document).toBeTruthy()
    wrapper.unmount()
    const reopened = mount(NoteComposer, { props: { note: { ...doc('polish-note', rich.markdown), content: rich } }, attachTo: document.body })
    await flushPromises()
    expect(reopened.find('.tiptap').text()).toContain('更加自然')
    reopened.unmount()
  })

  it('全屏表格右键菜单位于全屏容器之上，可插入行并保留草稿', async () => {
    const wrapper = mount(NoteComposer, { attachTo: document.body })
    await vi.dynamicImportSettled()
    await flushPromises()
    expect(wrapper.find('.toolbar-toggle').exists()).toBe(false)
    await wrapper.find('.fullscreen-toggle').trigger('click')
    const instance = wrapper.findComponent(MarkdownEditor).vm as unknown as { editor: TiptapEditor }
    instance.editor.chain().focus().insertTable({ rows: 2, cols: 2, withHeaderRow: false }).run()
    const rows = wrapper.findAll('tr').length
    await wrapper.find('td').trigger('contextmenu', { clientX: 100, clientY: 100 })
    await flushPromises()
    const menu = document.querySelector<HTMLElement>('.table-context-menu')!
    expect(Number(menu.style.zIndex)).toBeGreaterThan(2100)
    Array.from(menu.querySelectorAll('button')).find(button => button.textContent === '向下插入行')!.click()
    await flushPromises()
    expect(wrapper.findAll('tr')).toHaveLength(rows + 1)
    expect(JSON.parse(readNoteDraft('wao-quick-note-draft-v1:wg')!).content.document).toBeTruthy()
    await wrapper.trigger('keydown', { key: 'Escape' })
    expect(wrapper.classes()).not.toContain('fullscreen')
    expect(wrapper.findAll('tr')).toHaveLength(rows + 1)
    wrapper.unmount()
  })

  it('有未保存正文时按个人间隔提醒进入全屏，进入全屏后停止提醒', async () => {
    const preferences = usePreferenceStore()
    preferences.loaded = true
    preferences.noteFullscreenReminder = { enabled: true, delayMinutes: 1 }
    vi.useFakeTimers()
    const wrapper = mount(NoteComposer, { global: { stubs: { MarkdownEditor: Editor } } })
    await wrapper.find('textarea').setValue('继续写下去的想法')
    await vi.advanceTimersByTimeAsync(60_000)
    await flushPromises()
    expect(wrapper.find('.fullscreen-reminder').text()).toContain('来全屏专心创作')
    expect(wrapper.classes()).toContain('reminder-active')
    expect(wrapper.find('.composer-heading .fullscreen-toggle').attributes('aria-describedby'))
      .toBe(wrapper.find('.fullscreen-reminder').attributes('id'))
    await wrapper.findAll('.fullscreen-reminder button').find(button => button.text() === '稍后提醒')!.trigger('click')
    expect(wrapper.find('.fullscreen-reminder').exists()).toBe(false)
    expect(wrapper.classes()).not.toContain('reminder-active')
    await vi.advanceTimersByTimeAsync(60_000)
    await flushPromises()
    expect(wrapper.find('.fullscreen-reminder').exists()).toBe(true)
    await wrapper.find('[aria-label="关闭全屏提醒"]').trigger('click')
    await vi.advanceTimersByTimeAsync(60_000)
    expect(wrapper.find('.fullscreen-reminder').exists()).toBe(false)
    await wrapper.find('textarea').setValue('继续写下去的想法，再补一行')
    await vi.advanceTimersByTimeAsync(60_000)
    await flushPromises()
    expect(wrapper.find('.fullscreen-reminder').exists()).toBe(true)
    await wrapper.find('.fullscreen-reminder .reminder-enter').trigger('click')
    expect(wrapper.classes()).toContain('fullscreen')
    expect(wrapper.find('.fullscreen-reminder').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(wrapper.find('.fullscreen-reminder').exists()).toBe(false)
    wrapper.unmount()
    vi.useRealTimers()
  })

  it.each([false, true])('小记选区通用待办保留正文与草稿，正确区分已保存来源：%s', async saved => {
    const source = '整理明天的计划'
    if (!saved) localStorage.setItem('wao-quick-note-draft-v1:wg', JSON.stringify({ id: 'task-note', text: source }))
    const wrapper = mount(NoteComposer, { props: { note: saved ? doc('task-note', source) : undefined }, attachTo: document.body })
    await vi.dynamicImportSettled()
    await flushPromises()
    await wrapper.find('.fullscreen-toggle').trigger('click')
    const instance = wrapper.findComponent(MarkdownEditor).vm as unknown as { editor: TiptapEditor; addSelectionTask: () => Promise<void> }
    const before = instance.editor.getJSON()
    instance.editor.commands.setTextSelection({ from: 1, to: source.length + 1 })
    await instance.addSelectionTask()
    await flushPromises()
    const ui = useUiStore()
    expect(ui.modal).toBe('task')
    expect(ui.modalProject).toBe('')
    expect(ui.modalTaskSeed?.title).toBe(source)
    expect(ui.modalTaskSeed?.desc).toContain(source)
    expect(ui.modalTaskSeed?.sourceDocId).toBe(saved ? 'task-note' : undefined)
    expect(ui.modalTaskSeed?.sourceUrl).toBe(saved ? '/kb/kb-notes?doc=task-note' : '')
    expect(ui.modalTaskSeed?.draftKey).toContain(':wg:task-note:')
    expect(wrapper.classes()).not.toContain('fullscreen')
    expect(instance.editor.getJSON()).toEqual(before)
    expect(saved || readNoteDraft('wao-quick-note-draft-v1:wg')?.includes(source)).toBeTruthy()
    const key = ui.modalTaskSeed?.draftKey
    ui.closeModal()
    instance.editor.commands.setTextSelection({ from: 1, to: source.length + 1 })
    await instance.addSelectionTask()
    expect(ui.modalTaskSeed?.draftKey).toBe(key)
    wrapper.unmount()
  })

  it('标签字典保存失败保留名称、重试去重，标签与搜索组合并切换两种排序', async () => {
    const data = useDataStore()
    data.docs.a = { ...doc('a', '工作备忘', '2026-09-05T08:00:00'), updated: '2026-09-08T08:00:00', tags: ['工作'] }
    data.docs.b = { ...doc('b', '工作想法', '2026-09-07T08:00:00'), tags: ['想法'] }
    vi.mocked(fetchNoteTags).mockResolvedValue(['工作', '想法'])
    const wrapper = await view()
    await flushPromises()
    expect(wrapper.findAll('.note-card').map(card => card.attributes('data-note-id'))).toEqual(['a', 'b'])
    await openFilters(wrapper)
    await wrapper.find('[aria-label="小记排序"]').setValue('createdDesc')
    expect(wrapper.findAll('.note-card').map(card => card.attributes('data-note-id'))).toEqual(['b', 'a'])
    await wrapper.findAll('.filter-options button').find(tag => tag.text().includes('工作'))!.trigger('click')
    await wrapper.find('input[type="search"]').setValue('备忘')
    expect(wrapper.findAll('.note-card').map(card => card.attributes('data-note-id'))).toEqual(['a'])
    await wrapper.find('.filter-footer button').trigger('click')
    await wrapper.find('.new-tag-form input').setValue('灵感')
    vi.mocked(apiCreateNoteTag).mockRejectedValueOnce(new Error('网关不可达')).mockResolvedValueOnce('灵感')
    await wrapper.find('.new-tag-form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('.new-tag-form').text()).toContain('重试新增')
    expect((wrapper.find('.new-tag-form input').element as HTMLInputElement).value).toBe('灵感')
    await wrapper.find('.new-tag-form').trigger('submit')
    await flushPromises()
    await openFilters(wrapper)
    expect(wrapper.findAll('.filter-options button').some(tag => tag.text().includes('灵感'))).toBe(true)
    expect(apiCreateNoteTag).toHaveBeenNthCalledWith(2, '灵感')
    wrapper.unmount()
  })

  it('全屏保持编辑器实例、正文和标签草稿，刷新恢复后同请求提交标签', async () => {
    const add = vi.spyOn(useDataStore(), 'addNote').mockResolvedValue('tagged-note')
    const first = mount(NoteComposer, { props: { tagOptions: ['灵感'] }, global: { stubs: { MarkdownEditor: Editor } } })
    await first.find('textarea').setValue('全屏仍在的想法')
    await first.find('.fullscreen-toggle').trigger('click')
    await first.find('.fullscreen-quick-tags button').trigger('click')
    const editorElement = first.find('textarea').element
    expect(first.classes()).toContain('fullscreen')
    expect(first.find('textarea').element).toBe(editorElement)
    await first.trigger('keydown', { key: 'Escape' })
    expect(first.classes()).not.toContain('fullscreen')
    expect(JSON.parse(readNoteDraft('wao-quick-note-draft-v1:wg')!).tags).toEqual(['灵感'])
    first.unmount()
    const second = composer()
    expect((second.find('textarea').element as HTMLTextAreaElement).value).toBe('全屏仍在的想法')
    expect(second.find('.tag-chip').text()).toContain('灵感')
    await second.find('.save-note').trigger('click')
    await flushPromises()
    expect(add.mock.calls[0]![4]).toEqual(['灵感'])
    expect(readNoteDraft('wao-quick-note-draft-v1:wg')).toBeNull()
    second.unmount()
  })

  it('在卡片原地编辑、收起和重开不替换左侧新建实例及草稿', async () => {
    useDataStore().docs.a = { ...doc('a', '旧记录'), tags: ['工作'] }
    const wrapper = await view()
    const newEditor = wrapper.get('.writing-pane textarea').element
    await wrapper.get('.writing-pane textarea').setValue('正在写的新草稿')
    await wrapper.get('.note-card').trigger('click')
    expect(wrapper.find('.note-card .note-composer').exists()).toBe(false)
    await wrapper.get('.note-edit-action').trigger('click')
    expect(wrapper.get('.writing-pane textarea').element).toBe(newEditor)
    expect(wrapper.get<HTMLTextAreaElement>('.writing-pane textarea').element.value).toBe('正在写的新草稿')
    expect(wrapper.get<HTMLTextAreaElement>('.note-card textarea').element.value).toBe('旧记录')
    await wrapper.get('.note-card textarea').setValue('卡片内尚未保存的修改')
    await wrapper.get('.note-card .note-composer').findAll('button').find(button => button.text() === '收起编辑')!.trigger('click')
    expect(wrapper.find('.note-card .note-composer').exists()).toBe(false)
    await wrapper.get('.note-edit-action').trigger('click')
    expect(wrapper.get<HTMLTextAreaElement>('.note-card textarea').element.value).toBe('卡片内尚未保存的修改')
    expect(wrapper.get('.writing-pane textarea').element).toBe(newEditor)
    wrapper.unmount()
  })

  it('卡片保存失败可原地重试，成功更新正文且不清除筛选或新建草稿', async () => {
    const data = useDataStore()
    data.docs.a = doc('a', '工作原文')
    const wrapper = await view()
    await wrapper.get('.writing-pane textarea').setValue('左侧草稿')
    await wrapper.get('input[type="search"]').setValue('工作')
    await wrapper.get('.note-card').trigger('dblclick')
    await wrapper.get('.note-card textarea').setValue('工作修改')
    vi.mocked(apiPatchDoc).mockRejectedValueOnce(new Error('网关错误')).mockResolvedValueOnce({ ...doc('a', '工作修改'), rev: 4 })
    await wrapper.get('.note-card .save-note').trigger('click')
    await flushPromises()
    expect(wrapper.get('.note-card [role="alert"]').text()).toContain('网关错误')
    expect(data.docs.a!.title).toBe('工作原文')
    expect(wrapper.get<HTMLTextAreaElement>('.note-card textarea').element.value).toBe('工作修改')
    await wrapper.get('.note-card .save-note').trigger('click')
    await flushPromises()
    expect(apiPatchDoc).toHaveBeenLastCalledWith('a', expect.objectContaining({ expectedRev: 3 }))
    expect(wrapper.find('.note-card .note-composer').exists()).toBe(false)
    expect(wrapper.get<HTMLTextAreaElement>('.note-card textarea').element.value).toBe('工作修改')
    expect(wrapper.get('.note-card .saved').text()).toBe('已保存')
    expect(wrapper.get<HTMLInputElement>('input[type="search"]').element.value).toBe('工作')
    expect(wrapper.get<HTMLTextAreaElement>('.writing-pane textarea').element.value).toBe('左侧草稿')
    expect(readNoteDraft('wao-note-edit-v1:wg:a')).toBeNull()
    wrapper.unmount()
  })

  it('筛选和同步不卸载在编卡片，多个卡片独立保留内容与基础版本', async () => {
    const data = useDataStore()
    data.docs.a = doc('a', '记录 A')
    data.docs.b = doc('b', '记录 B')
    const wrapper = await view()
    await wrapper.get('[data-note-id="a"]').trigger('dblclick')
    await wrapper.get('[data-note-id="a"] textarea').setValue('A 未保存')
    const editorA = wrapper.get('[data-note-id="a"] textarea').element
    await wrapper.get('[data-note-id="b"]').trigger('dblclick')
    await wrapper.get('[data-note-id="b"] textarea').setValue('B 未保存')
    const editorB = wrapper.get('[data-note-id="b"] textarea').element
    await wrapper.get('input[type="search"]').setValue('不匹配任何记录')
    expect(wrapper.get('[data-note-id="a"] textarea').element).toBe(editorA)
    expect(wrapper.get('[data-note-id="b"] textarea').element).toBe(editorB)
    data.docs.a = { ...doc('a', '服务器更新'), rev: 7 }
    await flushPromises()
    expect(wrapper.get<HTMLTextAreaElement>('[data-note-id="a"] textarea').element.value).toBe('A 未保存')
    vi.mocked(apiPatchDoc).mockImplementationOnce(async (_id, payload) => ({ ...data.docs.a!, rev: 8, content: payload.content }))
    await wrapper.get('[data-note-id="a"] .save-note').trigger('click')
    await flushPromises()
    expect(apiPatchDoc).toHaveBeenCalledWith('a', expect.objectContaining({ expectedRev: 7,
      content: expect.objectContaining({ markdown: 'A 未保存' }) }))
    expect(wrapper.get<HTMLTextAreaElement>('[data-note-id="b"] textarea').element.value).toBe('B 未保存')
    wrapper.unmount()
  })

  it('卡片内真实富文本编辑器加载完成后自动获得输入焦点', async () => {
    const wrapper = mount(NoteComposer, { props: { note: doc('focus-note', '就地输入'), inline: true, autofocus: true }, attachTo: document.body })
    await vi.dynamicImportSettled()
    await flushPromises()
    await vi.waitFor(() => expect(document.activeElement).toBe(wrapper.get('.tiptap').element))
    expect(wrapper.classes()).toContain('inline')
    expect(wrapper.find('.composer-heading').exists()).toBe(false)
    expect(wrapper.find('.editor-tools').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('复制内容')
    expect(wrapper.text()).toContain('保存修改')
    wrapper.unmount()
  })

  it('已保存小记卡片内可全屏编辑，保存失败保留全屏、成功后自动退出', async () => {
    const note = { ...doc('inline-fullscreen', '原有正文'), tags: ['工作'] }
    useDataStore().docs[note.id] = note
    vi.mocked(apiPatchDoc).mockRejectedValueOnce(new Error('网络不可用'))
      .mockResolvedValueOnce({ ...note, title: '修改后的正文', blocks: [{ p: '修改后的正文' }] })
    const wrapper = mount(NoteComposer, {
      props: { note, inline: true },
      global: { stubs: { MarkdownEditor: Editor } },
    })
    await wrapper.find('textarea').setValue('修改后的正文')
    const editorElement = wrapper.find('textarea').element
    expect(wrapper.find('.inline-heading .fullscreen-toggle').attributes('aria-label')).toBe('全屏编辑')
    expect(wrapper.find('.tag-chip').text()).toContain('工作')
    expect(wrapper.find('.fullscreen-quick-tags').exists()).toBe(false)

    await wrapper.find('.inline-heading .fullscreen-toggle').trigger('click')
    expect(wrapper.classes()).toContain('fullscreen')
    expect(wrapper.find('.fullscreen-quick-tags').text()).toContain('常用标签')
    expect(wrapper.find('textarea').element).toBe(editorElement)
    await wrapper.find('.save-note').trigger('click')
    await flushPromises()
    expect(wrapper.classes()).toContain('fullscreen')
    expect(wrapper.find('[role="alert"]').text()).toContain('网络不可用')
    expect(wrapper.find('textarea').element).toBe(editorElement)

    await wrapper.find('.save-note').trigger('click')
    await flushPromises()
    expect(wrapper.classes()).not.toContain('fullscreen')
    expect(apiPatchDoc).toHaveBeenCalledWith('inline-fullscreen', expect.objectContaining({ expectedRev: 3 }))
    wrapper.unmount()
  })

  it('卡片预览与阅读模式共用只读阅读器，记住布局且保留草稿和返回焦点', async () => {
    useDataStore().docs.one = doc('one', '合成预览正文')
    const host = document.createElement('div')
    host.id = 'app'
    document.body.append(host)
    const wrapper = await view(host)
    await wrapper.get('.writing-pane textarea').setValue('验收草稿保持不变')
    const preview = wrapper.get('.note-preview-action')
    await preview.trigger('click')
    await preview.trigger('click') // 快速重复触发不能覆盖背景恢复状态。
    await flushPromises()
    const reader = () => new DOMWrapper(document.body).get('.notes-reader')
    expect(host.inert).toBe(true)
    expect(reader().get('textarea').attributes('readonly')).toBeDefined()
    expect(wrapper.find('.note-card.editing').exists()).toBe(false)
    await reader().get('.reader-mode-switch button:last-child').trigger('click')
    expect(reader().classes()).toContain('reader-full')
    await reader().get('[aria-label="退出阅读模式"]').trigger('click')
    await flushPromises()
    expect(host.inert).toBe(false)
    expect(document.body.style.overflow).not.toBe('hidden')
    expect(document.activeElement).toBe(preview.element)
    expect(wrapper.get<HTMLTextAreaElement>('.writing-pane textarea').element.value).toBe('验收草稿保持不变')
    await wrapper.get('.reader-toggle').trigger('click')
    await flushPromises()
    expect(reader().classes()).toContain('reader-full')
    await reader().get('[aria-label="退出阅读模式"]').trigger('click')
    await wrapper.get('.note-preview-choice').trigger('click')
    await flushPromises()
    await new DOMWrapper(document.body).get('[role="menu"] button:first-of-type').trigger('click')
    await flushPromises()
    expect(reader().classes()).not.toContain('reader-full')
    wrapper.unmount()
    host.remove()
    expect(document.body.style.overflow).not.toBe('hidden')
  })

  it('阅读按 ID 固定本次目录，后台新增或重排不跳篇，逐篇恢复滚动位置', async () => {
    const data = useDataStore()
    data.docs.a = doc('a', '合成旧篇')
    data.docs.b = doc('b', '合成新篇', '2026-09-08T08:00:00')
    const wrapper = await view()
    await wrapper.get('[data-note-id="b"] .note-preview-action').trigger('click')
    await flushPromises()
    const reader = () => new DOMWrapper(document.body).get('.notes-reader')
    const page = reader().get('.reader-page').element as HTMLElement
    page.scrollTop = 245
    data.docs.c = doc('c', '后台新增', '2026-09-09T08:00:00')
    data.docs.a = { ...data.docs.a!, updated: '2026-09-10T08:00:00' }
    await flushPromises()
    expect(reader().get('h2').text()).toBe('合成新篇')
    expect(reader().findAll('.reader-directory nav button')).toHaveLength(2)
    await reader().get('[aria-label="下一篇小记（→）"]').trigger('click')
    await flushPromises()
    expect(reader().get('h2').text()).toBe('合成旧篇')
    await reader().get('[aria-label="上一篇小记（←）"]').trigger('click')
    await flushPromises()
    expect((reader().get('.reader-page').element as HTMLElement).scrollTop).toBe(245)
    delete data.docs.b
    await flushPromises()
    expect(reader().get('h2').text()).toBe('合成旧篇')
    delete data.docs.a
    await flushPromises()
    expect(document.querySelector('.notes-reader')).toBeNull()
    wrapper.unmount()
  })

  it('窄屏目录先收起再退出，放大窗口后正文恢复操作', async () => {
    useDataStore().docs.one = doc('one', '合成目录验收')
    const wrapper = await view()
    await wrapper.get('.note-preview-action').trigger('click')
    const reader = new DOMWrapper(document.body).get('.notes-reader')
    await reader.get('.reader-directory-toggle').trigger('click')
    expect(reader.classes()).toContain('directory-open')
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(reader.classes()).not.toContain('directory-open')
    expect(document.querySelector('.notes-reader')).not.toBeNull()
    await reader.get('.reader-directory-toggle').trigger('click')
    window.dispatchEvent(new Event('resize'))
    await flushPromises()
    expect(reader.get('.reader-main').attributes('inert')).toBeUndefined()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(document.querySelector('.notes-reader')).toBeNull()
    wrapper.unmount()
  })

  it('卡片展示标题并支持键盘阅读、左右翻篇和上下滚动', async () => {
    useDataStore().docs.a = { ...doc('a', '键盘编辑的记录'), tags: ['工作'] }
    useDataStore().docs.b = doc('b', '下一篇记录', '2026-09-08T08:00:00')
    const wrapper = await view()
    await wrapper.find('.card-tags button').trigger('dblclick')
    await wrapper.find('.card-quick-actions .full-doc-icon').trigger('dblclick')
    expect(wrapper.find('.note-actions').exists()).toBe(false)
    expect(wrapper.find('.card-tags').element.parentElement?.classList.contains('note-heading')).toBe(true)
    expect(wrapper.get('[data-note-id="a"] h2').text()).toBe('键盘编辑的记录')
    expect(wrapper.get('[data-note-id="a"]').classes()).not.toContain('fixed-height')
    expect(wrapper.find('[data-note-id="a"] .note-read-action').exists()).toBe(false)
    await wrapper.get('[data-note-id="b"]').trigger('keydown', { key: 'Enter' })
    expect(document.body.querySelector('[role="dialog"][aria-label="小记阅读模式"] h2')?.textContent).toBe('下一篇记录')
    const readerPage = document.querySelector<HTMLElement>('.reader-page')!
    Object.defineProperty(readerPage, 'scrollBy', { value: vi.fn(), configurable: true })
    const scrollBy = vi.spyOn(readerPage, 'scrollBy').mockImplementation(() => {})
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    expect(scrollBy).toHaveBeenCalled()
    expect(document.body.querySelector('[role="dialog"][aria-label="小记阅读模式"] h2')?.textContent).toBe('下一篇记录')
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }))
    await flushPromises()
    expect(document.body.querySelector('[role="dialog"][aria-label="小记阅读模式"] h2')?.textContent).toBe('键盘编辑的记录')
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(document.body.querySelector('[aria-label="小记阅读模式"]')).toBeNull()
    await wrapper.get('[data-note-id="a"]').trigger('dblclick')
    expect(wrapper.get<HTMLTextAreaElement>('[data-note-id="a"] textarea').element.value).toBe('键盘编辑的记录')
    wrapper.unmount()
  })

  it('只保留标签筛选，无标签独立筛选且再次点击恢复全部', async () => {
    const data = useDataStore()
    data.docs.a = { ...doc('a', '有标签正文'), tags: ['工作'] }
    data.docs.b = { ...doc('b', '无标签正文'), tags: [] }
    const wrapper = await view()
    expect(wrapper.text()).not.toContain('全部小记')
    expect(wrapper.text()).not.toContain('全部时间')
    expect(wrapper.find('[aria-label="按创建日期筛选"]').exists()).toBe(false)
    await openFilters(wrapper)
    const untagged = () => wrapper.findAll('.filter-options button').find(button => button.text().startsWith('无标签'))!
    await untagged().trigger('click')
    expect(wrapper.findAll('.note-card').map(card => card.attributes('data-note-id'))).toEqual(['b'])
    await wrapper.findAll('.filter-options button').find(button => button.text() === '全部')!.trigger('click')
    expect(wrapper.findAll('.note-card')).toHaveLength(2)
    wrapper.unmount()
  })

  it('刷新小记页恢复上次筛选与排序', async () => {
    useDataStore().docs.a = { ...doc('a', '创作记录', '2026-09-05T08:00:00'), tags: ['创作'] }
    useDataStore().docs.b = { ...doc('b', '计划记录', '2026-09-07T08:00:00'), tags: ['计划'] }
    const first = await view()
    await openFilters(first)
    await first.get('[aria-label="小记排序"]').setValue('createdDesc')
    await first.findAll('.filter-options button').find(button => button.text().includes('创作'))!.trigger('click')
    expect(localStorage.getItem('wao-note-list-preferences-v1:wg')).toContain('createdDesc')
    first.unmount()
    const restored = await view()
    await openFilters(restored)
    expect((restored.get('[aria-label="小记排序"]').element as HTMLSelectElement).value).toBe('createdDesc')
    expect(restored.findAll('.filter-options button').find(button => button.text().includes('创作'))!.attributes('aria-pressed')).toBe('true')
    restored.unmount()
  })

  it('作者筛选默认全部，可按作者过滤并随清除筛选恢复', async () => {
    const data = useDataStore()
    data.docs.a = doc('a', '娃哥的小记', '2026-09-07T08:00:00', 'wg')
    data.docs.b = doc('b', '同伴的小记', '2026-09-08T08:00:00', 'wd')
    const wrapper = await view()
    await openFilters(wrapper)
    const author = wrapper.get<HTMLSelectElement>('[aria-label="按作者筛选"]')
    expect(author.element.value).toBe('')
    expect(author.findAll('option').map(option => option.attributes('value'))).toContain('wd')
    await author.setValue('wd')
    expect(wrapper.findAll('.note-card').map(card => card.attributes('data-note-id'))).toEqual(['b'])
    await wrapper.find('.filter-footer button:last-child').trigger('click')
    expect(wrapper.get<HTMLSelectElement>('[aria-label="按作者筛选"]').element.value).toBe('')
    expect(wrapper.findAll('.note-card').map(card => card.attributes('data-note-id'))).toEqual(['b', 'a'])
    wrapper.unmount()
  })

  it('小记列表滚动后显示回到顶部火箭，点击滚回列表开头', async () => {
    const wrapper = await view()
    const list = wrapper.get('.notes-list')
    const element = list.element as HTMLElement
    Object.defineProperties(element, {
      scrollTop: { configurable: true, writable: true, value: 620 },
      scrollHeight: { configurable: true, value: 1200 },
      clientHeight: { configurable: true, value: 500 },
    })
    const scrollTo = vi.fn()
    element.scrollTo = scrollTo
    await list.trigger('scroll')
    const backToTop = wrapper.get('[aria-label="回到顶部"]')
    await backToTop.trigger('click')
    expect(scrollTo).toHaveBeenCalledWith({ top: 0, behavior: 'smooth' })
    wrapper.unmount()
  })

  it('润色自动成为唯一内容且再次编辑一致，保留总览图和筛选', async () => {
    const today = new Date().toISOString()
    const source = '今天开会，然后就是决定明天发布。'
    useDataStore().docs.a = {
      ...doc('a', '今日有图', today), updated: today,
      content: { version: 1, document: null, markdown: `![文章总览图](/api/images/a/raw)\n\n${source}` },
    }
    useDataStore().docs.b = doc('b', '以前无图', '2020-01-01T08:00:00')
    vi.mocked(fetchNotePolishVersions).mockResolvedValue([{
      docId: 'a', sourceMarkdown: `![文章总览图](/api/images/a/raw)\n\n${source}`,
      polishedMarkdown: '会议决定明天发布。',
    }])
    const wrapper = await view()
    await flushPromises()
    expect(wrapper.get('[data-note-id="a"] .note-body textarea').attributes('value')).toContain('会议决定明天发布。')
    expect(wrapper.get('[data-note-id="a"] .note-body textarea').attributes('value')).toContain('![文章总览图]')
    expect(wrapper.find('.version-toggle').exists()).toBe(false)
    await wrapper.get('[data-note-id="a"]').trigger('dblclick')
    expect(wrapper.get<HTMLTextAreaElement>('[data-note-id="a"] .note-composer textarea').element.value).toContain('会议决定明天发布。')
    await wrapper.get('[data-note-id="a"] .note-composer').findAll('button').find(button => button.text() === '收起编辑')!.trigger('click')
    await openFilters(wrapper)
    await wrapper.findAll('.filter-group')[2]!.findAll('button')[1]!.trigger('click')
    await wrapper.findAll('.filter-group')[3]!.findAll('button')[1]!.trigger('click')
    expect(wrapper.findAll('.note-card').map(card => card.attributes('data-note-id'))).toEqual(['a'])
    wrapper.unmount()
  })

  it('正文输入井号唤起最近常用标签，并将选择加入标签', async () => {
    localStorage.setItem('wao-note-tag-usage-v1:wg', JSON.stringify({ 高频: { count: 6, lastUsed: 1 }, 最近: { count: 3, lastUsed: 99 } }))
    const wrapper = mount(NoteComposer, { props: { tagOptions: ['最近', '高频'] }, global: { stubs: { MarkdownEditor: Editor } }, attachTo: document.body })
    await wrapper.find('textarea').trigger('keydown', { key: '#' })
    await new Promise(resolve => window.setTimeout(resolve, 5))
    const menu = document.body.querySelector('.tag-suggestion-menu')
    expect(menu?.textContent).toContain('最近常用')
    expect(menu?.querySelector('button')?.textContent).toContain('高频')
    menu?.querySelector('button')?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    expect(wrapper.find('.tag-chip').text()).toContain('高频')
    wrapper.unmount()
  })

})
