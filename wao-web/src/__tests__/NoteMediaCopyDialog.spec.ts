import { DOMWrapper, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import NoteMediaCopyDialog from '@/components/notes/NoteMediaCopyDialog.vue'
import { copyToClipboard } from '@/utils/clipboard'
import { copyOriginalImage } from '@/utils/noteMediaClipboard'
vi.mock('@/utils/clipboard', () => ({ copyToClipboard: vi.fn().mockResolvedValue(true) }))
vi.mock('@/utils/noteMediaClipboard', async original => ({ ...await original<object>(), copyOriginalImage: vi.fn() }))
afterEach(() => { vi.clearAllMocks(); document.body.innerHTML = '' })
describe('分步复制图文', () => {
  it('交给 Codex 复制全文快照、机器可读链接和全部原图，不改变 ChatGPT 步骤', async () => {
    const content = { version: 1 as const, markdown: '完整开始\n\n![甲](/api/images/one/raw)\n\n完整结束' }
    const wrapper = mount(NoteMediaCopyDialog, { props: { content, noteId: 'source-id', title: '手写标题' }, attachTo: document.body })
    const dialog = new DOMWrapper(document.body)
    expect(dialog.get('.copy-target button:first-child').attributes('aria-current')).toBe('true')
    await dialog.get('.copy-primary').trigger('click'); await flushPromises()
    const copied = vi.mocked(copyToClipboard).mock.calls[vi.mocked(copyToClipboard).mock.calls.length - 1]![0]
    expect(copied).toContain('/api/share/docs/source-id.md')
    expect(copied).toContain(content.markdown)
    expect(copied).toContain(`${window.location.origin}/api/images/one/raw`)
    expect(copied).toContain('手写标题')
    expect(dialog.text()).toContain('已复制完整内容')
    await dialog.get('.copy-target button:last-child').trigger('click')
    expect(dialog.get('.copy-primary').text()).toBe('复制正文')
    wrapper.unmount()
  })
  it('正文到原图保持顺序，失败不推进，重试后可继续', async () => {
    const wrapper = mount(NoteMediaCopyDialog, { props: { content: { version: 1, document: null, markdown: '正文\n\n![第一张](/api/images/one/raw)\n\n![第二张](/api/images/two/raw)' } } })
    await flushPromises()
    const dialog = new DOMWrapper(document.body).get('.note-media-copy-dialog')
    await dialog.get('.copy-target button:last-child').trigger('click')
    expect(dialog.get('.copy-primary').text()).toBe('复制正文')
    expect(dialog.get('footer button:last-child').attributes('disabled')).toBeDefined()
    await dialog.get('.copy-primary').trigger('click'); await flushPromises()
    expect(copyToClipboard).toHaveBeenCalledWith(expect.stringContaining('[图 1：第一张]'))
    await dialog.get('footer button:last-child').trigger('click')
    vi.mocked(copyOriginalImage).mockRejectedValueOnce(new Error('读取失败')).mockResolvedValueOnce()
    await dialog.get('.copy-primary').trigger('click'); await flushPromises()
    expect(dialog.text()).toContain('读取失败')
    expect(dialog.get('footer button:last-child').attributes('disabled')).toBeDefined()
    await dialog.get('.copy-primary').trigger('click'); await flushPromises()
    expect(copyOriginalImage).toHaveBeenLastCalledWith('/api/images/one/raw')
    await dialog.get('footer button:last-child').trigger('click')
    await dialog.get('.copy-primary').trigger('click'); await flushPromises()
    expect(copyOriginalImage).toHaveBeenLastCalledWith('/api/images/two/raw')
    expect(dialog.text()).toContain('图 2 已复制')
    wrapper.unmount()
  })
})
