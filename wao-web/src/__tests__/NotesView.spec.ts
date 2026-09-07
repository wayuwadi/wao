import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import NotesView from '@/views/NotesView.vue'
import NoteComposer from '@/components/notes/NoteComposer.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { apiPatchDoc } from '@/api/board'
import type { Doc } from '@/types'

vi.mock('@/api/board', async importOriginal => ({ ...await importOriginal<object>(), apiPatchDoc: vi.fn() }))
const Editor = defineComponent({
  props: ['modelValue', 'readonly'], emits: ['update:modelValue'],
  template: '<textarea :value="modelValue" :readonly="readonly" @input="$emit(\'update:modelValue\', $event.target.value)" />',
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
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.mocked(apiPatchDoc).mockReset()
    localStorage.clear()
    setActivePinia(createPinia())
    useSessionStore().accept({ member: { id: 'wg', name: '娃哥', initial: '哥', color: '#4358d6', role: '成员' } } as never)
    const data = useDataStore()
    data.apiLive = true
    data.loadStates.docs = 'loaded'
    data.loadStates.knowledge = 'loaded'
    data.kbs = [{ id: 'kb-notes', name: '小记', notes: true, icon: '', color: '', desc: '', project: null, folders: [{ id: 'f', name: '小记', docs: [] }] }]
  })

  it('聚合历史小记并按时间倒序，正文搜索和作者筛选可以组合', async () => {
    const data = useDataStore()
    data.docs.a = doc('a', '旧想法', '2026-09-06T08:00:00')
    data.docs.b = doc('b', '发布清单', '2026-09-07T08:00:00', 'wd')
    data.docs.c = { ...doc('c', '普通文档'), kbId: 'other' }
    const wrapper = await view()
    expect(wrapper.findAll('.note-card').map(card => card.attributes('data-note-id'))).toEqual(['b', 'a'])
    await wrapper.find('input[type="search"]').setValue('发布')
    expect(wrapper.findAll('.note-card')).toHaveLength(1)
    await wrapper.findAll('.filters button')[1]!.trigger('click')
    expect(wrapper.text()).toContain('没有找到相关小记')
    wrapper.unmount()
  })

  it('保存即显示在原页面，清除旧筛选，并为下一条生成新的草稿 id', async () => {
    const data = useDataStore()
    vi.spyOn(data, 'addNote').mockImplementation(async (text, _title, id) => { data.docs[id!] = doc(id!, text); return id! })
    const wrapper = await view()
    await wrapper.find('input').setValue('找不到的词')
    await wrapper.find('textarea').setValue('即时显示的想法')
    const original = JSON.parse(localStorage.getItem('wao-quick-note-draft-v1:wg')!).id
    await wrapper.find('.save-note').trigger('click')
    await flushPromises()
    expect(wrapper.find('.note-card').text()).toContain('即时显示的想法')
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
})
