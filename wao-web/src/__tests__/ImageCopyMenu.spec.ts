import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ImageCopyMenu from '@/components/ui/ImageCopyMenu.vue'
import { copyText } from '@/utils/clipboard'

vi.mock('@/utils/clipboard', () => ({ copyText: vi.fn() }))
vi.mock('@/composables/toast', () => ({ toast: vi.fn() }))

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
})
