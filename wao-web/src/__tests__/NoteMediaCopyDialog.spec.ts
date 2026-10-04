import { DOMWrapper, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import NoteMediaCopyDialog from '@/components/notes/NoteMediaCopyDialog.vue'
import { copyToClipboard } from '@/utils/clipboard'
import { copyOriginalImage } from '@/utils/noteMediaClipboard'
vi.mock('@/utils/clipboard', () => ({ copyToClipboard: vi.fn().mockResolvedValue(true) }))
vi.mock('@/utils/noteMediaClipboard', async original => ({ ...await original<object>(), copyOriginalImage: vi.fn() }))
afterEach(() => { vi.clearAllMocks(); document.body.innerHTML = '' })
describe('分步复制图文', () => {
  it('正文到原图保持顺序，失败不推进，重试后可继续', async () => {
    const wrapper = mount(NoteMediaCopyDialog, { props: { content: { version: 1, document: null, markdown: '正文\n\n![第一张](/api/images/one/raw)\n\n![第二张](/api/images/two/raw)' } } })
    await flushPromises()
    const dialog = new DOMWrapper(document.body).get('.note-media-copy-dialog')
    expect(document.activeElement?.textContent).toBe('复制正文')
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
