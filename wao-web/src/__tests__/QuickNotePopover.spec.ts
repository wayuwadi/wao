import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import QuickNotePopover from '@/components/overlays/QuickNotePopover.vue'
import NoteComposer from '@/components/notes/NoteComposer.vue'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'
import { readNoteDraft } from '@/utils/noteDraftStorage'

const Editor = defineComponent({ props: ['modelValue', 'readonly'], emits: ['update:modelValue'], template: '<textarea class="tiptap" :value="modelValue" :readonly="readonly" @input="$emit(\'update:modelValue\', $event.target.value)" />' })
const options = { attachTo: document.body, global: { stubs: { MarkdownEditor: Editor } } }
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia())
  useSessionStore().accept({ member: { id: 'wg', name: '测试成员', initial: '测', color: '#4358d6', role: '成员' } } as never)
  useDataStore().apiLive = true
})
describe('原地快速小记', () => {
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
