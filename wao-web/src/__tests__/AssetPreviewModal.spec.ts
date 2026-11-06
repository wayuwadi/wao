import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import AssetPreviewModal from '@/components/files/AssetPreviewModal.vue'
import { fetchFilePreview } from '@/api/board'

vi.mock('@/api/board', () => ({ fetchFilePreview: vi.fn() }))
const wrappers: VueWrapper[] = []
function preview(asset: InstanceType<typeof AssetPreviewModal>['$props']['asset']) {
  const wrapper = mount(AssetPreviewModal, { props: { asset }, attachTo: document.body, global: { stubs: { teleport: true } } })
  wrappers.push(wrapper)
  return wrapper
}
function result(content: string) { return { id: 'file', name: 'file.md', contentType: 'text/markdown', content } }
beforeEach(() => vi.clearAllMocks())
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = '' })

describe('资源预览生命周期', () => {
  it('真实 Teleport 下超长标题限两行，完整名称可展开且切换资源后收起', async () => {
    const name = '这是用于检验资源预览的完整中文展示名称'.repeat(12)
    const wrapper = mount(AssetPreviewModal, { props: { asset: { kind: 'image', name, url: '/image.png' } }, attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    const heading = document.querySelector<HTMLHeadingElement>('[role="dialog"] h2')!
    expect(heading.classList.contains('limited-title')).toBe(true)
    expect(heading.style.getPropertyValue('--dialog-title-lines')).toBe('2')
    expect(heading.title).toBe(`预览 ${name}`)
    const fullName = document.querySelector<HTMLDetailsElement>('.preview-full-name')!
    expect(fullName.open).toBe(false)
    fullName.querySelector('summary')!.click()
    expect(fullName.open).toBe(true)
    expect(fullName.querySelector('p')!.textContent).toBe(name)
    const summary = fullName.querySelector('summary')!
    summary.focus()
    summary.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }))
    expect(document.activeElement).toBe(document.querySelector('.dialog-close'))
    await wrapper.setProps({ asset: { kind: 'image', name: `${name}第二张`, url: '/second.png' } })
    expect(document.querySelector<HTMLDetailsElement>('.preview-full-name')!.open).toBe(false)
  })
  it('保留人工网页存储与表单能力，AI 报告继续保持隔离', () => {
    const manual = preview({ kind: 'html', name: '演示', url: '/api/pages/demo', sourceType: 'MANUAL_UPLOAD' })
    expect(manual.get('iframe').attributes('sandbox')).toContain('allow-same-origin')
    expect(manual.get('iframe').attributes('sandbox')).toContain('allow-forms')
    expect(manual.get('iframe').attributes('tabindex')).toBe('0')
    const report = preview({ kind: 'html', name: '报告', url: '/api/pages/report', sourceType: 'AI_RUN_REPORT' })
    expect(report.get('iframe').attributes('sandbox')).not.toContain('allow-same-origin')
    expect(report.get('iframe').attributes('sandbox')).not.toContain('allow-forms')
  })

  it('切换预览取消旧请求，迟到的正文不能覆盖当前文件', async () => {
    let finishOld!: (value: ReturnType<typeof result>) => void
    vi.mocked(fetchFilePreview)
      .mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve }))
      .mockResolvedValueOnce(result('# 新文件'))
    const wrapper = preview({ kind: 'markdown', id: 'old', name: '旧文件.md' })
    const oldSignal = vi.mocked(fetchFilePreview).mock.calls[0]?.[1]?.signal
    await wrapper.setProps({ asset: { kind: 'markdown', id: 'new', name: '新文件.md' } })
    await flushPromises()
    expect(oldSignal?.aborted).toBe(true)
    expect(wrapper.get('.markdown-body').text()).toContain('新文件')
    finishOld(result('# 旧文件正文'))
    await flushPromises()
    expect(wrapper.get('.markdown-body').text()).toBe('新文件')
  })

  it('切换到图片后旧请求失败不污染状态，图片加载失败可重试', async () => {
    let failOld!: (cause: Error) => void
    vi.mocked(fetchFilePreview).mockImplementationOnce(() => new Promise((_, reject) => { failOld = reject }))
    const wrapper = preview({ kind: 'markdown', id: 'old', name: '旧文件.md' })
    await wrapper.setProps({ asset: { kind: 'image', url: '/image.png', name: '图片.png' } })
    failOld(new Error('旧请求失败'))
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toBe('正在加载图片…')
    await wrapper.get('.image-preview img').trigger('error')
    expect(wrapper.get('[role="alert"]').text()).toContain('图片未能加载')
    await wrapper.get('[role="alert"] button').trigger('click')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    await wrapper.get('.image-preview img').trigger('load')
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(wrapper.get('.image-preview').isVisible()).toBe(true)
  })

  it('正文失败原位重试，空正文有明确状态', async () => {
    vi.mocked(fetchFilePreview).mockRejectedValueOnce(new Error('暂时无法读取')).mockResolvedValueOnce(result(''))
    const wrapper = preview({ kind: 'markdown', id: 'file', name: 'file.md' })
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('暂时无法读取')
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toBe('这个文件还没有内容。')
    expect(fetchFilePreview).toHaveBeenCalledTimes(2)
  })

  it('旧图片的迟到加载和错误事件不改变新图片状态', async () => {
    const wrapper = preview({ kind: 'image', name: '旧图片', url: '/old.png' })
    const oldImage = wrapper.get('.image-preview img').element
    await wrapper.setProps({ asset: { kind: 'image', name: '新图片', url: '/new.png' } })
    oldImage.dispatchEvent(new Event('load'))
    oldImage.dispatchEvent(new Event('error'))
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toBe('正在加载图片…')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    await wrapper.get('.image-preview img').trigger('load')
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
  })

  it('Escape 关闭时停止请求并在卸载后归还焦点', async () => {
    const trigger = document.createElement('button')
    document.body.append(trigger)
    trigger.focus()
    vi.mocked(fetchFilePreview).mockImplementationOnce(() => new Promise(() => {}))
    const wrapper = preview({ kind: 'markdown', id: 'file', name: 'file.md' })
    const signal = vi.mocked(fetchFilePreview).mock.calls[0]?.[1]?.signal
    await flushPromises()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(signal?.aborted).toBe(true)
    wrapper.unmount()
    expect(document.activeElement).toBe(trigger)
    wrappers.splice(wrappers.indexOf(wrapper), 1)
  })

  it('空地址与非 HTTP 网页地址不能嵌入应用', () => {
    for (const url of ['', 'javascript:alert(1)', 'data:text/html,<h1>bad</h1>']) {
      const wrapper = preview({ kind: 'html', url, name: '无效网页' })
      expect(wrapper.find('iframe').exists()).toBe(false)
      expect(wrapper.get('[role="alert"]').text()).toContain('没有可预览的地址')
    }
  })

  it('图片预览接管键盘焦点，Tab 留在弹窗，Escape 关闭后返回原按钮', async () => {
    const trigger = document.createElement('button')
    trigger.textContent = '预览图片'
    document.body.append(trigger)
    trigger.focus()
    const wrapper = preview({ kind: 'image', name: '封面.png', url: '/image.png' })
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
    wrappers.splice(wrappers.indexOf(wrapper), 1)
  })

  it('Markdown 读取失败可以重试，正文链接处于弹窗焦点循环内', async () => {
    vi.mocked(fetchFilePreview).mockRejectedValueOnce(new Error('预览暂时不可用'))
      .mockResolvedValueOnce(result('[查看说明](https://example.test/help)'))
    const wrapper = preview({ kind: 'markdown', name: '说明.md', id: 'file-markdown' })
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
})
