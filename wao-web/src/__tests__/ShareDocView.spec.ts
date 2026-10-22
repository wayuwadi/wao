import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ShareDocView from '@/views/ShareDocView.vue'
import { apiShareDoc } from '@/api/board'
import { readPublicNote } from '@/api/notePublicShare'
const route = vi.hoisted(() => ({ params: { id: 'd-sized', token: '' } }))

vi.mock('vue-router', () => ({
  useRoute: () => route,
}))

vi.mock('@/api/board', () => ({
  apiShareDoc: vi.fn<(typeof import('@/api/board'))['apiShareDoc']>(),
}))
vi.mock('@/api/notePublicShare', async original => ({ ...await original<object>(), readPublicNote: vi.fn() }))

describe('ShareDocView', () => {
  beforeEach(() => { vi.clearAllMocks(); route.params.token = '' })

  it('小记使用令牌快照，失效清空正文并显示可重试状态', async () => {
    route.params.token = 'ns_' + 'a'.repeat(43)
    vi.mocked(readPublicNote).mockResolvedValueOnce({ doc: { id: 'private', title: '快照标题', note: true, kbId: 'kb-notes', updated: '', md: '私有分享快照' }, expiresAt: new Date(Date.now() + 60_000).toISOString() })
    const wrapper = mount(ShareDocView)
    await flushPromises()
    expect(readPublicNote).toHaveBeenCalledWith(route.params.token)
    expect(apiShareDoc).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('快照标题')
    vi.mocked(readPublicNote).mockRejectedValueOnce(Object.assign(new Error('分享链接无效或已失效'), { status: 404 }))
    document.dispatchEvent(new Event('visibilitychange')); await flushPromises()
    expect(wrapper.find('.doc-body').exists()).toBe(false)
    expect(wrapper.text()).toContain('已撤销或已到期')
    wrapper.unmount()
  })

  it('工作簿由只读表格展示，保留全部工作表快照，不交给正文编辑器', async () => {
    const content = { version: 1, document: { type: 'waoWorkbook', attrs: { snapshot: { id: 'book', sheetOrder: ['a', 'b'], sheets: { a: { name: '清单' }, b: { name: '汇总' } } } } }, markdown: '清单内容' }
    vi.mocked(apiShareDoc).mockResolvedValue({ id: 'sheet', title: '表格', note: false, kbId: 'kb', updated: '', md: '清单内容', content })
    const wrapper = mount(ShareDocView, { global: { stubs: { KnowledgeSheet: { name: 'KnowledgeSheet', props: { content: Object, disabled: Boolean }, template: '<div class="sheet-stub" />' } } } })
    await flushPromises()
    expect(wrapper.find('.doc-body').exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'KnowledgeSheet' }).props()).toMatchObject({ content, disabled: true })
    expect(wrapper.get('.locate').attributes('href')).toBe('/kb/kb?doc=sheet')
    wrapper.unmount()
  })

  it('网络故障保留加载失败反馈，不误报分享已经失效', async () => {
    vi.mocked(apiShareDoc).mockRejectedValueOnce(new Error('网络中断'))
    const wrapper = mount(ShareDocView)
    await flushPromises()
    expect(wrapper.text()).toContain('分享内容加载失败，请重试')
    expect(wrapper.text()).not.toContain('已撤销或已到期')
    wrapper.unmount()
  })

  it('公开分享页按服务端 Markdown 中保存的图片尺寸展示', async () => {
    vi.mocked(apiShareDoc).mockResolvedValue({
      id: 'd-sized',
      title: '带尺寸的文档',
      note: false,
      kbId: 'k1',
      updated: '2026-08-20T12:00:00',
      md: '![架构图](/api/images/img1/raw "wao-image-size:75%xauto")',
    })

    const wrapper = mount(ShareDocView)
    await flushPromises()

    expect(apiShareDoc).toHaveBeenCalledWith('d-sized')
    expect(wrapper.get('.doc-body img').attributes('style')).toContain('width: 75%')
    expect(wrapper.get('.doc-body img').attributes('style')).toContain('height: auto')
    expect(wrapper.get('.doc-body img').attributes('title')).toBeUndefined()
    wrapper.unmount()
  })
})

