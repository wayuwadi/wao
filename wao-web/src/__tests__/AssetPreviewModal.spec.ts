import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import AssetPreviewModal from '@/components/files/AssetPreviewModal.vue'
import { fetchFilePreview } from '@/api/board'

vi.mock('@/api/board', () => ({ fetchFilePreview: vi.fn() }))
enableAutoUnmount(afterEach)
afterEach(() => { document.body.innerHTML = ''; vi.clearAllMocks() })

describe('hosted HTML preview isolation', () => {
  it('lets the trusted manual-page shell restore demo storage while keeping AI reports restricted', () => {
    const manual = mount(AssetPreviewModal, {
      props: { asset: { kind: 'html', name: '演示', url: '/api/pages/demo', sourceType: 'MANUAL_UPLOAD' } },
      global: { stubs: { teleport: true } },
    })
    expect(manual.find('iframe').attributes('sandbox')).toContain('allow-same-origin')
    expect(manual.find('iframe').attributes('sandbox')).toContain('allow-forms')

    const report = mount(AssetPreviewModal, {
      props: { asset: { kind: 'html', name: '报告', url: '/api/pages/report', sourceType: 'AI_RUN_REPORT' } },
      global: { stubs: { teleport: true } },
    })
    expect(report.find('iframe').attributes('sandbox')).not.toContain('allow-same-origin')
    expect(report.find('iframe').attributes('sandbox')).not.toContain('allow-forms')
  })
})

it('图片预览接管键盘焦点，Tab 留在弹窗，Esc 关闭并返回原按钮', async () => {
  const trigger = document.createElement('button')
  trigger.textContent = '预览图片'
  document.body.append(trigger)
  trigger.focus()
  const wrapper = mount(AssetPreviewModal, {
    props: { asset: { kind: 'image', name: '封面.png', url: '/image.png' } },
    attachTo: document.body,
    global: { stubs: { teleport: true } },
  })
  await flushPromises()
  const dialog = wrapper.get<HTMLElement>('[role="dialog"]')
  expect(document.activeElement).toBe(dialog.element)
  await dialog.trigger('keydown', { key: 'Tab', shiftKey: true })
  const close = dialog.get<HTMLButtonElement>('button')
  expect(document.activeElement).toBe(close.element)
  await close.trigger('keydown', { key: 'Tab' })
  expect(document.activeElement).toBe(close.element)
  await close.trigger('keydown', { key: 'Escape' })
  expect(wrapper.emitted('close')).toHaveLength(1)
  wrapper.unmount()
  expect(document.activeElement).toBe(trigger)
})

it('Markdown 读取失败可以重试，正文链接处于弹窗焦点循环内', async () => {
  vi.mocked(fetchFilePreview).mockRejectedValueOnce(new Error('预览暂时不可用'))
    .mockResolvedValueOnce({ content: '[查看说明](https://example.test/help)' } as Awaited<ReturnType<typeof fetchFilePreview>>)
  const wrapper = mount(AssetPreviewModal, {
    props: { asset: { kind: 'markdown', name: '说明.md', id: 'file-markdown' } },
    attachTo: document.body,
    global: { stubs: { teleport: true } },
  })
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('预览暂时不可用')
  await wrapper.get('[role="alert"] button').trigger('click')
  await flushPromises()
  const link = wrapper.get<HTMLAnchorElement>('.markdown-body a')
  link.element.focus()
  await link.trigger('keydown', { key: 'Tab' })
  expect(document.activeElement).toBe(wrapper.get('[role="dialog"] button').element)
  expect(fetchFilePreview).toHaveBeenCalledTimes(2)
})
