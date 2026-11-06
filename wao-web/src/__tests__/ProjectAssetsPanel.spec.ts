import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectAssetsPanel from '@/components/files/ProjectAssetsPanel.vue'
import { useDataStore } from '@/stores/data'

enableAutoUnmount(afterEach)

describe('ProjectAssetsPanel', () => {
  const mountPanel = (attached = false) => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    vi.spyOn(data, 'refreshFiles').mockResolvedValue(true)
    vi.spyOn(data, 'refreshImages').mockResolvedValue(true)
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
    return {
      data,
      wrapper: mount(ProjectAssetsPanel, {
        ...(attached ? { attachTo: document.body } : {}),
        props: { projectId: 'p1', projectName: 'WAO' },
        global: { plugins: [pinia], stubs: { AssetPreviewModal: true, AssetReplaceModal: true, teleport: true, RouterLink: true } },
      }),
    }
  }

  beforeEach(() => vi.restoreAllMocks())

  it('只展示当前项目真实资产，并给 Markdown、图片和 HTML 提供站内预览入口', async () => {
    const { data, wrapper } = mountPanel()
    data.files = [
      {
        id: 'f1',
        name: '报告.md',
        size: '1 KB',
        icon: 'ri-file-line',
        time: '刚刚',
        by: 'wg',
        link: '/api/files/f1/download',
        projectId: 'p1',
      },
      {
        id: 'f2',
        name: '其它.md',
        size: '1 KB',
        icon: 'ri-file-line',
        time: '刚刚',
        by: 'wg',
        link: '/api/files/f2/download',
        projectId: 'p2',
      },
    ]
    data.imgs = [
      {
        id: 'i1',
        name: '截图.png',
        size: '2 KB',
        time: '刚刚',
        from: '项目',
        url: '/api/images/i1/raw',
        c1: '#fff',
        c2: '#eee',
        projectId: 'p1',
      },
    ]
    data.hostedPages = [
      {
        id: 'h1',
        slug: 'report',
        title: 'HTML 报告',
        revision: 0,
        fileName: 'report.html',
        hasIcon: false,
        updatedAt: '刚刚',
        url: '/api/pages/report',
        projectId: 'p1',
      },
    ]
    await flushPromises()

    expect(wrapper.text()).toContain('报告.md')
    expect(wrapper.text()).not.toContain('其它.md')
    await wrapper.get('button[title="重新上传文件"]').trigger('click')
    const fileReplacement = wrapper.findComponent({ name: 'AssetReplaceModal' })
    expect(fileReplacement.props('target')).toEqual(
      expect.objectContaining({
        kind: 'file',
        id: 'f1',
        name: '报告.md',
      }),
    )
    fileReplacement.vm.$emit('close')
    await flushPromises()

    await wrapper
      .findAll('.asset-row button')
      .find((button) => button.text().includes('预览'))!
      .trigger('click')
    expect(wrapper.findComponent({ name: 'AssetPreviewModal' }).attributes('asset')).toBeDefined()

    await wrapper.findAll('.asset-tabs button')[1]!.trigger('click')
    expect(wrapper.text()).toContain('截图.png')
    await wrapper.findAll('.asset-tabs button')[2]!.trigger('click')
    expect(wrapper.text()).toContain('HTML 报告')
    await wrapper.get('button[title="重新上传 HTML"]').trigger('click')
    const replacement = wrapper.findComponent({ name: 'AssetReplaceModal' })
    expect(replacement.props('target')).toEqual({
      kind: 'html',
      id: 'h1',
      name: 'HTML 报告',
      url: '/api/pages/report',
      revision: 0,
    })
    wrapper.unmount()
  })

  it('按类型复用真实上传链路并携带当前项目 id', async () => {
    const { data, wrapper } = mountPanel()
    const uploadFile = vi.spyOn(data, 'uploadFile').mockResolvedValue({
      id: 'f1',
      name: '说明.md',
      size: '1 B',
      icon: 'ri-file-line',
      time: '刚刚',
      by: 'wg',
      link: '/api/files/f1/download',
      projectId: 'p1',
    })
    const uploadImage = vi
      .spyOn(data, 'uploadImage')
      .mockResolvedValue({ name: '截图.png', url: '/api/images/i1/raw' })
    const saveHostedPage = vi.spyOn(data, 'saveHostedPage').mockResolvedValue({
      id: 'h1',
      slug: 'project-p1-report-abcde',
      title: 'report',
      fileName: 'report.html',
      hasIcon: false,
      updatedAt: '刚刚',
      url: '/api/pages/project-p1-report-abcde',
      projectId: 'p1',
    })
    const markdown = new File(['# 说明'], '说明.md', { type: 'text/markdown' })
    const image = new File(['png'], '截图.png', { type: 'image/png' })
    const html = new File(['<h1>报告</h1>'], 'report.html', { type: 'text/html' })

    await wrapper
      .find('.asset-drop')
      .trigger('drop', { dataTransfer: { files: [markdown, image, html] } })
    await flushPromises()
    expect(uploadFile).toHaveBeenCalledWith(markdown, 'p1', false, expect.objectContaining({ throwOnError: true }))
    expect(uploadImage).toHaveBeenCalledWith(image, '项目 · WAO', expect.objectContaining({
      requirePersisted: true,
      projectId: 'p1',
      throwOnError: true,
    }))
    expect(wrapper.find('.html-dialog').exists()).toBe(true)

    await wrapper.find('.html-dialog').trigger('submit')
    await flushPromises()
    expect(saveHostedPage).toHaveBeenCalledWith(expect.objectContaining({ html, projectId: 'p1' }))
  })

  it('上传后的项目 HTML 可修改 WAO 显示名称且保留文件和分享地址', async () => {
    const { data, wrapper } = mountPanel()
    data.hostedPages = [
      {
        id: 'h1',
        slug: 'architecture',
        title: 'product_model_architecture',
        revision: 0,
        fileName: 'product_model_architecture.html',
        hasIcon: false,
        updatedAt: '刚刚',
        url: '/api/pages/architecture',
        projectId: 'p1',
      },
    ]
    const saveHostedPage = vi.spyOn(data, 'saveHostedPage').mockImplementation(async (payload) => ({
      ...data.hostedPages[0]!,
      title: payload.title,
    }))
    await flushPromises()

    await wrapper.findAll('.asset-tabs button')[2]!.trigger('click')
    await wrapper.find('button[title="修改 WAO 显示名称"]').trigger('click')
    expect(wrapper.get('[role="dialog"] h2').text()).toBe('修改 WAO 显示名称')
    expect((wrapper.find('input[autofocus]').element as HTMLInputElement).value).toBe(
      'product_model_architecture',
    )

    await wrapper.find('input[autofocus]').setValue('商品模型架构')
    await wrapper.find('.html-dialog').trigger('submit')
    await flushPromises()

    expect(saveHostedPage).toHaveBeenCalledWith({
      id: 'h1',
      html: undefined,
      title: '商品模型架构',
      slug: 'architecture',
      projectId: undefined,
      expectedRevision: 0,
    })
    expect(wrapper.find('.html-dialog').exists()).toBe(false)
  })

  it('项目 HTML 改名失败时保留输入并提供重试', async () => {
    const { data, wrapper } = mountPanel()
    data.hostedPages = [
      {
        id: 'h1',
        slug: 'architecture',
        title: '旧名称',
        fileName: 'architecture.html',
        hasIcon: false,
        updatedAt: '刚刚',
        url: '/api/pages/architecture',
        projectId: 'p1',
      },
    ]
    const saveHostedPage = vi
      .spyOn(data, 'saveHostedPage')
      .mockRejectedValueOnce(new Error('名称保存失败，请重试'))
      .mockResolvedValueOnce({ ...data.hostedPages[0]!, title: '新名称' })
    await flushPromises()

    await wrapper.findAll('.asset-tabs button')[2]!.trigger('click')
    await wrapper.find('button[title="修改 WAO 显示名称"]').trigger('click')
    await wrapper.find('input[autofocus]').setValue('   ')
    await wrapper.find('.html-dialog').trigger('submit')
    expect(saveHostedPage).not.toHaveBeenCalled()
    expect(wrapper.find('[role="alert"]').text()).toContain('请填写页面标题')

    await wrapper.find('input[autofocus]').setValue('新名称')
    await wrapper.find('.html-dialog').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('名称保存失败')
    expect((wrapper.find('input[autofocus]').element as HTMLInputElement).value).toBe('新名称')
    await wrapper.find('.html-dialog').trigger('submit')
    await flushPromises()
    expect(wrapper.find('.html-dialog').exists()).toBe(false)
  })

  it('切换项目并卸载面板后队列继续上传，后续文件保持最初项目归属', async () => {
    const { data, wrapper } = mountPanel()
    let finish!: (value: never) => void
    const upload = vi.spyOn(data, 'uploadFile')
      .mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
      .mockResolvedValue({ id: 'f', name: 'file.txt' } as never)
    const first = new File(['a'], 'first.txt')
    const second = new File(['b'], 'second.txt')
    await wrapper.get('.asset-drop').trigger('drop', { dataTransfer: { files: [first, second] } })
    await wrapper.setProps({ projectId: 'p2', projectName: '另一项目' })
    expect(wrapper.get('.upload-button').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
    finish({ id: 'one', name: 'first.txt' } as never)
    await flushPromises()
    expect(upload.mock.calls.map(args => [args[0].name, args[1]])).toEqual([['first.txt', 'p1'], ['second.txt', 'p1']])
  })

  it('HTML 保存中阻止重复发布，切换项目后不弹出旧项目预览', async () => {
    const { data, wrapper } = mountPanel()
    let finish!: (value: never) => void
    const save = vi.spyOn(data, 'saveHostedPage').mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const file = new File(['<h1>one</h1>'], 'one.html', { type: 'text/html' })
    await wrapper.get('.asset-drop').trigger('drop', { dataTransfer: { files: [file] } })
    await wrapper.get('.html-dialog').trigger('submit')
    await wrapper.get('.html-dialog').trigger('submit')
    expect(save).toHaveBeenCalledTimes(1)
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ projectId: 'p1' }))
    await wrapper.setProps({ projectId: 'p2', projectName: '另一项目' })
    finish({ id: 'html', title: '旧项目网页', url: '/old' } as never)
    await flushPromises()
    expect(wrapper.find('.html-dialog').exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'AssetPreviewModal' }).exists()).toBe(false)
    wrapper.unmount()
  })

  it('项目网页改名冲突保留输入，明确继续时比较新版本并保留最新链接后缀', async () => {
    const { data, wrapper } = mountPanel()
    data.hostedPages = [{ id: 'h1', projectId: 'p1', title: '旧标题', slug: 'old', revision: 0 } as never]
    await flushPromises()
    const save = vi.spyOn(data, 'saveHostedPage').mockRejectedValueOnce(Object.assign(new Error('已修改'), { status: 409 })).mockResolvedValueOnce({} as never)
    vi.mocked(data.refreshHostedPages).mockImplementationOnce(async () => {
      data.hostedPages = [{ id: 'h1', projectId: 'p1', title: '他处标题', slug: 'new-link', revision: 2 } as never]
      return true
    })
    await wrapper.findAll('.asset-tabs button')[2]!.trigger('click')
    await wrapper.get('button[title="修改 WAO 显示名称"]').trigger('click')
    await wrapper.get('input[autofocus]').setValue('我的草稿')
    await wrapper.get('.html-dialog').trigger('submit')
    await flushPromises()
    expect(save).toHaveBeenLastCalledWith(expect.objectContaining({ expectedRevision: 0, title: '我的草稿' }))
    expect((wrapper.get('input[autofocus]').element as HTMLInputElement).value).toBe('我的草稿')
    expect(wrapper.get('.html-conflict').text()).toContain('他处标题')
    await wrapper.get('.html-dialog').trigger('submit')
    expect(save).toHaveBeenCalledTimes(1)
    await wrapper.findAll('.html-conflict button').find(button => button.text() === '仍保存当前名称')!.trigger('click')
    await flushPromises()
    expect(save).toHaveBeenLastCalledWith(expect.objectContaining({ expectedRevision: 2, title: '我的草稿', slug: 'new-link' }))
    expect(wrapper.find('.html-dialog').exists()).toBe(false)
    wrapper.unmount()
  })

  it('项目网页冲突读取失败禁止保存，重读成功可采用最新版本', async () => {
    const { data, wrapper } = mountPanel()
    data.hostedPages = [{ id: 'h1', projectId: 'p1', title: '旧标题', slug: 'old', revision: 1 } as never]
    await flushPromises()
    const save = vi.spyOn(data, 'saveHostedPage').mockRejectedValueOnce(Object.assign(new Error('已修改'), { status: 409 }))
    vi.mocked(data.refreshHostedPages).mockResolvedValueOnce(false).mockImplementationOnce(async () => {
      data.hostedPages = [{ id: 'h1', projectId: 'p1', title: '最新标题', slug: 'latest', revision: 3 } as never]
      return true
    })
    await wrapper.findAll('.asset-tabs button')[2]!.trigger('click')
    await wrapper.get('button[title="修改 WAO 显示名称"]').trigger('click')
    await wrapper.get('input[autofocus]').setValue('我的草稿')
    await wrapper.get('.html-dialog').trigger('submit')
    await flushPromises()
    expect(wrapper.get('.html-dialog .primary').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('最新网页版本读取失败')
    expect(wrapper.findAll('button').some(button => button.text() === '仍保存当前名称')).toBe(false)
    await wrapper.get('.html-dialog').trigger('submit')
    expect(save).toHaveBeenCalledTimes(1)
    await wrapper.findAll('button').find(button => button.text() === '重新读取最新版本')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '使用最新版本')!.trigger('click')
    expect((wrapper.get('input[autofocus]').element as HTMLInputElement).value).toBe('最新标题')
    expect(wrapper.find('.html-conflict').exists()).toBe(false)
    expect(save).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('项目 HTML 未发布内容关闭需确认，刷新离开提示在卸载时清理', async () => {
    const { wrapper } = mountPanel()
    const file = new File(['<h1>草稿</h1>'], 'draft.html', { type: 'text/html' })
    await wrapper.get('.asset-drop').trigger('drop', { dataTransfer: { files: [file] } })
    const leaving = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(leaving)
    expect(leaving.defaultPrevented).toBe(true)
    await wrapper.get('.html-dialog footer button').trigger('click')
    expect(wrapper.find('.html-dialog').exists()).toBe(true)
    expect(wrapper.text()).toContain('放弃未保存的网页修改')
    await wrapper.get('.confirmation-actions button').trigger('click')
    expect(wrapper.get('.selected-file').text()).toContain('draft.html')
    await wrapper.get('.html-dialog footer button').trigger('click')
    await wrapper.get('.confirmation-actions .wao-button--danger').trigger('click')
    await flushPromises()
    expect(wrapper.find('.html-dialog').exists()).toBe(false)
    wrapper.unmount()
    const after = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(after)
    expect(after.defaultPrevented).toBe(false)
  })

  it('项目网页对话框支持 Esc 关闭、焦点循环并回到改名入口', async () => {
    const { data, wrapper } = mountPanel(true)
    data.hostedPages = [{ id: 'h1', projectId: 'p1', title: '原名称', slug: 'report', revision: 0 } as never]
    await wrapper.findAll('.asset-tabs button')[2]!.trigger('click')
    const trigger = wrapper.get('button[title="修改 WAO 显示名称"]')
    ;(trigger.element as HTMLButtonElement).focus()
    await trigger.trigger('click')
    await flushPromises()
    const dialog = wrapper.get('[role="dialog"][aria-label="修改 WAO 显示名称"]')
    expect(dialog.attributes('aria-modal')).toBe('true')
    expect(document.activeElement).toBe(dialog.element)
    const close = wrapper.get('.dialog-close')
    const save = wrapper.get('.html-dialog .primary')
    ;(save.element as HTMLButtonElement).focus()
    await save.trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(close.element)
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await flushPromises()
    expect(wrapper.find('.html-dialog').exists()).toBe(false)
    expect(document.activeElement).toBe(trigger.element)
    wrapper.unmount()
  })

  it('Esc 关闭未发布网页先确认，确认框 Esc 仅返回草稿，保存中保持窗口', async () => {
    const { data, wrapper } = mountPanel()
    const file = new File(['<h1>草稿</h1>'], 'draft.html', { type: 'text/html' })
    await wrapper.get('.asset-drop').trigger('drop', { dataTransfer: { files: [file] } })
    await wrapper.get('input[autofocus]').setValue('未发布标题')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await flushPromises()
    expect(wrapper.findAll('[role="dialog"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('放弃未保存的网页修改')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await flushPromises()
    expect(wrapper.findAll('[role="dialog"]')).toHaveLength(1)
    expect((wrapper.get('input[autofocus]').element as HTMLInputElement).value).toBe('未发布标题')
    expect(wrapper.get('.selected-file').text()).toContain('draft.html')

    let reject!: (cause: Error) => void
    vi.spyOn(data, 'saveHostedPage').mockImplementationOnce(() => new Promise((_resolve, fail) => { reject = fail }))
    await wrapper.get('.html-dialog').trigger('submit')
    expect(wrapper.get('.dialog-close').attributes('disabled')).toBeDefined()
    expect(wrapper.get('input[autofocus]').attributes('disabled')).toBeDefined()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await flushPromises()
    expect(wrapper.findAll('[role="dialog"]')).toHaveLength(1)
    expect(wrapper.find('.confirmation-actions').exists()).toBe(false)
    reject(new Error('暂时无法保存'))
    await flushPromises()
    expect((wrapper.get('input[autofocus]').element as HTMLInputElement).value).toBe('未发布标题')
    expect(wrapper.get('.selected-file').text()).toContain('draft.html')
    wrapper.unmount()
  })
})
