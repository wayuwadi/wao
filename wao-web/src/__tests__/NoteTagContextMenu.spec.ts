import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import NoteTagContextMenu from '@/components/notes/NoteTagContextMenu.vue'
import { apiDeleteNoteTag, apiMergeNoteTag } from '@/api/board'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { replaceCachedNoteTags, replaceTagNames } from '@/utils/noteTagChanges'
import { createNoteDraftStorage } from '@/utils/noteDraftStorage'

vi.mock('@/api/board', async original => ({ ...await original<object>(), apiDeleteNoteTag: vi.fn(), apiMergeNoteTag: vi.fn() }))
const body = () => document.body
async function open(wrapper: ReturnType<typeof mount>, tag = '旧标签', keyboard = false) {
  const anchor = document.createElement('button'); anchor.textContent = tag; body().append(anchor)
  const event = keyboard ? new KeyboardEvent('keydown', { key: 'F10', shiftKey: true, cancelable: true }) : new MouseEvent('contextmenu', { clientX: 9999, clientY: 9999, cancelable: true })
  Object.defineProperty(event, 'currentTarget', { value: anchor })
  ;(wrapper.vm as unknown as { open: (event: MouseEvent | KeyboardEvent, tag: string) => void }).open(event, tag)
  await flushPromises()
  return { event, anchor }
}
function button(text: string) { return [...body().querySelectorAll<HTMLButtonElement>('button')].find(el => el.textContent === text)! }
async function choose(text: string) { button(text).click(); await flushPromises() }
function form() { return body().querySelector<HTMLFormElement>('.note-tag-panel')! }
async function rename(value: string) {
  const input = form().querySelector<HTMLInputElement>('input')!
  input.value = value; input.dispatchEvent(new Event('input', { bubbles: true })); await flushPromises()
}
async function submit() { form().dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await flushPromises() }
describe('标签右键管理', () => {
  beforeEach(() => {
    document.body.innerHTML = ''; localStorage.clear(); sessionStorage.clear(); vi.clearAllMocks()
    setActivePinia(createPinia()); useDataStore().apiLive = true
    useSessionStore().accept({ member: { id: 'me', name: '本人', role: '成员' } } as never)
    vi.mocked(apiMergeNoteTag).mockResolvedValue(undefined); vi.mocked(apiDeleteNoteTag).mockResolvedValue(undefined)
  })
  it('右键不触发写入，菜单提供修改和删除；取消回到标签', async () => {
    const wrapper = mount(NoteTagContextMenu, { props: { choices: ['旧标签'] } })
    const { event, anchor } = await open(wrapper)
    expect(event.defaultPrevented).toBe(true)
    expect(body().querySelector('[role=menu]')?.textContent).toContain('修改标签')
    expect(apiMergeNoteTag).not.toHaveBeenCalled()
    await choose('删除标签'); expect(form().textContent).toContain('文章会保留')
    await choose('取消'); expect(form()).toBeNull(); expect(document.activeElement).toBe(anchor)
    expect(apiDeleteNoteTag).not.toHaveBeenCalled(); wrapper.unmount()
  })
  it('修改支持新名称与已有目标合并，去重并广播成功结果', async () => {
    const wrapper = mount(NoteTagContextMenu, { props: { choices: ['旧标签', '已存在'] } })
    const changed = vi.fn(); window.addEventListener('wao-note-tag-change', changed)
    await open(wrapper); await choose('修改标签'); await rename(' 新名字 '); await submit()
    expect(apiMergeNoteTag).toHaveBeenCalledWith('旧标签', '新名字')
    expect((changed.mock.calls[0]![0] as CustomEvent).detail).toEqual({ source: '旧标签', target: '新名字' })
    await open(wrapper); await choose('修改标签'); await rename('已存在')
    expect(form().textContent).toContain('同名标签已存在')
    await submit(); expect(apiMergeNoteTag).toHaveBeenLastCalledWith('旧标签', '已存在')
    window.removeEventListener('wao-note-tag-change', changed); wrapper.unmount()
  })
  it('失败保留输入及错误，重试成功才发布变更', async () => {
    vi.mocked(apiMergeNoteTag).mockRejectedValueOnce(new Error('暂时离线'))
    const wrapper = mount(NoteTagContextMenu, { props: { choices: ['旧标签'] } })
    await open(wrapper); await choose('修改标签'); await rename('新名字'); await submit()
    expect(form().textContent).toContain('暂时离线'); expect(form().querySelector('input')?.value).toBe('新名字')
    expect(localStorage.getItem('wao-note-tag-change-v1')).toBeNull()
    await submit(); expect(apiMergeNoteTag).toHaveBeenCalledTimes(2); expect(form()).toBeNull(); wrapper.unmount()
  })
  it('保存中阻止重复提交及取消，删除只调用标签接口', async () => {
    let finish!: () => void
    vi.mocked(apiDeleteNoteTag).mockImplementation(() => new Promise(resolve => { finish = () => resolve(undefined) }))
    const wrapper = mount(NoteTagContextMenu, { props: { choices: ['旧标签'] } })
    await open(wrapper); await choose('删除标签'); await submit(); await submit()
    expect(button('取消').disabled).toBe(true); expect(apiDeleteNoteTag).toHaveBeenCalledTimes(1)
    finish(); await flushPromises(); expect(form()).toBeNull()
    expect(apiMergeNoteTag).not.toHaveBeenCalled(); wrapper.unmount()
  })
  it('键盘打开，Esc 关闭，并校验名称及相同名称不写入', async () => {
    const wrapper = mount(NoteTagContextMenu, { props: { choices: ['旧标签'] } })
    await open(wrapper, '旧标签', true); await choose('修改标签')
    await rename(' '); await submit(); expect(form().textContent).toContain('请输入')
    await rename('旧标签'); await submit(); expect(apiMergeNoteTag).not.toHaveBeenCalled()
    await open(wrapper, '旧标签', true); await choose('修改标签')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); await flushPromises()
    expect(form()).toBeNull(); wrapper.unmount()
  })
  it('缓存更新保留各页签正文、基线及版本，不改别的成员', () => {
    const a = createNoteDraftStorage(localStorage, sessionStorage, 'A'), b = createNoteDraftStorage(localStorage, sessionStorage, 'B')
    const draft = (text: string) => JSON.stringify({ id: 'one', text, tags: ['旧标签', '目标'], baseRev: 9, baseNote: { title: '标题', tags: ['旧标签'], content: { markdown: '原文' } } })
    const key = 'wao-note-edit-v1:me:one', other = 'wao-note-edit-v1:other:one'
    a.write(key, draft('A 的草稿')); b.write(key, draft('B 的草稿')); a.write(other, draft('另一成员'))
    replaceCachedNoteTags('me', { source: '旧标签', target: '目标' })
    expect(JSON.parse(a.read(key)!)).toMatchObject({ text: 'A 的草稿', tags: ['目标'], baseRev: 9, baseNote: { tags: ['目标'] } })
    expect(JSON.parse(b.read(key)!)).toMatchObject({ text: 'B 的草稿', tags: ['目标'] })
    expect(JSON.parse(a.read(other)!).tags).toContain('旧标签')
    replaceCachedNoteTags('me', { source: '目标', target: null }); expect(JSON.parse(a.read(key)!).tags).toEqual([])
    expect(replaceTagNames(['甲', '乙', '甲'], { source: '甲', target: '乙' })).toEqual(['乙'])
  })
})
