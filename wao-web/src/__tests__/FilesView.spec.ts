import { config, DOMWrapper, flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import FilesView from '@/views/FilesView.vue'
import { useDataStore } from '@/stores/data'
import { fetchFileSettings } from '@/api/system'
import { useSessionStore } from '@/stores/session'
import type { FileItem, HostedPage } from '@/types'

const route = vi.hoisted(() => ({ query: {} as Record<string, string> }))
vi.mock('vue-router', async (original) => ({
  ...(await original<typeof import('vue-router')>()),
  useRoute: () => route,
}))

vi.mock('@/api/system', () => ({
  fetchFileSettings: vi.fn<(typeof import('@/api/system'))['fetchFileSettings']>(),
  saveFileSettings: vi.fn<(typeof import('@/api/system'))['saveFileSettings']>(),
}))

function file(index: number, values: Partial<FileItem> = {}): FileItem {
  return {
    id: `f-${index}`,
    name: `文件${index}.pdf`,
    size: '1 KB',
    byteSize: 1024,
    icon: 'ri-file-line',
    by: '小明',
    time: '11月6日',
    link: `/api/files/f-${index}/download`,
    ...values,
  }
}

function listFixture(files: FileItem[] = []) {
  const pinia = createPinia()
  const data = useDataStore(pinia)
  vi.spyOn(data, 'refreshFiles').mockResolvedValue(true)
  vi.spyOn(data, 'refreshImages').mockResolvedValue(true)
  vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
  data.fileLoadState = data.hostedPageLoadState = data.loadStates.images = 'loaded'
  data.files = files
  const wrapper = mount(FilesView, {
    global: { plugins: [pinia], stubs: { FileSettingsModal: true, AssetPreviewModal: true } },
  })
  return { pinia, data, wrapper }
}

function hostedPage(revision = 0): HostedPage {
  return { id: 'revision-page', title: '原始网页', slug: 'original', fileName: 'original.html', hasIcon: false, updatedAt: '11月6日', url: '/api/pages/original', revision }
}

describe('FilesView', () => {
  it.each(['files', 'img', 'pages'] as const)('切换账号恢复%s搜索时保留该账号页码，只有用户搜索才重置', async tab => {
    const pinia = createPinia(), session = useSessionStore(pinia)
    const login = (id: string) => session.$patch({ account: { accountId: id, member: { id, name: id, initial: id, role: '', color: '' }, identities: [], createdAt: '' } })
    login('first')
    sessionStorage.setItem('wao-browse-v1:second:second:files', JSON.stringify({ tab, filePage: 3, imagePage: 3, hostedPage: 3, searchFiles: '报告', searchImages: '报告', searchPages: '报告' }))
    const data = useDataStore(pinia)
    vi.spyOn(data, 'refreshFiles').mockResolvedValue(true)
    vi.spyOn(data, 'refreshImages').mockResolvedValue(true)
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
    const loadResources = () => {
      data.files = Array.from({ length: 61 }, (_, index) => file(index, { name: `页面报告 ${index + 1}.pdf` }))
      data.imgs = Array.from({ length: 31 }, (_, index) => ({ id: `im-${index}`, name: `页面报告 ${index + 1}`, url: `/images/${index}`, size: '', time: '', from: '', c1: '', c2: '' }))
      data.hostedPages = Array.from({ length: 31 }, (_, index) => ({ id: `page-${index}`, title: `页面报告 ${index + 1}`, fileName: `${index}.html`, slug: `page-${index}`, hasIcon: false, updatedAt: '', url: `/api/pages/page-${index}` }))
      data.fileLoadState = data.loadStates.images = data.hostedPageLoadState = 'loaded'
    }
    loadResources()
    const wrapper = mount(FilesView, { global: { plugins: [pinia], stubs: { FileSettingsModal: true } } })
    try {
      await wrapper.findAll('.seg-btn')[tab === 'files' ? 0 : tab === 'img' ? 1 : 2]!.trigger('click')
      await wrapper.get('.resource-search input').setValue('页面')
      await wrapper.get('[aria-label="第 2 页"]').trigger('click')
      login('second'); await flushPromises()
      expect(data.files).toHaveLength(0)
      loadResources(); await flushPromises()
      expect((wrapper.get('.resource-search input').element as HTMLInputElement).value).toBe('报告')
      expect(wrapper.get('[aria-current="page"]').text()).toBe('3')
      const saved = JSON.parse(sessionStorage.getItem('wao-browse-v1:second:second:files')!)
      expect(saved[tab === 'files' ? 'filePage' : tab === 'img' ? 'imagePage' : 'hostedPage']).toBe(3)
      await wrapper.get('.resource-search input').setValue('页面报告')
      expect(wrapper.get('[aria-current="page"]').text()).toBe('1')
    } finally { wrapper.unmount() }
  })

  it('托管真实弹窗聚焦名称，发布按钮位于固定页脚，取消返回原入口', async () => {
    const pinia = createPinia(), data = useDataStore(pinia)
    vi.spyOn(data, 'refreshFiles').mockResolvedValue(true)
    vi.spyOn(data, 'refreshImages').mockResolvedValue(true)
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
    const wrapper = mount(FilesView, { attachTo: document.body, global: { plugins: [pinia], stubs: { teleport: false, FileSettingsModal: true } } })
    try {
      await wrapper.findAll('.seg-btn')[2]!.trigger('click')
      const trigger = wrapper.get<HTMLButtonElement>('.ftop .btn-primary')
      trigger.element.focus(); await trigger.trigger('click'); await flushPromises()
      const dialog = new DOMWrapper(document.querySelector('[aria-label="托管 HTML"]')!)
      const input = dialog.get<HTMLInputElement>('.page-dialog input')
      expect(document.activeElement).toBe(input.element)
      const form = dialog.get('form').element
      const publish = dialog.get<HTMLButtonElement>('.dialog-footer .btn-primary')
      expect(form.contains(publish.element)).toBe(false)
      expect(publish.element.form).toBe(form)
      await dialog.get('.dialog-actions button').trigger('click'); await flushPromises()
      expect(document.querySelector('[aria-label="托管 HTML"]')).toBeNull()
      expect(document.activeElement).toBe(trigger.element)
    } finally { wrapper.unmount() }
  })

  it('清除资源筛选后将焦点交还搜索，可继续输入', async () => {
    const pinia = createPinia(), data = useDataStore(pinia)
    vi.spyOn(data, 'refreshFiles').mockResolvedValue(true)
    vi.spyOn(data, 'refreshImages').mockResolvedValue(true)
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
    data.files = [file(1)]; data.fileLoadState = 'loaded'
    const wrapper = mount(FilesView, { attachTo: document.body, global: { plugins: [pinia], stubs: { FileSettingsModal: true } } })
    try {
      const input = wrapper.get<HTMLInputElement>('.resource-search input')
      await input.setValue('无结果')
      const clear = wrapper.get<HTMLButtonElement>('.resource-summary button')
      clear.element.focus(); await clear.trigger('click'); await flushPromises()
      expect(input.element.value).toBe('')
      expect(document.activeElement).toBe(input.element)
      expect(wrapper.findAll('.file-row')).toHaveLength(1)
    } finally { wrapper.unmount() }
  })

  it('网页编辑保存使用打开时的版本快照，并将版本传给HTML替换窗口', async () => {
    const { data, wrapper } = listFixture()
    const original = hostedPage(0)
    data.hostedPages = [original]
    const save = vi.spyOn(data, 'saveHostedPage').mockResolvedValue({ ...original, revision: 1 })
    await flushPromises()
    await wrapper.findAll('.seg-btn')[2]!.trigger('click')
    await wrapper.get('[aria-label="修改网页 原始网页"]').trigger('click')
    data.hostedPages[0]!.revision = 8
    await wrapper.get('.page-dialog input').setValue('我的网页')
    await wrapper.get('.page-dialog').trigger('submit')
    await flushPromises()
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ id: original.id, expectedRevision: 0, title: '我的网页' }))
    await wrapper.get('[title="重新上传 HTML"]').trigger('click')
    expect(wrapper.findComponent({ name: 'AssetReplaceModal' }).props('target')).toEqual(expect.objectContaining({ kind: 'html', id: original.id, revision: 8 }))
    wrapper.unmount()
  })

  it('网页409保留所有草稿，读取失败不能覆盖，重新发布始终比较最新版本', async () => {
    const { data, wrapper } = listFixture()
    const original = hostedPage(3)
    data.hostedPages = [original]
    await flushPromises()
    const conflict = Object.assign(new Error('网页已更新'), { status: 409 })
    const save = vi.spyOn(data, 'saveHostedPage').mockRejectedValueOnce(conflict).mockRejectedValueOnce(conflict).mockResolvedValueOnce({ ...original, revision: 7 })
    vi.mocked(data.refreshHostedPages).mockResolvedValueOnce(false)
    await wrapper.findAll('.seg-btn')[2]!.trigger('click')
    await wrapper.get('[aria-label="修改网页 原始网页"]').trigger('click')
    const inputs = wrapper.findAll('.page-dialog input:not([type="file"])')
    await inputs[0]!.setValue('我的草稿标题')
    await inputs[1]!.setValue('my-draft-link')
    const html = new File(['<h1>草稿</h1>'], 'draft.html', { type: 'text/html' })
    const icon = new File(['icon'], 'draft.png', { type: 'image/png' })
    await wrapper.get('.html-picker').trigger('drop', { dataTransfer: { files: [html] } })
    await wrapper.get('.icon-picker').trigger('drop', { dataTransfer: { files: [icon] } })
    await wrapper.get('.page-dialog').trigger('submit')
    await flushPromises()
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ expectedRevision: 3, title: '我的草稿标题', slug: 'my-draft-link', html, icon }))
    expect(wrapper.get('[aria-label="网页版本冲突"]').text()).toContain('最新版本读取失败')
    expect(wrapper.get('.dialog-actions .btn-primary').attributes('disabled')).toBeDefined()
    await wrapper.get('.page-dialog').trigger('submit')
    expect(save).toHaveBeenCalledTimes(1)
    expect((inputs[0]!.element as HTMLInputElement).value).toBe('我的草稿标题')
    expect(wrapper.get('.html-picker').text()).toContain('draft.html')
    vi.mocked(data.refreshHostedPages).mockImplementationOnce(async () => { data.hostedPages = [{ ...original, title: '别处的新标题', revision: 5 }]; return true })
    await wrapper.get('.page-conflict button').trigger('click')
    await flushPromises()
    expect(wrapper.get('.page-conflict').text()).toContain('别处的新标题')
    expect(save).toHaveBeenCalledTimes(1)
    vi.mocked(data.refreshHostedPages).mockImplementationOnce(async () => { data.hostedPages = [{ ...original, title: '再次更新的标题', revision: 6 }]; return true })
    await wrapper.findAll('.page-conflict-actions button')[1]!.trigger('click')
    await flushPromises()
    expect(save).toHaveBeenNthCalledWith(2, expect.objectContaining({ expectedRevision: 5, html, icon }))
    expect(wrapper.get('.page-conflict').text()).toContain('再次更新的标题')
    await wrapper.findAll('.page-conflict-actions button')[1]!.trigger('click')
    await flushPromises()
    expect(save).toHaveBeenNthCalledWith(3, expect.objectContaining({ expectedRevision: 6, title: '我的草稿标题', slug: 'my-draft-link', html, icon }))
    expect(wrapper.find('.page-dialog').exists()).toBe(false)
    wrapper.unmount()
  })

  it('网页冲突使用最新版本会明确舍弃本次文件，后续保存仍带最新版本', async () => {
    const { data, wrapper } = listFixture()
    const original = hostedPage(1)
    data.hostedPages = [original]
    await flushPromises()
    const save = vi.spyOn(data, 'saveHostedPage').mockRejectedValueOnce(Object.assign(new Error('网页已更新'), { status: 409 })).mockResolvedValueOnce({ ...original, revision: 5 })
    vi.mocked(data.refreshHostedPages).mockImplementationOnce(async () => { data.hostedPages = [{ ...original, title: '最新标题', slug: 'latest', revision: 4 }]; return true })
    await wrapper.findAll('.seg-btn')[2]!.trigger('click')
    await wrapper.get('[aria-label="修改网页 原始网页"]').trigger('click')
    const html = new File(['html'], 'draft.html', { type: 'text/html' })
    await wrapper.get('.html-picker').trigger('drop', { dataTransfer: { files: [html] } })
    await wrapper.get('.page-dialog').trigger('submit')
    await flushPromises()
    expect(wrapper.get('.page-conflict').text()).toContain('使用最新版本会放弃本次修改')
    await wrapper.findAll('.page-conflict-actions button')[0]!.trigger('click')
    expect(wrapper.find('.page-conflict').exists()).toBe(false)
    expect((wrapper.get('.page-dialog input').element as HTMLInputElement).value).toBe('最新标题')
    expect(wrapper.get('.html-picker').text()).not.toContain('draft.html')
    expect(save).toHaveBeenCalledTimes(1)
    await wrapper.get('.page-dialog').trigger('submit')
    await flushPromises()
    expect(save).toHaveBeenLastCalledWith(expect.objectContaining({ expectedRevision: 4, title: '最新标题', slug: 'latest', html: undefined }))
    wrapper.unmount()
  })

  it('网页冲突重读遇账号切换后不会污染新账号窗口', async () => {
    const { pinia, data, wrapper } = listFixture()
    data.hostedPages = [hostedPage(1)]
    await flushPromises()
    const save = vi.spyOn(data, 'saveHostedPage').mockRejectedValueOnce(Object.assign(new Error('网页已更新'), { status: 409 }))
    let resolveRead!: (value: boolean) => void
    vi.mocked(data.refreshHostedPages).mockImplementationOnce(() => new Promise(resolve => { resolveRead = resolve }))
    await wrapper.findAll('.seg-btn')[2]!.trigger('click')
    await wrapper.get('[aria-label="修改网页 原始网页"]').trigger('click')
    await wrapper.get('.page-dialog input').setValue('旧账号草稿')
    await wrapper.get('.page-dialog').trigger('submit')
    await flushPromises()
    expect(wrapper.get('.page-conflict').text()).toContain('正在读取最新版本')
    useSessionStore(pinia).account = { accountId: 'page-other', member: { id: 'other', name: '乙', initial: '乙', color: '', role: '' }, identities: [], createdAt: '' }
    await flushPromises()
    data.hostedPages = [{ ...hostedPage(20), title: '新账号网页' }]
    await wrapper.findAll('.seg-btn')[2]!.trigger('click')
    await wrapper.get('[aria-label="修改网页 新账号网页"]').trigger('click')
    resolveRead(true)
    await flushPromises()
    expect(wrapper.find('.page-conflict').exists()).toBe(false)
    expect((wrapper.get('.page-dialog input').element as HTMLInputElement).value).toBe('新账号网页')
    expect(save).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('大型文件库最多渲染30行，筛选与删除末页都回到有效页码', async () => {
    const { data, wrapper } = listFixture(
      Array.from({ length: 301 }, (_, index) => file(index + 1)),
    )
    await flushPromises()
    expect(wrapper.findAll('[data-file-id]')).toHaveLength(30)
    await wrapper.get('[aria-label="文件分页"] [aria-label="第 11 页"]').trigger('click')
    expect(wrapper.findAll('[data-file-id]')).toHaveLength(1)
    expect(wrapper.get('[data-file-id]').text()).toContain('文件301.pdf')
    data.files = data.files.slice(0, 300)
    await flushPromises()
    expect(wrapper.get('[aria-label="第 10 页"]').attributes('aria-current')).toBe('page')
    expect(wrapper.findAll('[data-file-id]')).toHaveLength(30)
    await wrapper.get('input[type="search"]').setValue('文件2')
    expect(wrapper.get('[data-file-id]').text()).toContain('文件2.pdf')
    expect(wrapper.get('[aria-label="第 1 页"]').attributes('aria-current')).toBe('page')
    wrapper.unmount()
  })

  it('搜索覆盖展示名、原名和上传者，并与类型及大小排序组合', async () => {
    const { wrapper } = listFixture([
      file(1, { name: 'financial.xlsx', displayName: '预算表', byteSize: 4096 }),
      file(2, { name: 'memo.pdf', displayName: '预算说明', byteSize: 1024 }),
      file(3, { name: 'source.ts', displayName: '预算脚本', byteSize: 2048, by: '小红' }),
    ])
    await flushPromises()
    await wrapper.get('input[type="search"]').setValue('预算 小明')
    expect(wrapper.findAll('[data-file-id]')).toHaveLength(2)
    expect(wrapper.get('.resource-summary').text()).toContain('找到 2 / 3 项')
    expect(wrapper.get('.resource-summary').text()).toContain('5 KB')
    expect(wrapper.get('.resource-summary').text()).not.toContain('约')
    await wrapper.get('[aria-label="文件类型"]').setValue('sheet')
    expect(wrapper.findAll('[data-file-id]')).toHaveLength(1)
    await wrapper.get('input[type="search"]').setValue('FINANCIAL')
    expect(wrapper.get('[data-file-id]').attributes('data-file-id')).toBe('f-1')
    await wrapper.get('.resource-summary button').trigger('click')
    await wrapper.get('[aria-label="资源排序"]').setValue('size')
    expect(wrapper.findAll('[data-file-id]').map((row) => row.attributes('data-file-id'))).toEqual([
      'f-1',
      'f-3',
      'f-2',
    ])
    wrapper.unmount()
  })

  it('明确区分筛选无结果并保持各类资源独立查询', async () => {
    const { wrapper } = listFixture([file(1)])
    await flushPromises()
    await wrapper.get('input[type="search"]').setValue('不存在')
    expect(wrapper.text()).toContain('没有匹配的文件')
    expect(wrapper.text()).not.toContain('还没有文件')
    await wrapper.findAll('.seg-btn')[1]!.trigger('click')
    expect((wrapper.get('input[type="search"]').element as HTMLInputElement).value).toBe('')
    await wrapper.get('input[type="search"]').setValue('封面')
    await wrapper.findAll('.seg-btn')[0]!.trigger('click')
    expect((wrapper.get('input[type="search"]').element as HTMLInputElement).value).toBe('不存在')
    await wrapper.get('.file-drop .wao-button').trigger('click')
    expect(wrapper.findAll('[data-file-id]')).toHaveLength(1)
    wrapper.unmount()
  })

  it('外部文件定位清除阻挡筛选并跳转到目标所在页', async () => {
    route.query = { file: 'f-75', findRequest: 'test-1' }
    const { wrapper } = listFixture(Array.from({ length: 90 }, (_, index) => file(index + 1)))
    await flushPromises()
    expect(wrapper.get('[aria-label="第 3 页"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('.search-target').attributes('data-file-id')).toBe('f-75')
    expect(wrapper.findAll('[data-file-id]')).toHaveLength(30)
    wrapper.unmount()
  })

  it('列表刷新中和失败时保留已有图片，缩略图失效可重试且原图入口可键盘访问', async () => {
    const { data, wrapper } = listFixture()
    data.imgs = [
      {
        id: 'im-1',
        name: '封面.png',
        size: '1 MB',
        byteSize: 1048576,
        time: '刚刚',
        from: '知识库',
        url: '/api/images/im-1/raw',
        previewUrl: '/api/images/im-1/preview',
        c1: '',
        c2: '',
      },
    ]
    await wrapper.findAll('.seg-btn')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.get('.img-open img').attributes('src')).toBe('/api/images/im-1/preview')
    expect(wrapper.get('.img-open').element.tagName).toBe('BUTTON')
    expect(wrapper.get('.img-open').attributes('aria-label')).toBe('预览图片 封面.png')
    data.loadStates.images = 'loading'
    await flushPromises()
    expect(wrapper.findAll('.img-card')).toHaveLength(1)
    data.loadStates.images = 'error'
    data.loadErrors.images = '网络失败'
    await flushPromises()
    expect(wrapper.findAll('.img-card')).toHaveLength(1)
    expect(wrapper.get('[role="alert"]').text()).toContain('上次读取')
    await wrapper.get('.img-open img').trigger('error')
    expect(wrapper.get('.thumbnail-fallback').text()).toContain('点击查看原图')
    await wrapper.get('.img-info button').trigger('click')
    expect(wrapper.find('.img-open img').exists()).toBe(true)
    await wrapper.get('.img-open').trigger('click')
    expect(wrapper.findComponent({ name: 'AssetPreviewModal' }).props('asset')).toEqual({
      kind: 'image',
      name: '封面.png',
      url: '/api/images/im-1/raw',
    })
    wrapper.unmount()
  })

  it('浏览条件按账号保存，重新进入恢复而切换成员清空旧条件', async () => {
    const { pinia, wrapper } = listFixture([file(1)])
    const session = useSessionStore(pinia)
    session.account = {
      accountId: 'account-a',
      member: { id: 'member-a', name: '甲', initial: '甲', color: '', role: '' },
      identities: [],
      createdAt: '',
    }
    await flushPromises()
    await wrapper.get('input[type="search"]').setValue('甲的查询')
    await wrapper.get('[aria-label="文件类型"]').setValue('archive')
    wrapper.unmount()
    const second = mount(FilesView, {
      global: { plugins: [pinia], stubs: { FileSettingsModal: true } },
    })
    await flushPromises()
    expect((second.get('input[type="search"]').element as HTMLInputElement).value).toBe('甲的查询')
    expect((second.get('[aria-label="文件类型"]').element as HTMLSelectElement).value).toBe(
      'archive',
    )
    session.account = {
      accountId: 'account-b',
      member: { id: 'member-b', name: '乙', initial: '乙', color: '', role: '' },
      identities: [],
      createdAt: '',
    }
    await flushPromises()
    expect((second.get('input[type="search"]').element as HTMLInputElement).value).toBe('')
    expect((second.get('[aria-label="文件类型"]').element as HTMLSelectElement).value).toBe('all')
    second.unmount()
  })

  it('网页发布校验、重复提交和失败重试保留表单与所选文件', async () => {
    const { data, wrapper } = listFixture()
    let rejectSave!: (reason: Error) => void
    const save = vi.spyOn(data, 'saveHostedPage').mockImplementation(
      () =>
        new Promise((_, reject) => {
          rejectSave = reject
        }),
    )
    const html = new File(['<h1>报告</h1>'], 'report.html', { type: 'text/html' })
    await wrapper.get('.unified-drop').trigger('drop', { dataTransfer: { files: [html] } })
    const inputs = wrapper.findAll('.page-dialog input:not([type="file"])')
    await inputs[1]!.setValue('-invalid-')
    await wrapper.get('.page-dialog').trigger('submit')
    expect(save).not.toHaveBeenCalled()
    expect(wrapper.get('.page-dialog').text()).toContain('首尾不能是中划线')
    await inputs[1]!.setValue('report')
    await wrapper.get('.page-dialog').trigger('submit')
    await wrapper.get('.page-dialog').trigger('submit')
    expect(save).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.dialog-actions .btn-primary').attributes('disabled')).toBeDefined()
    rejectSave(new Error('连接中断'))
    await flushPromises()
    expect(wrapper.get('.page-error').text()).toContain('连接中断')
    expect(wrapper.get('.page-error').text()).toContain('输入与所选文件已保留')
    expect(wrapper.get('.html-picker').text()).toContain('report.html')
    expect((inputs[1]!.element as HTMLInputElement).value).toBe('report')
    await wrapper.get('.dialog-actions button').trigger('click')
    expect(wrapper.text()).toContain('放弃未发布的修改')
    wrapper.unmount()
  })

  it('HTML 和图标的无效文件原位提示，重选合法文件后可以发布', async () => {
    const { data, wrapper } = listFixture()
    const save = vi
      .spyOn(data, 'saveHostedPage')
      .mockResolvedValue({
        id: 'p',
        slug: 'report',
        title: 'report',
        fileName: 'report.html',
        hasIcon: false,
        updatedAt: '',
        url: '/api/pages/report',
      })
    await wrapper.findAll('.seg-btn')[2]!.trigger('click')
    await wrapper.get('.ftop .btn-primary').trigger('click')
    await wrapper
      .get('.html-picker')
      .trigger('drop', { dataTransfer: { files: [new File(['x'], 'bad.txt')] } })
    expect(wrapper.get('.page-dialog').text()).toContain('请选择 .html 或 .htm 文件')
    const html = new File(['<h1>report</h1>'], 'report.html', { type: 'text/html' })
    await wrapper.get('.html-picker').trigger('drop', { dataTransfer: { files: [html] } })
    const oversized = new File(['x'], 'big.png', { type: 'image/png' })
    Object.defineProperty(oversized, 'size', { value: 1024 * 1024 + 1 })
    await wrapper.get('.icon-picker').trigger('drop', { dataTransfer: { files: [oversized] } })
    await wrapper.get('.page-dialog').trigger('submit')
    expect(save).not.toHaveBeenCalled()
    expect(wrapper.get('.page-dialog').text()).toContain('网页图标不能超过 1MB')
    const icon = new File(['icon'], 'ok.png', { type: 'image/png' })
    await wrapper.get('.icon-picker').trigger('drop', { dataTransfer: { files: [icon] } })
    await wrapper.get('.page-dialog').trigger('submit')
    await flushPromises()
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ html, icon }))
    expect(wrapper.find('.page-dialog').exists()).toBe(false)
    wrapper.unmount()
  })

  it('仅有未发布输入时保护浏览器刷新，卸载后解除保护', async () => {
    const { wrapper } = listFixture()
    await flushPromises()
    const initial = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(initial)
    expect(initial.defaultPrevented).toBe(false)
    await wrapper.findAll('.seg-btn')[2]!.trigger('click')
    await wrapper.get('.ftop .btn-primary').trigger('click')
    const blank = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(blank)
    expect(blank.defaultPrevented).toBe(false)
    await wrapper.get('.page-dialog input').setValue('尚未发布的标题')
    const dirty = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(dirty)
    expect(dirty.defaultPrevented).toBe(true)
    wrapper.unmount()
    const after = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(after)
    expect(after.defaultPrevented).toBe(false)
  })

  it('站内路由离开先保留网页表单，明确放弃后才跳转', async () => {
    const { createRouter, createMemoryHistory, RouterView } = await import('vue-router')
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/files', component: FilesView },
        { path: '/away', component: { template: '<div>已离开资源库</div>' } },
      ],
    })
    const pinia = createPinia()
    const data = useDataStore(pinia)
    vi.spyOn(data, 'refreshFiles').mockResolvedValue(true)
    vi.spyOn(data, 'refreshImages').mockResolvedValue(true)
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
    await router.push('/files')
    const wrapper = mount(
      { components: { RouterView }, template: '<RouterView />' },
      { global: { plugins: [pinia, router], stubs: { FileSettingsModal: true } } },
    )
    await flushPromises()
    const html = new File(['<h1>report</h1>'], 'report.html', { type: 'text/html' })
    await wrapper.get('.unified-drop').trigger('drop', { dataTransfer: { files: [html] } })
    const canceled = router.push('/away')
    await flushPromises()
    expect(wrapper.text()).toContain('离开并放弃未发布的修改')
    await wrapper.get('.confirmation-actions button').trigger('click')
    await canceled
    expect(router.currentRoute.value.path).toBe('/files')
    expect(wrapper.get('.html-picker').text()).toContain('report.html')
    const allowed = router.push('/away')
    await flushPromises()
    await wrapper.findAll('.confirmation-actions button')[1]!.trigger('click')
    await allowed
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/away')
    expect(wrapper.text()).toContain('已离开资源库')
    wrapper.unmount()
  })

  it('可选图标校验失败可以清除本次选择并直接发布HTML', async () => {
    const { data, wrapper } = listFixture()
    const save = vi
      .spyOn(data, 'saveHostedPage')
      .mockResolvedValue({
        id: 'p',
        slug: 'report',
        title: 'report',
        fileName: 'report.html',
        hasIcon: false,
        updatedAt: '',
        url: '/api/pages/report',
      })
    const html = new File(['<h1>report</h1>'], 'report.html', { type: 'text/html' })
    await wrapper.get('.unified-drop').trigger('drop', { dataTransfer: { files: [html] } })
    await wrapper
      .get('.icon-picker')
      .trigger('drop', { dataTransfer: { files: [new File(['x'], 'not-an-image.txt')] } })
    expect(wrapper.get('.page-dialog').text()).toContain('请选择图片文件作为网页图标')
    await wrapper
      .findAll('.page-file-field button')
      .find((button) => button.text() === '清除本次图标选择')!
      .trigger('click')
    await wrapper.get('.page-dialog').trigger('submit')
    await flushPromises()
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ html, icon: undefined }))
    wrapper.unmount()
  })

  it('迟到的设置读取不能覆盖刚保存的上传限制', async () => {
    let resolve!: (value: {
      fileMaxMb: number
      imageMaxMb: number
      downloadCaptchaEnabled: boolean
    }) => void
    vi.mocked(fetchFileSettings).mockImplementationOnce(
      () =>
        new Promise((result) => {
          resolve = result
        }),
    )
    const { wrapper } = listFixture()
    await wrapper.get('.settings-button').trigger('click')
    wrapper
      .findComponent({ name: 'FileSettingsModal' })
      .vm.$emit('saved', { fileMaxMb: 20, imageMaxMb: 8, downloadCaptchaEnabled: false })
    await flushPromises()
    resolve({ fileMaxMb: 5, imageMaxMb: 2, downloadCaptchaEnabled: true })
    await flushPromises()
    expect(wrapper.text()).toContain('20MB')
    expect(wrapper.text()).not.toContain('5MB')
    wrapper.unmount()
  })

  it('文件元信息和检索使用成员名称，旧上传者姓名仍可显示', async () => {
    const { pinia, wrapper } = listFixture([
      file(1, { by: 'm1', time: '11月6日' }),
      file(2, { by: '旧用户', time: '' }),
    ])
    useSessionStore(pinia).members = [
      { id: 'm1', name: '测试上传者', initial: '测', color: '', role: '' },
    ]
    await flushPromises()
    expect(wrapper.findAll('.file-sub')[0]!.text()).toBe('测试上传者 · 11月6日')
    expect(wrapper.findAll('.file-sub')[1]!.text()).toBe('旧用户')
    await wrapper.get('input[type="search"]').setValue('测试上传者')
    expect(wrapper.findAll('[data-file-id]')).toHaveLength(1)
    wrapper.unmount()
  })

  it('大型图床按页渲染，跳到末页和删除末页后不会显示全部卡片', async () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    vi.spyOn(data, 'refreshFiles').mockResolvedValue(true)
    vi.spyOn(data, 'refreshImages').mockResolvedValue(true)
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
    data.loadStates.images = 'loaded'
    data.imgs = Array.from({ length: 301 }, (_, i) => ({
      id: `image-${i}`,
      name: `图片 ${i + 1}`,
      url: `/api/images/image-${i}/raw`,
      size: '1 KB',
      time: '',
      from: '',
      c1: '',
      c2: '',
    }))
    const wrapper = mount(FilesView, {
      global: { plugins: [pinia], stubs: { DemoBanner: true, FileSettingsModal: true } },
    })
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
    const wrapper = mount(FilesView, {
      global: { plugins: [pinia], stubs: { DemoBanner: true, FileSettingsModal: true } },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('正在读取文件')
    expect(wrapper.text()).not.toContain('还没有文件')
    data.fileLoadState = 'error'
    await flushPromises()
    expect(wrapper.text()).toContain('文件读取失败')
    expect(wrapper.text()).not.toContain('还没有文件')
    refresh.mockImplementation(async () => {
      data.fileLoadState = 'loaded'
      return true
    })
    await wrapper.get('.file-drop .wao-button').trigger('click')
    await flushPromises()
    expect(refresh).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('还没有文件')
    expect(wrapper.get('.file-drop .wao-button').text()).toBe('选择文件')
    wrapper.unmount()
  })

  beforeEach(() => {
    config.global.stubs = { ...config.global.stubs, teleport: true }
    route.query = {}
    sessionStorage.clear()
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
    expect(wrapper.text()).toContain('分享下载需确认')
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

    expect(uploadImage).toHaveBeenCalledWith(
      image,
      '图床',
      expect.objectContaining({ requirePersisted: true, throwOnError: true }),
    )
    expect(uploadFile).toHaveBeenCalledWith(
      file,
      undefined,
      false,
      expect.objectContaining({ throwOnError: true }),
    )
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
      global: { plugins: [pinia], stubs: { DemoBanner: true, FileSettingsModal: true } },
    })
    await flushPromises()
    const oversized = new File(['x'], '过大.txt')
    Object.defineProperty(oversized, 'size', { value: 13 * 1024 * 1024 })
    await wrapper.get('.unified-drop').trigger('drop', {
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
    expect(upload).toHaveBeenCalledWith(
      image,
      '图床',
      expect.objectContaining({ requirePersisted: true, throwOnError: true }),
    )
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
    await wrapper.get('.unified-drop').trigger('drop', {
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
