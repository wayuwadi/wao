import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ImageCopyMenu from '@/components/ui/ImageCopyMenu.vue'
import { copyText } from '@/utils/clipboard'
import { copyOriginalImage } from '@/utils/noteMediaClipboard'

vi.mock('@/utils/clipboard', () => ({ copyText: vi.fn() }))
vi.mock('@/composables/toast', () => ({ toast: vi.fn() }))
vi.mock('@/utils/noteMediaClipboard', () => ({ copyOriginalImage: vi.fn() }))
afterEach(() => { vi.clearAllMocks(); document.body.innerHTML = '' })

describe('图片右键菜单', () => {
  it('同时提供复制图片和复制图片地址，地址为可打开的绝对地址', async () => {
    const wrapper = mount(ImageCopyMenu, { props: { src: '/api/images/img1/raw', x: 50, y: 70 }, attachTo: document.body })
    expect(document.body.querySelector('.image-copy-menu')?.textContent).toContain('复制图片')
    expect(document.body.querySelector('.image-copy-menu')?.textContent).toContain('复制图片链接')
    document.body.querySelectorAll<HTMLButtonElement>('.image-copy-menu button')[1]?.click()
    await flushPromises()
    expect(copyText).toHaveBeenCalledWith(new URL('/api/images/img1/raw', window.location.href).href, '图片地址已复制')
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('复制过程中保留菜单并阻止重复，成功后才关闭', async () => {
    let finish!: () => void
    vi.mocked(copyOriginalImage).mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve }))
    const wrapper = mount(ImageCopyMenu, { props: { src: '/api/images/original/raw', x: 50, y: 70 }, attachTo: document.body })
    const button = document.body.querySelector<HTMLButtonElement>('.image-copy-actions button')!
    button.click(); button.click()
    await flushPromises()
    expect(copyOriginalImage).toHaveBeenCalledExactlyOnceWith('/api/images/original/raw')
    expect(button.disabled).toBe(true)
    expect(button.textContent).toContain('正在复制')
    expect(wrapper.emitted('close')).toBeUndefined()
    finish(); await flushPromises()
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('失败原因保留在菜单中，可原位重试而不只弹出提示', async () => {
    vi.mocked(copyOriginalImage).mockRejectedValueOnce(new Error('原图读取失败，请重试')).mockResolvedValueOnce(undefined)
    const wrapper = mount(ImageCopyMenu, { props: { src: '/api/images/original/raw', x: 50, y: 70 }, attachTo: document.body })
    const button = document.body.querySelector<HTMLButtonElement>('.image-copy-actions button')!
    button.click(); await flushPromises()
    expect(document.body.querySelector('[role="alert"]')?.textContent).toBe('原图读取失败，请重试')
    expect(button.textContent).toContain('重试复制图片')
    expect(wrapper.emitted('close')).toBeUndefined()
    button.click(); await flushPromises()
    expect(copyOriginalImage).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })
})
