import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import QuickNotePopover from '@/components/overlays/QuickNotePopover.vue'
import NoteComposer from '@/components/notes/NoteComposer.vue'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'
import { readNoteDraft, writeNoteDraft } from '@/utils/noteDraftStorage'
import DigitalIdentitySettings from '@/components/ui/DigitalIdentitySettings.vue'
import { http } from '@/api/http'
import type { Doc } from '@/types'

const companionSave = vi.hoisted(() => vi.fn())
vi.mock('@/api/noteAiCompanion', () => ({ saveCompanionDraft: companionSave }))

const Editor = defineComponent({ props: ['modelValue', 'readonly'], emits: ['update:modelValue'], template: '<textarea class="tiptap" :value="modelValue" :readonly="readonly" @input="$emit(\'update:modelValue\', $event.target.value)" />' })
const options = { attachTo: document.body, global: { stubs: { MarkdownEditor: Editor } } }
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia())
  useSessionStore().accept({ member: { id: 'wg', name: '测试成员', initial: '测', color: '#4358d6', role: '成员' } } as never)
  useDataStore().apiLive = true
  companionSave.mockReset()
})
describe('原地快速小记', () => {
  it('旧草稿 id 指向已有图文时另存第二篇，第一篇正文和配图保持不变', async () => {
    const first: Doc = { id: 'first-note', title: '第一篇', author: 'wg', created: '2026-01-01', updated: '2026-01-01',
      kbId: 'kb-notes', folderId: 'notes-folder', rev: 4, links: [], backlinks: [], blocks: [],
      content: { version: 1, markdown: '第一篇正文\n\n![第一篇配图](/api/images/first/raw)', document: null } }
    const data = useDataStore()
    data.applyRemoteDoc(first)
    const firstSnapshot = JSON.stringify(data.docById(first.id))
    writeNoteDraft('wao-quick-note-draft-v1:wg', JSON.stringify({ id: first.id, text: '第二篇独立正文' }))
    const put = vi.spyOn(http, 'put').mockImplementation(async (url, payload) => {
      const id = String(url).split('/').pop()!
      if (id === first.id) throw new Error('小记文档 id 已被占用')
      const request = payload as { title: string; content: Doc['content']; blocks: Doc['blocks'] }
      return { data: { data: { ...first, ...request, id, rev: 1 } } }
    })
    const wrapper = mount(NoteComposer, options)
    try {
      await flushPromises(); await wrapper.get('.save-note').trigger('click'); await flushPromises()
      expect(put).toHaveBeenCalledTimes(2)
      const secondId = wrapper.emitted('saved')![0]![0] as string
      expect(secondId).not.toBe(first.id)
      expect(data.docById(secondId)?.content?.markdown).toBe('第二篇独立正文')
      expect(JSON.stringify(data.docById(first.id))).toBe(firstSnapshot)
      expect(readNoteDraft('wao-quick-note-draft-v1:wg')).toBeNull()
    } finally { wrapper.unmount(); put.mockRestore() }
  })
  it('明确的 id 占用只更换一次标识，图文标签在再次失败及重载后保留', async () => {
    const key = 'wao-quick-note-draft-v1:wg'
    const rich = { version: 1, markdown: '重要正文\n![截图](/uploads/test-image.png)', document: { type: 'doc', content: [{ type: 'paragraph', content: [{ type:'text', text:'重要正文' }] }, { type:'image', attrs:{ src:'/uploads/test-image.png' } }] } }
    writeNoteDraft(key, JSON.stringify({ id:'occupied-note', text:rich.markdown, content:rich, tags:['思考'], generatedTitle:'保留的标题' }))
    const add = vi.spyOn(useDataStore(), 'addNote').mockRejectedValueOnce(new Error('小记文档 id 已被占用')).mockRejectedValueOnce(new Error('网络中断')).mockResolvedValueOnce('saved-note')
    let wrapper = mount(NoteComposer, options)
    await flushPromises(); await wrapper.get('.save-note').trigger('click'); await flushPromises()
    expect(add).toHaveBeenCalledTimes(2)
    const replacement = add.mock.calls[1]![2]
    expect(replacement).not.toBe('occupied-note')
    expect(replacement).toMatch(/^[a-f0-9]{32}$/)
    const savedDraft = JSON.parse(readNoteDraft(key)!)
    expect(savedDraft).toMatchObject({ id:replacement, text:rich.markdown, content:rich, tags:['思考'], generatedTitle:'保留的标题' })
    expect(add.mock.calls[1]!.slice(3,5)).toEqual([rich,['思考']])
    wrapper.unmount(); wrapper = mount(NoteComposer, options)
    await flushPromises(); await wrapper.get('.save-note').trigger('click'); await flushPromises()
    expect(add.mock.calls[2]![2]).toBe(replacement)
    expect(readNoteDraft(key)).toBeNull()
    wrapper.unmount()
  })
  it('连续明确占用也不会无限自动重试，保留新的草稿 id', async () => {
    const add = vi.spyOn(useDataStore(), 'addNote').mockRejectedValue(new Error('小记文档 id 已被占用'))
    const wrapper = mount(NoteComposer, options)
    await flushPromises(); await wrapper.get('textarea').setValue('保留输入')
    await wrapper.get('.save-note').trigger('click'); await flushPromises()
    expect(add).toHaveBeenCalledTimes(2)
    expect(JSON.parse(readNoteDraft('wao-quick-note-draft-v1:wg')!).id).toBe(add.mock.calls[1]![2])
    expect(wrapper.text()).toContain('小记文档 id 已被占用')
    wrapper.unmount()
  })

  it('本次人物选择随草稿恢复，重试传递同一选择且成功后清理', async () => {
    const add = vi.spyOn(useDataStore(), 'addNote').mockRejectedValueOnce(new Error('离线')).mockResolvedValueOnce('saved-note')
    let wrapper = mount(NoteComposer, options)
    await flushPromises()
    await wrapper.get('textarea').setValue('保存本次人物选择')
    wrapper.findComponent(DigitalIdentitySettings).vm.$emit('update:modelValue', { mode: 'none' })
    await flushPromises()
    wrapper.unmount()
    wrapper = mount(NoteComposer, options)
    await flushPromises()
    expect(wrapper.findComponent(DigitalIdentitySettings).props('modelValue')).toEqual({ mode: 'none' })
    await wrapper.get('.save-note').trigger('click'); await flushPromises()
    expect(readNoteDraft('wao-quick-note-draft-v1:wg')).toContain('"mode":"none"')
    await wrapper.get('.save-note').trigger('click'); await flushPromises()
    expect(add.mock.calls[0]![5]).toEqual({ identitySelection: { mode: 'none' } })
    expect(add.mock.calls[1]![5]).toEqual(add.mock.calls[0]![5])
    expect(readNoteDraft('wao-quick-note-draft-v1:wg')).toBeNull()
    wrapper.unmount()
  })
  it('本次指定缺少confirmed版本时保护正文并阻止请求', async () => {
    const add = vi.spyOn(useDataStore(), 'addNote')
    const wrapper = mount(NoteComposer, options)
    await flushPromises()
    await wrapper.get('textarea').setValue('指定人物的正文')
    wrapper.findComponent(DigitalIdentitySettings).vm.$emit('update:modelValue', { mode: 'explicit', subjectId: 'one' })
    await wrapper.get('.save-note').trigger('click'); await flushPromises()
    expect(add).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请选择成员与已确认的形象版本')
    expect(readNoteDraft('wao-quick-note-draft-v1:wg')).toContain('指定人物的正文')
    wrapper.unmount()
  })
  it('对话来源隐藏形象入口且忽略保留的explicit选择，仍用无自动配图保存', async () => {
    writeNoteDraft('wao-quick-note-draft-v1:wg', JSON.stringify({ id: 'draft-one', text: '对话正文', companionMessageId: 'message-one', identitySelection: { mode: 'explicit', subjectId: 'one' } }))
    companionSave.mockRejectedValueOnce(new Error('对话保存可重试'))
    const submitNote = vi.fn()
    const wrapper = mount(NoteComposer, { ...options, props: { submitNote } })
    await flushPromises()
    expect(wrapper.findComponent(DigitalIdentitySettings).exists()).toBe(false)
    expect(wrapper.text()).toContain('保存后可手动生成')
    await wrapper.get('.save-note').trigger('click'); await flushPromises()
    expect(submitNote).not.toHaveBeenCalled()
    expect(companionSave).toHaveBeenCalledWith(expect.objectContaining({ messageId: 'message-one' }))
    expect(wrapper.text()).toContain('对话保存可重试')
    expect(readNoteDraft('wao-quick-note-draft-v1:wg')).toContain('"mode":"explicit"')
    wrapper.unmount()
  })
  it('刷新屏障保存已收起的浮层草稿，失败阻断且重试沿用同一条小记', async () => {
    const add = vi.spyOn(useDataStore(), 'addNote').mockRejectedValueOnce(new Error('离线，无法保存')).mockResolvedValueOnce('saved-note')
    const wrapper = mount(QuickNotePopover, { ...options, props: { open:true } })
    await flushPromises()
    await wrapper.findComponent(NoteComposer).get('textarea').setValue('更新前必须保存的内容')
    await wrapper.setProps({ open:false })
    function barrier() {
      const requests: Promise<unknown>[] = []
      window.dispatchEvent(new CustomEvent('wao-save-before-refresh', { detail: { waitUntil:(request:Promise<unknown>) => requests.push(request) } }))
      return Promise.all(requests)
    }
    await expect(barrier()).rejects.toThrow('离线，无法保存')
    expect(readNoteDraft('wao-quick-note-draft-v1:wg:floating')).toContain('更新前必须保存的内容')
    await barrier()
    expect(add.mock.calls[0]![2]).toBe(add.mock.calls[1]![2])
    expect(readNoteDraft('wao-quick-note-draft-v1:wg:floating')).toBeNull()
    await barrier()
    expect(add).toHaveBeenCalledTimes(2)
    wrapper.unmount()
    await barrier()
    expect(add).toHaveBeenCalledTimes(2)
  })
  it('独立于小记页新建草稿，收起及重新挂载都恢复自己的内容', async () => {
    const page = mount(NoteComposer, options)
    await flushPromises()
    await page.get('textarea').setValue('页面未完成正文')
    const popover = mount(QuickNotePopover, { ...options, props: { open: true } })
    await flushPromises()
    expect((popover.findComponent(NoteComposer).get('textarea').element as HTMLTextAreaElement).value).toBe('')
    await popover.findComponent(NoteComposer).get('textarea').setValue('浮层独立灵感')
    await popover.setProps({ open: false }); await popover.setProps({ open: true })
    expect((popover.findComponent(NoteComposer).get('textarea').element as HTMLTextAreaElement).value).toBe('浮层独立灵感')
    popover.unmount()
    const restored = mount(QuickNotePopover, { ...options, props: { open: true } })
    await flushPromises()
    expect((restored.findComponent(NoteComposer).get('textarea').element as HTMLTextAreaElement).value).toBe('浮层独立灵感')
    expect(readNoteDraft('wao-quick-note-draft-v1:wg')).toContain('页面未完成正文')
    restored.unmount(); page.unmount()
  })
  it('失败不收起，使用同一 id 重试成功，仅清理浮层草稿', async () => {
    const add = vi.spyOn(useDataStore(), 'addNote').mockRejectedValueOnce(new Error('网络中断')).mockResolvedValueOnce('saved-note')
    const wrapper = mount(QuickNotePopover, { ...options, props: { open: true } })
    await flushPromises(); await wrapper.findComponent(NoteComposer).get('textarea').setValue('不能丢的正文')
    await wrapper.findComponent(NoteComposer).get('.save-note').trigger('click'); await flushPromises()
    expect(wrapper.findComponent(NoteComposer).text()).toContain('网络中断')
    expect(wrapper.emitted('close')).toBeUndefined()
    expect(readNoteDraft('wao-quick-note-draft-v1:wg:floating')).toContain('不能丢的正文')
    await wrapper.findComponent(NoteComposer).get('.save-note').trigger('click'); await flushPromises()
    expect(add.mock.calls[0]![2]).toBe(add.mock.calls[1]![2])
    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(readNoteDraft('wao-quick-note-draft-v1:wg:floating')).toBeNull()
    wrapper.unmount()
  })
  it('空正文不可保存，提交期间不可收起或重复保存', async () => {
    let finish!: (value: string) => void
    const add = vi.spyOn(useDataStore(), 'addNote').mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const wrapper = mount(QuickNotePopover, { ...options, props: { open: true } })
    await flushPromises()
    expect(wrapper.findComponent(NoteComposer).get('.save-note').attributes('disabled')).toBeDefined()
    await wrapper.findComponent(NoteComposer).get('textarea').setValue('保存中保护')
    await wrapper.findComponent(NoteComposer).get('.save-note').trigger('click')
    document.querySelector<HTMLButtonElement>('.close-note')!.click()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await wrapper.findComponent(NoteComposer).get('.save-note').trigger('click')
    expect(add).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('close')).toBeUndefined()
    finish('saved-note'); await flushPromises()
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })
})
