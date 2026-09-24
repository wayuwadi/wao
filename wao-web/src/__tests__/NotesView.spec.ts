import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
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
import { useUiStore } from '@/stores/ui'
import { apiCreateNoteTag, fetchNoteTags, apiPatchDoc } from '@/api/board'
import type { Doc } from '@/types'
import { fetchPreference, savePreference } from '@/api/favorites'
import { copyText } from '@/utils/clipboard'
vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn().mockResolvedValue('[]'), savePreference: vi.fn().mockResolvedValue(undefined) }))
vi.mock('@/utils/clipboard', () => ({ copyText: vi.fn().mockResolvedValue(undefined) }))

vi.mock('@/api/assistant', () => ({ polishDocumentText: vi.fn() }))
vi.mock('@/api/board', async importOriginal => ({ ...await importOriginal<object>(), apiPatchDoc: vi.fn(), fetchNoteTags: vi.fn().mockResolvedValue([]), apiCreateNoteTag: vi.fn() }))
const Editor = defineComponent({
  props: ['modelValue', 'readonly'], emits: ['update:modelValue'],
  template: '<div><textarea :value="modelValue" :readonly="readonly" @input="$emit(\'update:modelValue\', $event.target.value)" /><slot name="footer-tags" /></div>',
})
function doc(id: string, text: string, created = '2026-09-07T08:00:00', author = 'wg'): Doc {
  return { id, title: text, author, created, updated: created, kbId: 'kb-notes', rev: 3,
    folderId: 'f', blocks: [{ p: text }], links: [], backlinks: [] }
}
async function view() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/notes', component: NotesView }, { path: '/kb/:id', component: { template: '<div />' } }] })
  await router.push('/notes')
  return mount(NotesView, { global: { plugins: [router], stubs: { MarkdownEditor: Editor } } })
}
function composer(note?: Doc) {
  return mount(NoteComposer, { props: { note }, global: { stubs: { MarkdownEditor: Editor } } })
}
describe('小记集中记录空间', () => {
  it('从小记卡片直接复制免登录只读分享页链接', async () => {
    useDataStore().docs.old = doc('old', '要分享的小记')
    const wrapper = await view()
    const share = wrapper.findAll('[data-note-id="old"] .note-actions button').find(button => button.text().includes('复制分享链接'))
    expect(share).toBeDefined()
    await share!.trigger('click')
    expect(copyText).toHaveBeenCalledWith(`${window.location.origin}/s/d/old`, '公开分享链接已复制')
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
    await wrapper.find('[data-note-id="old"] .note-actions button[aria-pressed]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('置顶保存失败')
    expect(wrapper.find('.note-card').attributes('data-note-id')).toBe('old')
    expect(wrapper.find<HTMLTextAreaElement>('textarea').element.value).toBe('未保存草稿')
    await wrapper.find('[data-note-id="old"] .note-actions button[aria-pressed]').trigger('click')
    await flushPromises()
    expect(savePreference).toHaveBeenLastCalledWith('note-pins-v1', '[]')
    expect(wrapper.find('.note-card').attributes('data-note-id')).toBe('new')
    wrapper.unmount()
  })
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.mocked(copyText).mockClear()
    vi.mocked(apiPatchDoc).mockReset()
    vi.mocked(fetchNoteTags).mockResolvedValue([])
    vi.mocked(apiCreateNoteTag).mockReset()
    localStorage.clear()
    setActivePinia(createPinia())
    useSessionStore().accept({ member: { id: 'wg', name: '娃哥', initial: '哥', color: '#4358d6', role: '成员' } } as never)
    const data = useDataStore()
    data.apiLive = true
    data.loadStates.docs = 'loaded'
    data.loadStates.knowledge = 'loaded'
    data.kbs = [{ id: 'kb-notes', name: '小记', notes: true, icon: '', color: '', desc: '', project: null, folders: [{ id: 'f', name: '小记', docs: [] }] }]
  })

  it('清单删除失败保留小记和新建草稿，重试成功后移出清单', async () => {
    const data = useDataStore()
    data.docs.one = doc('one', '待删小记')
    const remove = vi.spyOn(data, 'deleteDoc').mockRejectedValueOnce(new Error('暂时失败'))
      .mockImplementationOnce(async id => { delete data.docs[id] })
    const wrapper = await view()
    await wrapper.find('textarea').setValue('正在写的新小记')
    await wrapper.find('[data-note-id="one"] .card-delete').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-note-id="one"]').exists()).toBe(true)
    expect(wrapper.find('[data-note-id="one"] [role="alert"]').text()).toContain('小记未改变')
    await wrapper.find('[data-note-id="one"] .card-delete-error button').trigger('click')
    await flushPromises()
    expect(remove).toHaveBeenCalledTimes(2)
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
    await wrapper.find('select').setValue('created')
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
    await wrapper.find('[aria-label="编辑区新标签名称"]').setValue('新分类')
    vi.mocked(apiCreateNoteTag).mockRejectedValueOnce(new Error('暂时离线')).mockResolvedValueOnce('新分类')
    await wrapper.find('.new-tag-form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('.new-tag-form').text()).toContain('重试新增')
    expect(wrapper.find('.tag-chip').exists()).toBe(false)
    await wrapper.find('.new-tag-form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('.tag-chip').text()).toContain('新分类')
    expect(wrapper.emitted('tag-created')).toEqual([['新分类']])
    expect(JSON.parse(localStorage.getItem('wao-quick-note-draft-v1:wg')!).tags).toEqual(['新分类'])
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
    await wrapper.find('.card-edit').trigger('click')
    await flushPromises()
    expect(wrapper.find('.note-meta .note-count').exists()).toBe(false)
    expect(wrapper.find('[aria-label="当前正文字数"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('正文图片只在下方显示多张缩略图，点击后可看大图并关闭', async () => {
    const note = doc('images', '有图小记')
    note.content = { version: 1, markdown: '正文\n\n![图一](/api/images/one/raw)\n\n![图二](/api/images/two/raw)',
      document: { type: 'doc', content: [
        { type: 'paragraph', content: [{ type: 'text', text: '正文' }] },
        { type: 'image', attrs: { src: '/api/images/one/raw', alt: '图一' } },
        { type: 'image', attrs: { src: '/api/images/two/raw', alt: '图二' } },
      ] } }
    useDataStore().docs.images = note
    const wrapper = await view()
    const thumbs = wrapper.findAll('[data-note-id="images"] .note-image-thumb')
    expect(thumbs).toHaveLength(2)
    expect(thumbs.map(thumb => thumb.find('img').attributes('src'))).toEqual(['/api/images/one/raw', '/api/images/two/raw'])
    await thumbs[1]!.trigger('click')
    expect(document.body.querySelector('.note-image-lightbox img')?.getAttribute('src')).toBe('/api/images/two/raw')
    document.body.querySelector<HTMLButtonElement>('.note-image-close')?.click()
    await flushPromises()
    expect(document.body.querySelector('.note-image-lightbox')).toBeNull()
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
    await wrapper.findAll('.tag-filter').find(button => button.text().startsWith('历史'))!.trigger('click')
    expect(wrapper.text()).toContain('没有找到相关小记')
    wrapper.unmount()
  })

  it('保存即显示在原页面，清除旧筛选，并为下一条生成新的草稿 id', async () => {
    const data = useDataStore()
    vi.spyOn(data, 'addNote').mockImplementation(async (text, _title, id) => { data.docs[id!] = doc(id!, text); return id! })
    const wrapper = await view()
    await wrapper.find('input[type="search"]').setValue('找不到的词')
    await wrapper.find('textarea').setValue('即时显示的想法')
    const original = JSON.parse(localStorage.getItem('wao-quick-note-draft-v1:wg')!).id
    await wrapper.find('.save-note').trigger('click')
    await flushPromises()
    expect((wrapper.find('.note-card textarea').element as HTMLTextAreaElement).value).toBe('即时显示的想法')
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('')
    expect(localStorage.getItem('wao-quick-note-draft-v1:wg')).toBeNull()
    await wrapper.find('textarea').setValue('第二条')
    expect(JSON.parse(localStorage.getItem('wao-quick-note-draft-v1:wg')!).id).not.toBe(original)
    wrapper.unmount()
  })

  it('兼容旧草稿，保存失败保留正文，同 id 重试；断线拒绝内存假保存', async () => {
    localStorage.setItem('wao-quick-note-draft-v1:wg', JSON.stringify({ id: 'legacy-note', text: '不能丢' }))
    const data = useDataStore()
    const add = vi.spyOn(data, 'addNote').mockRejectedValueOnce(new Error('网关错误')).mockResolvedValueOnce('legacy-note')
    const wrapper = composer()
    await wrapper.find('.save-note').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain('网关错误')
    expect(localStorage.getItem('wao-quick-note-draft-v1:wg')).toContain('不能丢')
    await wrapper.find('.save-note').trigger('click')
    await flushPromises()
    expect(add.mock.calls.map(call => call[2])).toEqual(['legacy-note', 'legacy-note'])
    data.apiLive = false
    await wrapper.find('textarea').setValue('服务尚未连接')
    await wrapper.find('.save-note').trigger('click')
    await flushPromises()
    expect(add).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="alert"]').text()).toContain('服务尚未连接')
    wrapper.unmount()
  })

  it('编辑恢复草稿时保留原始版本，冲突不覆盖正文，成功才更新列表', async () => {
    const note = doc('edit-note', '原文')
    const data = useDataStore()
    data.docs[note.id] = note
    const first = composer(note)
    await first.find('textarea').setValue('修改后的正文')
    first.unmount()
    const second = composer({ ...note, rev: 5 })
    vi.mocked(apiPatchDoc).mockRejectedValueOnce(new Error('文档版本冲突')).mockResolvedValueOnce(doc('edit-note', '修改后的正文'))
    await second.find('.save-note').trigger('click')
    await flushPromises()
    expect(apiPatchDoc).toHaveBeenCalledWith('edit-note', expect.objectContaining({ expectedRev: 3 }))
    expect(second.find('[role="alert"]').text()).toContain('版本冲突')
    expect(second.text()).toContain('保留为新小记')
    expect(data.docs[note.id]!.title).toBe('原文')
    expect(localStorage.getItem('wao-note-edit-v1:wg:edit-note')).toContain('修改后的正文')
    await second.find('.save-note').trigger('click')
    await flushPromises()
    expect(data.docs[note.id]!.title).toBe('修改后的正文')
    expect(localStorage.getItem('wao-note-edit-v1:wg:edit-note')).toBeNull()
    second.unmount()
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
    expect(wrapper.find('.tiptap').attributes('contenteditable')).toBe('true')
    expect(wrapper.find('.tiptap').text()).toBe('')
    expect(wrapper.find('.save-note').attributes('disabled')).toBeDefined()
    expect(localStorage.getItem('wao-quick-note-draft-v1:wg')).toBeNull()
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
    expect(editor.find('button[title="表格"]').exists()).toBe(true)
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
    expect(JSON.parse(localStorage.getItem('wao-quick-note-draft-v1:wg')!).text).toContain('更加自然')
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
    await wrapper.find('.fullscreen-toggle').trigger('click')
    await wrapper.find('button[title="表格"]').trigger('click')
    const rows = wrapper.findAll('tr').length
    await wrapper.find('td').trigger('contextmenu', { clientX: 100, clientY: 100 })
    await flushPromises()
    const menu = document.querySelector<HTMLElement>('.table-context-menu')!
    expect(Number(menu.style.zIndex)).toBeGreaterThan(2100)
    Array.from(menu.querySelectorAll('button')).find(button => button.textContent === '向下插入行')!.click()
    await flushPromises()
    expect(wrapper.findAll('tr')).toHaveLength(rows + 1)
    expect(JSON.parse(localStorage.getItem('wao-quick-note-draft-v1:wg')!).content.document).toBeTruthy()
    await wrapper.trigger('keydown', { key: 'Escape' })
    expect(wrapper.classes()).not.toContain('fullscreen')
    expect(wrapper.findAll('tr')).toHaveLength(rows + 1)
    wrapper.unmount()
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
    if (!saved) expect(localStorage.getItem('wao-quick-note-draft-v1:wg')).toContain(source)
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
    await wrapper.find('select').setValue('created')
    expect(wrapper.findAll('.note-card').map(card => card.attributes('data-note-id'))).toEqual(['b', 'a'])
    await wrapper.findAll('.tag-filter').find(tag => tag.text().includes('工作'))!.trigger('click')
    await wrapper.find('input[type="search"]').setValue('备忘')
    expect(wrapper.findAll('.note-card').map(card => card.attributes('data-note-id'))).toEqual(['a'])
    await wrapper.find('.new-tag-toggle').trigger('click')
    await wrapper.find('[aria-label="新标签名称"]').setValue('灵感')
    vi.mocked(apiCreateNoteTag).mockRejectedValueOnce(new Error('网关不可达')).mockResolvedValueOnce('灵感')
    await wrapper.find('.new-tag-form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('.new-tag-form').text()).toContain('重试新增')
    expect((wrapper.find('[aria-label="新标签名称"]').element as HTMLInputElement).value).toBe('灵感')
    await wrapper.find('.new-tag-form').trigger('submit')
    await flushPromises()
    expect(wrapper.findAll('.tag-filter').some(tag => tag.text().includes('灵感'))).toBe(true)
    expect(apiCreateNoteTag).toHaveBeenNthCalledWith(2, '灵感')
    wrapper.unmount()
  })

  it('全屏保持编辑器实例、正文和标签草稿，刷新恢复后同请求提交标签', async () => {
    const add = vi.spyOn(useDataStore(), 'addNote').mockResolvedValue('tagged-note')
    const first = mount(NoteComposer, { props: { tagOptions: ['灵感'] }, global: { stubs: { MarkdownEditor: Editor } } })
    await first.find('textarea').setValue('全屏仍在的想法')
    await first.find('.add-tag').trigger('click')
    await first.find('.tag-picker button').trigger('click')
    const editorElement = first.find('textarea').element
    await first.find('.fullscreen-toggle').trigger('click')
    expect(first.classes()).toContain('fullscreen')
    expect(first.find('textarea').element).toBe(editorElement)
    await first.trigger('keydown', { key: 'Escape' })
    expect(first.classes()).not.toContain('fullscreen')
    expect(JSON.parse(localStorage.getItem('wao-quick-note-draft-v1:wg')!).tags).toEqual(['灵感'])
    first.unmount()
    const second = composer()
    expect((second.find('textarea').element as HTMLTextAreaElement).value).toBe('全屏仍在的想法')
    expect(second.find('.tag-chip').text()).toContain('灵感')
    await second.find('.save-note').trigger('click')
    await flushPromises()
    expect(add.mock.calls[0]![4]).toEqual(['灵感'])
    expect(localStorage.getItem('wao-quick-note-draft-v1:wg')).toBeNull()
    second.unmount()
  })

  it('在卡片原地编辑、收起和重开不替换左侧新建实例及草稿', async () => {
    useDataStore().docs.a = { ...doc('a', '旧记录'), tags: ['工作'] }
    const wrapper = await view()
    const newEditor = wrapper.get('.writing-pane textarea').element
    await wrapper.get('.writing-pane textarea').setValue('正在写的新草稿')
    await wrapper.get('.note-card').trigger('click')
    expect(wrapper.find('.note-card .note-composer').exists()).toBe(false)
    await wrapper.get('.note-card').trigger('dblclick')
    expect(wrapper.get('.writing-pane textarea').element).toBe(newEditor)
    expect(wrapper.get<HTMLTextAreaElement>('.writing-pane textarea').element.value).toBe('正在写的新草稿')
    expect(wrapper.get<HTMLTextAreaElement>('.note-card textarea').element.value).toBe('旧记录')
    await wrapper.get('.note-card textarea').setValue('卡片内尚未保存的修改')
    await wrapper.get('.note-card .note-composer').findAll('button').find(button => button.text() === '收起编辑')!.trigger('click')
    expect(wrapper.find('.note-card .note-composer').exists()).toBe(false)
    await wrapper.get('.card-edit').trigger('click')
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
    await wrapper.get('.card-edit').trigger('click')
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
    expect(localStorage.getItem('wao-note-edit-v1:wg:a')).toBeNull()
    wrapper.unmount()
  })

  it('筛选和同步不卸载在编卡片，多个卡片独立保留内容与基础版本', async () => {
    const data = useDataStore()
    data.docs.a = doc('a', '记录 A')
    data.docs.b = doc('b', '记录 B')
    const wrapper = await view()
    await wrapper.get('[data-note-id="a"] .card-edit').trigger('click')
    await wrapper.get('[data-note-id="a"] textarea').setValue('A 未保存')
    const editorA = wrapper.get('[data-note-id="a"] textarea').element
    await wrapper.get('[data-note-id="b"] .card-edit').trigger('click')
    await wrapper.get('[data-note-id="b"] textarea').setValue('B 未保存')
    const editorB = wrapper.get('[data-note-id="b"] textarea').element
    await wrapper.get('input[type="search"]').setValue('不匹配任何记录')
    expect(wrapper.get('[data-note-id="a"] textarea').element).toBe(editorA)
    expect(wrapper.get('[data-note-id="b"] textarea').element).toBe(editorB)
    data.docs.a = { ...doc('a', '服务器更新'), rev: 7 }
    await flushPromises()
    expect(wrapper.get<HTMLTextAreaElement>('[data-note-id="a"] textarea').element.value).toBe('A 未保存')
    vi.mocked(apiPatchDoc).mockRejectedValueOnce(new Error('文档版本冲突'))
    await wrapper.get('[data-note-id="a"] .save-note').trigger('click')
    await flushPromises()
    expect(apiPatchDoc).toHaveBeenCalledWith('a', expect.objectContaining({ expectedRev: 3 }))
    expect(wrapper.get('[data-note-id="a"] [role="alert"]').text()).toContain('版本冲突')
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

  it('卡片支持键盘编辑，标签和文档链接双击不会误入编辑', async () => {
    useDataStore().docs.a = { ...doc('a', '键盘编辑的记录'), tags: ['工作'] }
    const wrapper = await view()
    await wrapper.find('.card-tags button').trigger('dblclick')
    await wrapper.find('.note-actions a').trigger('dblclick')
    expect(wrapper.find('.note-card .note-composer').exists()).toBe(false)
    await wrapper.find('.note-card').trigger('keydown', { key: 'Enter' })
    expect(wrapper.get<HTMLTextAreaElement>('.note-card textarea').element.value).toBe('键盘编辑的记录')
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
    const untagged = () => wrapper.findAll('.tag-filter').find(button => button.text().startsWith('无标签'))!
    await untagged().trigger('click')
    expect(wrapper.findAll('.note-card').map(card => card.attributes('data-note-id'))).toEqual(['b'])
    await untagged().trigger('click')
    expect(wrapper.findAll('.note-card')).toHaveLength(2)
    wrapper.unmount()
  })

})
