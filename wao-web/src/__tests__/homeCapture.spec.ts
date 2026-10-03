import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import HomeInspirationPaper from '@/components/home/HomeInspirationPaper.vue'
import { useSessionStore } from '@/stores/session'
import { readNoteDraft } from '@/utils/noteDraftStorage'

vi.mock('@/api/board', () => ({ fetchDocs: vi.fn(), fetchKbs: vi.fn() }))
const append = vi.fn<(text: string) => boolean>()
const Composer = defineComponent({ setup(_, { expose }) { expose({ appendPlainText: append }); return {} }, template: '<div>原有草稿</div>' })
function login(id: string) { useSessionStore().accept({ member: { id, name: id, initial: id, color: '#4358D6', role: '成员' } } as never) }
function paper() { return mount(HomeInspirationPaper, { global: { stubs: { NoteComposer: Composer, AModal: { template: '<div><slot /></div>' } } } }) }
beforeEach(() => { localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia()); login('me'); append.mockReset().mockReturnValue(true) })

describe('伙伴接物的草稿边界', () => {
  it('只有明确追加才改正文；编辑器未准备好保留原文并可重试', async () => {
    const w = paper()
    await w.vm.showCapture('递来的文字')
    expect(append).not.toHaveBeenCalled()
    expect(readNoteDraft('wao-quick-note-draft-v1:me:home-capture')).toBe('递来的文字')
    append.mockReturnValueOnce(false)
    const button = () => w.findAll('button').find(b => b.text() === '追加到纸条末尾')!
    await button().trigger('click')
    expect(w.text()).toContain('原文仍在')
    expect((w.find('textarea').element as HTMLTextAreaElement).value).toBe('递来的文字')
    await button().trigger('click')
    expect(append).toHaveBeenLastCalledWith('递来的文字')
    expect(readNoteDraft('wao-quick-note-draft-v1:me:home-capture')).toBeNull()
    expect(w.find('textarea').exists()).toBe(false)
    w.unmount()
  })
  it('刷新恢复待接文字；切成员不串草稿，超长新内容不能覆盖原文', async () => {
    const first = paper()
    await first.vm.showCapture('原文')
    first.unmount()
    const w = paper()
    await w.vm.showCapture()
    expect((w.find('textarea').element as HTMLTextAreaElement).value).toBe('原文')
    expect(await w.vm.showCapture('长'.repeat(10000))).toBe(false)
    expect(readNoteDraft('wao-quick-note-draft-v1:me:home-capture')).toBe('原文')
    login('other')
    await flushPromises()
    await w.vm.showCapture()
    expect((w.find('textarea').element as HTMLTextAreaElement).value).toBe('')
    expect(readNoteDraft('wao-quick-note-draft-v1:other:home-capture')).toBeNull()
    login('me')
    await flushPromises()
    await w.vm.showCapture()
    expect((w.find('textarea').element as HTMLTextAreaElement).value).toBe('原文')
    w.unmount()
  })
})
