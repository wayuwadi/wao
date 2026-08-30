import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import FilesView from '@/views/FilesView.vue'
import { useDataStore } from '@/stores/data'
import { fetchFileSettings } from '@/api/system'

vi.mock('@/api/system', () => ({
  fetchFileSettings: vi.fn<(typeof import('@/api/system'))['fetchFileSettings']>(),
  saveFileSettings: vi.fn<(typeof import('@/api/system'))['saveFileSettings']>(),
}))

describe('FilesView', () => {
  beforeEach(() => {
    vi.mocked(fetchFileSettings).mockResolvedValue({
      fileMaxMb: 12,
      imageMaxMb: 4,
      downloadCaptchaEnabled: false,
    })
  })

  it('展示真实上传限制并将网页托管默认分页为10条', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    data.hostedPages = Array.from({ length: 11 }, (_, index) => ({
      id: `page-${index + 1}`,
      slug: `page-${index + 1}`,
      title: `托管页 ${index + 1}`,
      fileName: `page-${index + 1}.html`,
      hasIcon: false,
      updatedAt: '2026-08-26T08:00:00',
      url: `/api/pages/page-${index + 1}`,
    }))
    const wrapper = mount(FilesView, {
      global: {
        plugins: [pinia],
        stubs: { DemoBanner: true, FileSettingsModal: true },
      },
    })
    await flushPromises()

    await wrapper.findAll('.seg-btn')[2]!.trigger('click')
    expect(wrapper.findAll('.file-row')).toHaveLength(10)
    expect(wrapper.find('.pagination').text()).toContain('跳至')
    expect(wrapper.text()).not.toContain('托管页 11')

    await wrapper.find('.pagination').get('[aria-label="第 2 页"]').trigger('click')
    expect(wrapper.findAll('.file-row')).toHaveLength(1)
    expect(wrapper.text()).toContain('托管页 11')

    await wrapper.findAll('.seg-btn')[0]!.trigger('click')
    expect(wrapper.text()).toContain('12MB')
    expect(wrapper.text()).toContain('一次性票据')
  })

  it('统一入口按真实文件类型分流，并为 HTML 生成可修改的标题和后缀', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    const saveHostedPage = vi.spyOn(data, 'saveHostedPage').mockResolvedValue({
      id: 'page-1',
      slug: 'wao-dashboard',
      title: 'WAO Dashboard',
      fileName: 'WAO Dashboard.html',
      hasIcon: false,
      updatedAt: '2026-08-28T12:00:00',
      url: '/api/pages/wao-dashboard',
    })
    const uploadImage = vi.spyOn(data, 'uploadImage').mockResolvedValue({
      name: '封面.png',
      url: '/api/images/image-1/raw',
    })
    const uploadFile = vi.spyOn(data, 'uploadFile').mockResolvedValue({
      id: 'file-1',
      name: '说明.pdf',
      icon: 'ri-file-line',
      size: '1 B',
      by: '我',
      time: '刚刚',
      link: '/api/files/file-1/download',
    })
    const wrapper = mount(FilesView, {
      global: {
        plugins: [pinia],
        stubs: { DemoBanner: true, FileSettingsModal: true },
      },
    })
    await flushPromises()

    const image = new File(['image'], '封面.png', { type: 'image/png' })
    const html = new File(['<h1>WAO</h1>'], 'WAO Dashboard.html', { type: 'text/html' })
    const file = new File(['pdf'], '说明.pdf', { type: 'application/pdf' })
    await wrapper.find('.unified-drop').trigger('drop', {
      dataTransfer: { files: [image, html, file] },
    })
    await flushPromises()

    expect(uploadImage).toHaveBeenCalledWith(image, '图床')
    expect(uploadFile).toHaveBeenCalledWith(file)
    expect(wrapper.find('.page-dialog').exists()).toBe(true)
    const inputs = wrapper.findAll('.page-dialog input')
    expect((inputs[0]!.element as HTMLInputElement).value).toBe('WAO Dashboard')
    expect((inputs[1]!.element as HTMLInputElement).value).toBe('wao-dashboard')
    expect(wrapper.find('.html-picker').text()).toContain('WAO Dashboard.html')

    await wrapper.find('.dialog-actions .btn-primary').trigger('submit')
    await flushPromises()
    expect(saveHostedPage).toHaveBeenCalledWith(expect.objectContaining({ html }))
  })

  it('网页托管直接选择 HTML 后也自动补齐默认值', async () => {
    const pinia = createPinia()
    const wrapper = mount(FilesView, {
      global: {
        plugins: [pinia],
        stubs: { DemoBanner: true, FileSettingsModal: true },
      },
    })
    await flushPromises()

    await wrapper.findAll('.seg-btn')[2]!.trigger('click')
    await wrapper.find('.btn-primary').trigger('click')
    const htmlInput = wrapper.find('.page-dialog input[type="file"]')
    Object.defineProperty(htmlInput.element, 'files', {
      value: [new File(['html'], '团队周报.html', { type: 'text/html' })],
      configurable: true,
    })
    await htmlInput.trigger('change')

    const inputs = wrapper.findAll('.page-dialog input')
    expect((inputs[0]!.element as HTMLInputElement).value).toBe('团队周报')
    expect((inputs[1]!.element as HTMLInputElement).value).toMatch(/^page-/)
  })

  it('托管弹窗支持拖入 HTML 和 icon，并在发布时使用已选文件', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    const saveHostedPage = vi.spyOn(data, 'saveHostedPage').mockResolvedValue({
      id: 'page-2',
      slug: 'dragged-page',
      title: 'Dragged Page',
      fileName: 'dragged-page.html',
      hasIcon: true,
      updatedAt: '2026-08-28T12:00:00',
      url: '/api/pages/dragged-page',
    })
    const wrapper = mount(FilesView, {
      global: {
        plugins: [pinia],
        stubs: { DemoBanner: true, FileSettingsModal: true },
      },
    })
    await flushPromises()

    await wrapper.findAll('.seg-btn')[2]!.trigger('click')
    await wrapper.find('.btn-primary').trigger('click')
    const html = new File(['<h1>拖拽页面</h1>'], 'dragged-page.html', { type: 'text/html' })
    const icon = new File(['icon'], 'page-icon.png', { type: 'image/png' })
    await wrapper.find('.html-picker').trigger('drop', { dataTransfer: { files: [html] } })
    await wrapper.find('.icon-picker').trigger('drop', { dataTransfer: { files: [icon] } })

    expect(wrapper.find('.html-picker').text()).toContain('dragged-page.html')
    expect(wrapper.find('.icon-picker').text()).toContain('page-icon.png')
    await wrapper.find('.dialog-actions .btn-primary').trigger('submit')
    await flushPromises()

    expect(saveHostedPage).toHaveBeenCalledWith(
      expect.objectContaining({ html, icon, slug: 'dragged-page', title: 'dragged-page' }),
    )
  })
})
