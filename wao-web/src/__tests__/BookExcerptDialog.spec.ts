import { DOMWrapper, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import BookExcerptDialog from '@/components/notes/BookExcerptDialog.vue'
import { readNoteDraft } from '@/utils/noteDraftStorage'

const read = vi.fn()
const wrappers: ReturnType<typeof mount>[] = []
const layer = () => new DOMWrapper(document.body).get('[aria-label="粘贴为书摘"]')
async function open(insert = vi.fn().mockReturnValue(true), key = 'wao-quick-note-draft-v1:member:excerpt') {
  const wrapper = mount(BookExcerptDialog, { props: { draftKey: key, insertExcerpt: insert } })
  wrappers.push(wrapper)
  void wrapper.vm.openDialog()
  await flushPromises()
  return { wrapper, insert }
}
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear(); read.mockReset()
  vi.stubGlobal('isSecureContext', true)
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { readText: read } })
})
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); document.body.innerHTML = ''; vi.unstubAllGlobals() })

describe('书摘预览与恢复', () => {
  it('只有用户主动打开才读取；修改预览后追加，不自动保存', async () => {
    read.mockResolvedValue('复制的原文')
    const insert = vi.fn().mockReturnValue(true)
    const wrapper = mount(BookExcerptDialog, { props: { draftKey: 'wao-note-edit-v1:member:one:excerpt', insertExcerpt: insert } })
    wrappers.push(wrapper)
    expect(read).not.toHaveBeenCalled()
    await wrapper.vm.openDialog(); await flushPromises()
    expect(layer().get<HTMLTextAreaElement>('[aria-label="书摘原文"]').element.value).toBe('复制的原文')
    await layer().get('[aria-label="书摘来源"]').setValue('书名')
    await layer().get('[aria-label="书摘感想"]').setValue('感想')
    await layer().get('.excerpt-insert').trigger('click'); await flushPromises()
    expect(insert).toHaveBeenCalledWith('复制的原文', '书名', '感想')
    expect(document.body.querySelector('.excerpt-dialog')).toBeNull()
    expect(readNoteDraft('wao-note-edit-v1:member:one:excerpt')).toBeNull()
  })
  it('拒绝读取时可手动粘贴，关闭和重开仍保留未导入文字', async () => {
    read.mockRejectedValue(new Error('NotAllowedError'))
    const { wrapper } = await open()
    expect(layer().text()).toContain('长按书摘框')
    await layer().get('[aria-label="书摘原文"]').setValue('手动粘贴')
    await layer().get('[aria-label="关闭书摘导入"]').trigger('click'); await flushPromises()
    await wrapper.vm.openDialog(); await flushPromises()
    expect(layer().get<HTMLTextAreaElement>('[aria-label="书摘原文"]').element.value).toBe('手动粘贴')
    expect(read).toHaveBeenCalledTimes(1)
  })
  it('异步剪贴板结果不能覆盖用户已经输入的预览', async () => {
    let resolve!: (text: string) => void
    read.mockImplementation(() => new Promise<string>(done => { resolve = done }))
    await open()
    await layer().get('[aria-label="书摘原文"]').setValue('我先手动贴了')
    resolve('较晚的剪贴板内容'); await flushPromises()
    expect(layer().get<HTMLTextAreaElement>('[aria-label="书摘原文"]').element.value).toBe('我先手动贴了')
  })
  it('关闭后到达的读取结果不写入草稿', async () => {
    let resolve!: (text: string) => void
    read.mockImplementation(() => new Promise<string>(done => { resolve = done }))
    await open()
    await layer().get('[aria-label="关闭书摘导入"]').trigger('click'); await flushPromises()
    resolve('已过期内容'); await flushPromises()
    expect(readNoteDraft('wao-quick-note-draft-v1:member:excerpt')).toBeNull()
  })
  it('组件销毁后迟到结果也不写入前一个成员的预览', async () => {
    let resolve!: (text: string) => void
    read.mockImplementation(() => new Promise<string>(done => { resolve = done }))
    const { wrapper } = await open()
    wrapper.unmount()
    resolve('成员切换前的内容'); await flushPromises()
    expect(readNoteDraft('wao-quick-note-draft-v1:member:excerpt')).toBeNull()
  })
  it('剪贴板 API 不可用时仍能手动录入', async () => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined })
    await open()
    expect(layer().text()).toContain('长按书摘框')
    await layer().get('[aria-label="书摘原文"]').setValue('手动输入')
    expect(layer().get('.excerpt-insert').attributes('disabled')).toBeUndefined()
    expect(read).not.toHaveBeenCalled()
  })
  it('未就绪时保留书摘供重试，空白原文不能导入', async () => {
    read.mockResolvedValue('  ')
    const { insert } = await open(vi.fn().mockReturnValue(false))
    expect(layer().get('.excerpt-insert').attributes('disabled')).toBeDefined()
    await layer().get('[aria-label="书摘原文"]').setValue('待导入')
    await layer().get('.excerpt-insert').trigger('click')
    expect(insert).toHaveBeenCalled()
    expect(layer().text()).toContain('书摘仍在')
  })
  it('重挂载恢复来源和感想，其他成员不会读取这份预览', async () => {
    read.mockResolvedValue('原文')
    const { wrapper } = await open()
    await layer().get('[aria-label="书摘来源"]').setValue('来源')
    await layer().get('[aria-label="书摘感想"]').setValue('我的感想')
    wrapper.unmount()
    const restored = await open()
    expect(layer().get<HTMLTextAreaElement>('[aria-label="书摘感想"]').element.value).toBe('我的感想')
    expect(layer().get<HTMLInputElement>('[aria-label="书摘来源"]').element.value).toBe('来源')
    restored.wrapper.unmount()
    read.mockResolvedValue('')
    await open(undefined, 'wao-quick-note-draft-v1:other:excerpt')
    expect(layer().get<HTMLTextAreaElement>('[aria-label="书摘感想"]').element.value).toBe('')
  })
})
