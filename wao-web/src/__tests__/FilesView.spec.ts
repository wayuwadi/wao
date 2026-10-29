import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import FilesView from '@/views/FilesView.vue'
import { useDataStore } from '@/stores/data'
import { fetchFileSettings } from '@/api/system'
import { useSessionStore } from '@/stores/session'

vi.mock('@/api/system', () => ({
  fetchFileSettings: vi.fn<(typeof import('@/api/system'))['fetchFileSettings']>(),
  saveFileSettings: vi.fn<(typeof import('@/api/system'))['saveFileSettings']>(),
}))

describe('FilesView', () => {
  it('读取中和读取失败不显示空列表，重试成功后才提供首个文件入口', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    const refresh = vi.spyOn(data, 'refreshFiles').mockResolvedValue(false)
    vi.spyOn(data, 'refreshImages').mockResolvedValue(true)
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
    const wrapper = mount(FilesView, { global: { plugins: [pinia], stubs: { DemoBanner: true, FileSettingsModal: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('正在读取文件')
    expect(wrapper.text()).not.toContain('还没有文件')
    data.fileLoadState = 'error'
    await flushPromises()
    expect(wrapper.text()).toContain('文件读取失败')
    expect(wrapper.text()).not.toContain('还没有文件')
    refresh.mockImplementation(async () => { data.fileLoadState = 'loaded'; return true })
    await wrapper.get('.file-drop .wao-button').trigger('click')
    await flushPromises()
    expect(refresh).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('还没有文件')
    expect(wrapper.get('.file-drop .wao-button').text()).toBe('选择文件')
    wrapper.unmount()
  })

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
    data.hostedPageLoadState = 'loaded'
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
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
    data.hostedPageLoadState = 'loaded'
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
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

    expect(uploadImage).toHaveBeenCalledWith(image, '图床', { requirePersisted: true })
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

  it('网页托管按持久化来源区分人工上传与 AI Run 报告', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    data.hostedPageLoadState = 'loaded'
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
    data.hostedPages = [
      {
        id: 'page-manual',
        slug: 'manual',
        title: '人工页面',
        fileName: 'manual.html',
        sourceType: 'MANUAL_UPLOAD',
        hasIcon: false,
        updatedAt: '8月31日 10:00',
        url: '/api/pages/manual',
      },
      {
        id: 'run-report-ar1',
        slug: 'ai-run-ar1',
        title: '视觉优化 · AI 报告',
        fileName: 'report.html',
        sourceType: 'AI_RUN_REPORT',
        hasIcon: false,
        updatedAt: '8月31日 11:00',
        url: '/api/pages/ai-run-ar1',
      },
    ]
    const wrapper = mount(FilesView, {
      global: { plugins: [pinia], stubs: { DemoBanner: true, FileSettingsModal: true } },
    })
    await flushPromises()

    await wrapper.findAll('.seg-btn')[2]!.trigger('click')
    expect(wrapper.findAll('.page-source')).toHaveLength(2)
    expect(wrapper.text()).toContain('人工上传')
    expect(wrapper.text()).toContain('AI Run 报告')

    await wrapper
      .findAll('[aria-label="网页托管类型"] button')
      .find((button) => button.text() === 'AI Run 报告')!
      .trigger('click')
    expect(wrapper.findAll('.file-row')).toHaveLength(1)
    expect(wrapper.text()).toContain('视觉优化 · AI 报告')
    expect(wrapper.text()).not.toContain('人工页面')
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
    data.hostedPageLoadState = 'loaded'
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
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
  it('全局文件和 HTML 列表都能按原资产打开重新上传', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    data.hostedPageLoadState = 'loaded'
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
    vi.spyOn(data, 'refreshFiles').mockResolvedValue(true)
    vi.spyOn(data, 'refreshImages').mockResolvedValue(true)
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
    data.files = [
      {
        id: 'f1',
        name: 'old.pdf',
        displayName: '保留名称',
        size: '1 B',
        icon: 'ri-file-line',
        time: '刚刚',
        by: 'wg',
        link: '/api/files/f1/download',
      },
    ]
    data.hostedPages = [
      {
        id: 'h1',
        slug: 'stable',
        title: '原页面',
        fileName: 'old.html',
        hasIcon: false,
        updatedAt: '刚刚',
        url: '/api/pages/stable',
      },
    ]
    const wrapper = mount(FilesView, {
      global: {
        plugins: [pinia],
        stubs: { DemoBanner: true, FileSettingsModal: true, AssetReplaceModal: true },
      },
    })
    await flushPromises()
    await wrapper.get('button[title="重新上传文件"]').trigger('click')
    const modal = wrapper.findComponent({ name: 'AssetReplaceModal' })
    expect(modal.props('target')).toEqual(
      expect.objectContaining({ kind: 'file', id: 'f1', name: '保留名称' }),
    )
    modal.vm.$emit('close')
    await flushPromises()
    await wrapper.findAll('.seg-btn')[2]!.trigger('click')
    await wrapper.get('button[title="重新上传 HTML"]').trigger('click')
    expect(wrapper.findComponent({ name: 'AssetReplaceModal' }).props('target')).toEqual({
      kind: 'html',
      id: 'h1',
      name: '原页面',
      url: '/api/pages/stable',
    })
    wrapper.unmount()
  })
  it('托管页加载失败明确提示并可重试，不误报空列表', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    const refresh = vi.spyOn(data, 'refreshHostedPages').mockImplementation(async () => {
      data.hostedPageLoadState = 'error'
      data.hostedPageLoadError = '连接失败'
      return false
    })
    const wrapper = mount(FilesView, {
      global: { plugins: [pinia], stubs: { DemoBanner: true, FileSettingsModal: true } },
    })
    await flushPromises()
    await wrapper.findAll('.seg-btn')[2]!.trigger('click')
    expect(wrapper.get('[role="alert"]').text()).toContain('连接失败')
    expect(wrapper.text()).not.toContain('还没有托管网页')
    refresh.mockImplementationOnce(async () => {
      data.hostedPageLoadState = 'loaded'
      return true
    })
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('还没有托管网页')
    wrapper.unmount()
  })
  it('批量部分失败保留逐项结果，重试只提交失败文件', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    const success = {
      id: 'ok',
      name: '成功.txt',
      icon: 'ri-file-line',
      size: '1 B',
      by: '我',
      time: '刚刚',
      link: '/api/files/ok/download',
    }
    const upload = vi
      .spyOn(data, 'uploadFile')
      .mockResolvedValueOnce(success)
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce(success)
    const wrapper = mount(FilesView, {
      global: { plugins: [pinia], stubs: { DemoBanner: true, FileSettingsModal: true } },
    })
    await flushPromises()
    const first = new File(['a'], '成功.txt')
    const second = new File(['b'], '重试.txt')
    await wrapper.get('.unified-drop').trigger('drop', { dataTransfer: { files: [first, second] } })
    await flushPromises()
    const results = wrapper.get('[aria-label="本次上传结果"]')
    expect(results.text()).toContain('已上传 1 项')
    expect(results.text()).toContain('1 项需处理')
    expect(results.text()).toContain('未收到上传成功确认')
    expect(results.text()).toContain('刷新或离开后需重新选择')
    await results.get('[aria-label="重试上传 重试.txt"]').trigger('click')
    await flushPromises()
    expect(upload).toHaveBeenCalledTimes(3)
    expect(upload.mock.calls.map((call) => call[0])).toEqual([first, second, second])
    expect(results.text()).toContain('已上传 2 项')
    expect(results.find('[role="alert"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('单项异常不阻断后续上传，超限项说明如何恢复且不重复请求', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    const upload = vi
      .spyOn(data, 'uploadFile')
      .mockRejectedValueOnce(new Error('网络中断'))
      .mockResolvedValue(null)
    const wrapper = mount(FilesView, {
      global: { plugins: [pinia], stubs: { DemoBanner: true, FileSettingsModal: true } },
    })
    await flushPromises()
    const oversized = new File(['x'], '过大.txt')
    Object.defineProperty(oversized, 'size', { value: 13 * 1024 * 1024 })
    await wrapper
      .get('.unified-drop')
      .trigger('drop', {
        dataTransfer: {
          files: [new File(['a'], '中断.txt'), oversized, new File(['b'], '后续.txt')],
        },
      })
    await flushPromises()
    expect(upload).toHaveBeenCalledTimes(2)
    const results = wrapper.get('[aria-label="本次上传结果"]')
    expect(results.text()).toContain('网络中断')
    expect(results.text()).toContain('超过 12MB 上限，请压缩后重新选择文件')
    expect(results.find('[aria-label="重试上传 过大.txt"]').exists()).toBe(false)
    expect(results.text()).toContain('3 项需处理')
    wrapper.unmount()
  })

  it('图片持久化失败不能显示已上传，也不重复提交处理中条目', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    let resolve!: (value: null) => void
    const upload = vi.spyOn(data, 'uploadImage').mockImplementation(
      () =>
        new Promise((r) => {
          resolve = r
        }),
    )
    const wrapper = mount(FilesView, {
      global: { plugins: [pinia], stubs: { DemoBanner: true, FileSettingsModal: true } },
    })
    await flushPromises()
    const image = new File(['x'], '图片.png', { type: 'image/png' })
    await wrapper.get('.unified-drop').trigger('drop', { dataTransfer: { files: [image] } })
    expect(wrapper.get('[aria-label="本次上传结果"]').text()).toContain('上传中')
    expect(upload).toHaveBeenCalledWith(image, '图床', { requirePersisted: true })
    resolve(null)
    await flushPromises()
    expect(wrapper.get('[aria-label="本次上传结果"]').text()).toContain('已上传 0 项')
    expect(wrapper.get('[aria-label="本次上传结果"]').text()).toContain('1 项需处理')
    wrapper.unmount()
  })

  it('切换账号后清空上传记录且停止队列后续请求', async () => {
    const pinia = createPinia()
    const session = useSessionStore(pinia)
    const data = useDataStore(pinia)
    let resolve!: (value: null) => void
    const upload = vi.spyOn(data, 'uploadFile').mockImplementation(
      () =>
        new Promise((r) => {
          resolve = r
        }),
    )
    const wrapper = mount(FilesView, {
      global: { plugins: [pinia], stubs: { DemoBanner: true, FileSettingsModal: true } },
    })
    await flushPromises()
    await wrapper
      .get('.unified-drop')
      .trigger('drop', {
        dataTransfer: { files: [new File(['a'], '旧账号.txt'), new File(['b'], '排队.txt')] },
      })
    session.$patch({ account: { member: { id: 'other', name: '其他成员' } } } as never)
    await flushPromises()
    resolve(null)
    await flushPromises()
    expect(upload).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[aria-label="本次上传结果"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
