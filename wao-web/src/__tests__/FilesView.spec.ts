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
  it.each(['img', 'pages'] as const)('切换账号恢复%s搜索时保留该账号页码，只有用户搜索才重置', async tab => {
    sessionStorage.clear()
    const pinia = createPinia()
    const session = useSessionStore(pinia)
    const login = (id: string) => session.$patch({ account: { accountId: id, member: { id, name: id, initial: id, role: '', color: '' }, identities: [], createdAt: '' } })
    login('first')
    sessionStorage.setItem('wao-browse-v1:second:second:files', JSON.stringify({ tab, imagePage: 3, hostedPage: 3, searchImages: '报告', searchPages: '报告' }))
    const data = useDataStore(pinia)
    vi.spyOn(data, 'refreshFiles').mockResolvedValue(true)
    vi.spyOn(data, 'refreshImages').mockResolvedValue(true)
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
    data.loadStates.images = data.hostedPageLoadState = 'loaded'
    data.imgs = Array.from({ length: 31 }, (_, i) => ({ id: `im-${i}`, name: `页面报告 ${i + 1}`, url: `/images/${i}`, size: '', time: '', from: '', c1: '', c2: '' }))
    data.hostedPages = Array.from({ length: 31 }, (_, i) => ({ id: `page-${i}`, title: `页面报告 ${i + 1}`, fileName: `${i}.html`, slug: `page-${i}`, hasIcon: false, updatedAt: '', url: `/api/pages/page-${i}` }))
    const wrapper = mount(FilesView, { global: { plugins: [pinia], stubs: { Teleport: true, FileSettingsModal: true } } })
    await wrapper.findAll('.seg-btn')[tab === 'img' ? 1 : 2]!.trigger('click')
    await wrapper.get('.resource-search input').setValue('页面')
    await wrapper.get('[aria-label="第 2 页"]').trigger('click')
    login('second')
    await flushPromises()
    expect((wrapper.get('.resource-search input').element as HTMLInputElement).value).toBe('报告')
    expect(wrapper.get('[aria-current="page"]').text()).toBe('3')
    const saved = JSON.parse(sessionStorage.getItem('wao-browse-v1:second:second:files')!)
    expect(saved[tab === 'img' ? 'imagePage' : 'hostedPage']).toBe(3)
    await wrapper.get('.resource-search input').setValue('页面报告')
    expect(wrapper.get('[aria-current="page"]').text()).toBe('1')
    wrapper.unmount()
    sessionStorage.clear()
  })

  it('托管发布失败保留表单和所选文件，忙碌时阻止重复发布，重试后关闭', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    vi.spyOn(data, 'refreshFiles').mockResolvedValue(true)
    vi.spyOn(data, 'refreshImages').mockResolvedValue(true)
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
    let reject!: (reason: Error) => void
    const save = vi.spyOn(data, 'saveHostedPage').mockImplementationOnce(() => new Promise((_, fail) => { reject = fail }))
      .mockResolvedValueOnce({ id: 'p1', title: '网页草稿', slug: 'draft', fileName: 'draft.html', hasIcon: false, updatedAt: '', url: '/api/pages/draft' })
    const wrapper = mount(FilesView, { global: { plugins: [pinia], stubs: { Teleport: true, FileSettingsModal: true } } })
    const html = new File(['<h1>草稿</h1>'], 'draft.html', { type: 'text/html' })
    await wrapper.get('.unified-drop').trigger('drop', { dataTransfer: { files: [html] } })
    await wrapper.get('.page-dialog input').setValue('网页草稿')
    await wrapper.get('.page-dialog').trigger('submit')
    await wrapper.get('.page-dialog').trigger('submit')
    expect(save).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[aria-label="关闭托管 HTML"]').attributes('disabled')).toBeDefined()
    reject(new Error('网络暂不可用'))
    await flushPromises()
    expect(wrapper.get('.page-error').text()).toContain('网络暂不可用')
    expect((wrapper.get('.page-dialog input').element as HTMLInputElement).value).toBe('网页草稿')
    expect(wrapper.get('.html-picker').text()).toContain('draft.html')
    await wrapper.get('.page-dialog').trigger('submit')
    await flushPromises()
    expect(save).toHaveBeenCalledTimes(2)
    expect(save.mock.calls[1]![0].html).toBe(html)
    expect(wrapper.find('.page-dialog').exists()).toBe(false)
    wrapper.unmount()
  })

  it('分别记住三种资源的搜索，并匹配展示名、原名和来源，清除后恢复全部', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    vi.spyOn(data, 'refreshFiles').mockResolvedValue(true)
    vi.spyOn(data, 'refreshImages').mockResolvedValue(true)
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
    data.fileLoadState = data.hostedPageLoadState = data.loadStates.images = 'loaded'
    data.files = [
      { id: 'f-a', name: 'Report.PDF', displayName: '年度总结', by: '小林', time: '', size: '', icon: 'ri-file-line', link: '/a' },
      { id: 'f-b', name: '预算.xlsx', by: '小陈', time: '', size: '', icon: 'ri-file-line', link: '/b' },
    ]
    data.imgs = [{ id: 'i-a', name: '封面.png', from: '任务', time: '', size: '', url: '/a.png', c1: '', c2: '' }]
    data.hostedPages = [{ id: 'p-a', title: '产品说明', fileName: 'index.html', slug: 'product', hasIcon: false, updatedAt: '', url: '/api/pages/product' }]
    const wrapper = mount(FilesView, { global: { plugins: [pinia], stubs: { Teleport: true, FileSettingsModal: true } } })
    await flushPromises()
    const input = () => wrapper.get('.resource-search input')
    await input().setValue('report 小林')
    expect(wrapper.findAll('.file-row')).toHaveLength(1)
    expect(wrapper.get('.file-row').text()).toContain('年度总结')
    await wrapper.findAll('.seg-btn')[1]!.trigger('click')
    expect((input().element as HTMLInputElement).value).toBe('')
    await input().setValue('任务')
    expect(wrapper.findAll('.img-card')).toHaveLength(1)
    expect(wrapper.get('[aria-label="预览图片 封面.png"]').element.tagName).toBe('BUTTON')
    await wrapper.findAll('.seg-btn')[2]!.trigger('click')
    await input().setValue('PRODUCT')
    expect(wrapper.findAll('.file-row')).toHaveLength(1)
    await wrapper.findAll('.seg-btn')[0]!.trigger('click')
    expect((input().element as HTMLInputElement).value).toBe('report 小林')
    await input().setValue('无此文件')
    expect(wrapper.text()).toContain('没有匹配的文件')
    expect(wrapper.text()).not.toContain('还没有文件')
    await wrapper.get('[aria-label="清除资源搜索"]').trigger('click')
    expect(wrapper.findAll('.file-row')).toHaveLength(2)
    wrapper.unmount()
  })

  it('在图床末页开始搜索时回到首个匹配结果，不留下空分页', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    vi.spyOn(data, 'refreshFiles').mockResolvedValue(true)
    vi.spyOn(data, 'refreshImages').mockResolvedValue(true)
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
    data.loadStates.images = 'loaded'
    data.imgs = Array.from({ length: 31 }, (_, i) => ({ id: `im-${i}`, name: `图片 ${i + 1}`, url: `/images/${i}`, size: '', time: '', from: '', c1: '', c2: '' }))
    const wrapper = mount(FilesView, { global: { plugins: [pinia], stubs: { Teleport: true, FileSettingsModal: true } } })
    await wrapper.findAll('.seg-btn')[1]!.trigger('click')
    await wrapper.get('[aria-label="第 3 页"]').trigger('click')
    await wrapper.get('.resource-search input').setValue('图片 1')
    expect(wrapper.findAll('.img-card')).toHaveLength(13)
    expect(wrapper.text()).toContain('图片 1')
    expect(wrapper.find('[aria-label="图床分页"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('大型图床按页渲染，跳到末页和删除末页后不会显示全部卡片', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    vi.spyOn(data, 'refreshFiles').mockResolvedValue(true)
    vi.spyOn(data, 'refreshImages').mockResolvedValue(true)
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
    data.loadStates.images = 'loaded'
    data.imgs = Array.from({ length: 301 }, (_, i) => ({ id: `image-${i}`, name: `图片 ${i + 1}`, url: `/api/images/image-${i}/raw`, size: '1 KB', time: '', from: '', c1: '', c2: '' }))
    const wrapper = mount(FilesView, { global: { plugins: [pinia], stubs: { Teleport: true, DemoBanner: true, FileSettingsModal: true } } })
    await wrapper.findAll('.seg-btn')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.img-card')).toHaveLength(15)
    await wrapper.get('[aria-label="图床分页"] [aria-label="第 21 页"]').trigger('click')
    expect(wrapper.findAll('.img-card')).toHaveLength(1)
    expect(wrapper.find('.img-card').text()).toContain('图片 301')
    data.imgs = data.imgs.slice(0, 300)
    await flushPromises()
    expect(wrapper.findAll('.img-card')).toHaveLength(15)
    expect(wrapper.get('[aria-label="第 20 页"]').attributes('aria-current')).toBe('page')
    wrapper.unmount()
  })

  it('读取中和读取失败不显示空列表，重试成功后才提供首个文件入口', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    const refresh = vi.spyOn(data, 'refreshFiles').mockResolvedValue(false)
    vi.spyOn(data, 'refreshImages').mockResolvedValue(true)
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
    const wrapper = mount(FilesView, { global: { plugins: [pinia], stubs: { Teleport: true, DemoBanner: true, FileSettingsModal: true } } })
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
        stubs: { Teleport: true, DemoBanner: true, FileSettingsModal: true },
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
        stubs: { Teleport: true, DemoBanner: true, FileSettingsModal: true },
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

    await wrapper.find('.page-dialog').trigger('submit')
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
      global: { plugins: [pinia], stubs: { Teleport: true, DemoBanner: true, FileSettingsModal: true } },
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
        stubs: { Teleport: true, DemoBanner: true, FileSettingsModal: true },
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
        stubs: { Teleport: true, DemoBanner: true, FileSettingsModal: true },
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
    await wrapper.find('.page-dialog').trigger('submit')
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
        stubs: { Teleport: true, DemoBanner: true, FileSettingsModal: true, AssetReplaceModal: true },
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
      global: { plugins: [pinia], stubs: { Teleport: true, DemoBanner: true, FileSettingsModal: true } },
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
      global: { plugins: [pinia], stubs: { Teleport: true, DemoBanner: true, FileSettingsModal: true } },
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
    expect(results.text()).toContain('刷新或关闭浏览器后需重新选择')
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
      global: { plugins: [pinia], stubs: { Teleport: true, DemoBanner: true, FileSettingsModal: true } },
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
      global: { plugins: [pinia], stubs: { Teleport: true, DemoBanner: true, FileSettingsModal: true } },
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
      global: { plugins: [pinia], stubs: { Teleport: true, DemoBanner: true, FileSettingsModal: true } },
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
