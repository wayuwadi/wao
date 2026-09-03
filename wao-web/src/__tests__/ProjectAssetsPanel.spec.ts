import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectAssetsPanel from '@/components/files/ProjectAssetsPanel.vue'
import { useDataStore } from '@/stores/data'

describe('ProjectAssetsPanel', () => {
  const mountPanel = () => {
    const pinia = createPinia()
    const data = useDataStore(pinia)
    vi.spyOn(data, 'refreshFiles').mockResolvedValue(true)
    vi.spyOn(data, 'refreshImages').mockResolvedValue(true)
    vi.spyOn(data, 'refreshHostedPages').mockResolvedValue(true)
    return {
      data,
      wrapper: mount(ProjectAssetsPanel, {
        props: { projectId: 'p1', projectName: 'WAO' },
        global: { plugins: [pinia], stubs: { AssetPreviewModal: true } },
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
    await wrapper
      .findAll('.asset-row button')
      .find((button) => button.text().includes('预览'))!
      .trigger('click')
    expect(wrapper.findComponent({ name: 'AssetPreviewModal' }).attributes('asset')).toBeDefined()

    await wrapper.findAll('.asset-tabs button')[1]!.trigger('click')
    expect(wrapper.text()).toContain('截图.png')
    await wrapper.findAll('.asset-tabs button')[2]!.trigger('click')
    expect(wrapper.text()).toContain('HTML 报告')
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
    expect(uploadFile).toHaveBeenCalledWith(markdown, 'p1')
    expect(uploadImage).toHaveBeenCalledWith(image, '项目 · WAO', {
      requirePersisted: true,
      projectId: 'p1',
    })
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
    expect(wrapper.find('.html-dialog h3').text()).toBe('修改 WAO 显示名称')
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
})
